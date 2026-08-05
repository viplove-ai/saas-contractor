import imageCompression from 'browser-image-compression';
import { apiClient } from '../shared/apiClient';
import { offlineDb, type DraftEntityType, type PendingUpload } from './db';

/**
 * What a bill photograph is compressed to before it is queued.
 *
 * <p>The numbers come from the job rather than from a guess about bandwidth. A bill is read,
 * not admired: 1600 pixels on the long edge is enough to read a GST number and a total off a
 * printed invoice held at arm's length, and it is roughly a fifth of what a modern phone
 * camera produces. 700 KB is the ceiling the compressor works down to from there.</p>
 *
 * <p>Compressing on the device rather than on the server is the whole point. A supervisor on
 * a 2G edge uploads what the device sends, so a four-megabyte original is four megabytes of
 * his morning whatever the server does with it afterwards — and on the day he has no signal
 * at all it is four megabytes sitting in IndexedDB against a browser storage quota he cannot
 * see and will not understand when it is exceeded.</p>
 */
export const PHOTO_MAX_BYTES = 700 * 1024;
export const PHOTO_MAX_EDGE_PX = 1600;

/** JPEG regardless of what came in: the backend accepts it and it is the smallest of the three. */
const OUTPUT_TYPE = 'image/jpeg';

export interface CompressedPhoto {
  blob: Blob;
  fileName: string;
  contentType: string;
  originalBytes: number;
}

/**
 * Shrinks a photograph to something a phone can hold and a bad connection can send.
 *
 * <p>A file that is already small enough is passed through untouched rather than
 * re-encoded, because a second JPEG pass over an already-compressed image costs quality and
 * buys nothing. Anything that is not an image — a PDF bill emailed by the vendor — is also
 * passed through: there is nothing to resize and the compressor would reject it.</p>
 */
export async function compressPhoto(file: File): Promise<CompressedPhoto> {
  const originalBytes = file.size;
  const passThrough = !file.type.startsWith('image/') || file.size <= PHOTO_MAX_BYTES;
  if (passThrough) {
    return {
      blob: file,
      fileName: file.name,
      contentType: file.type || 'application/octet-stream',
      originalBytes,
    };
  }
  const compressed = await imageCompression(file, {
    maxSizeMB: PHOTO_MAX_BYTES / (1024 * 1024),
    maxWidthOrHeight: PHOTO_MAX_EDGE_PX,
    fileType: OUTPUT_TYPE,
    useWebWorker: true,
  });
  return {
    blob: compressed,
    fileName: replaceExtension(file.name, 'jpg'),
    contentType: OUTPUT_TYPE,
    originalBytes,
  };
}

export interface QueuePhotoInput {
  /** The record this is evidence for — the same client-generated id the record carries. */
  clientId: string;
  entityType: DraftEntityType;
  siteId: string;
  file: File;
}

/**
 * Compresses a photograph and queues it against its record. Returns what the compression
 * achieved, so the screen can tell the supervisor his four-megabyte photograph became four
 * hundred kilobytes rather than leaving him watching a spinner.
 */
export async function queuePhoto(
  input: QueuePhotoInput,
): Promise<{ bytes: number; originalBytes: number }> {
  const photo = await compressPhoto(input.file);
  const upload: PendingUpload = {
    uploadId: crypto.randomUUID(),
    clientId: input.clientId,
    entityType: input.entityType,
    fileName: photo.fileName,
    contentType: photo.contentType,
    blob: photo.blob,
    originalBytes: photo.originalBytes,
    status: 'PENDING',
    attempts: 0,
    nextAttemptAt: 0,
    createdAt: new Date().toISOString(),
  };
  // The site travels on the upload row through the draft it belongs to; storing it twice
  // would let the two disagree after an edit.
  await offlineDb.uploads.put(upload);
  return { bytes: photo.blob.size, originalBytes: photo.originalBytes };
}

/**
 * Sends one queued photograph: the file into object storage, then the link onto the record.
 *
 * <p>Two calls, and the order matters. An attachment with no owner is a draft upload the
 * uploader can still delete; a link to an attachment that does not exist is a broken row on
 * a bill somebody has to approve. If the second call fails the first is retried with it —
 * the cost is an orphaned file in the bucket, which is cheap and sweepable, against an
 * expense that claims evidence it cannot show.</p>
 */
export async function uploadAttachment(upload: PendingUpload): Promise<string> {
  const draft = await offlineDb.drafts.get(upload.clientId);
  const form = new FormData();
  form.append('file', upload.blob, upload.fileName);
  const created = await apiClient.post<{ id: string }>('/attachments', form, {
    params: {
      ownerEntityType: upload.entityType,
      kind: upload.entityType === 'EXPENSE' ? 'BILL' : 'PHOTO',
      ...(draft?.siteId ? { siteId: draft.siteId } : {}),
    },
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  const attachmentId = created.data.id;
  if (upload.entityType === 'EXPENSE') {
    await apiClient.post(`/expenses/${upload.clientId}/attachments`, {
      attachmentId,
      docType: 'BILL',
    });
  }
  return attachmentId;
}

function replaceExtension(name: string, extension: string): string {
  const dot = name.lastIndexOf('.');
  const stem = dot > 0 ? name.slice(0, dot) : name;
  return `${stem}.${extension}`;
}

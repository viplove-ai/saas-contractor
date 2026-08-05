import Dexie, { type Table } from 'dexie';

export type SyncStatus = 'DRAFT' | 'PENDING' | 'SYNCED' | 'FAILED' | 'CONFLICT';

export type DraftEntityType =
  | 'ATTENDANCE'
  | 'EXPENSE'
  | 'GOODS_RECEIPT'
  | 'MATERIAL_ISSUE'
  | 'DPR';

/**
 * One record per offline draft. `clientId` is generated on the device and becomes the
 * server-side primary key, so re-sending after a dropped connection updates the same row
 * instead of creating a second one.
 */
export interface DraftRecord {
  clientId: string;
  entityType: DraftEntityType;
  siteId: string;
  businessDate: string; // yyyy-MM-dd
  payload: unknown;
  status: SyncStatus;
  attempts: number;
  lastError?: string;
  clientUpdatedAt: string; // ISO instant, used for conflict resolution
  createdAt: string;
}

/** Photos are queued separately: form data syncs first so a record is never lost to a large upload. */
export interface PendingUpload {
  uploadId: string;
  clientId: string;
  entityType: DraftEntityType;
  fileName: string;
  contentType: string;
  blob: Blob;
  status: SyncStatus;
  attempts: number;
  createdAt: string;
}

export class NirmanOfflineDb extends Dexie {
  drafts!: Table<DraftRecord, string>;
  uploads!: Table<PendingUpload, string>;

  constructor() {
    super('nirman-offline');
    this.version(1).stores({
      drafts: 'clientId, entityType, status, siteId, businessDate',
      uploads: 'uploadId, clientId, status',
    });
  }
}

export const offlineDb = new NirmanOfflineDb();

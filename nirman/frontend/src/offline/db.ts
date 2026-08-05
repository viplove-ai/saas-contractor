import Dexie, { type Table } from 'dexie';

/**
 * Where a queued record stands.
 *
 * <p>PENDING is the normal life of a record with no signal behind it: it is waiting and it
 * will be retried. FAILED and CONFLICT both mean the queue has stopped trying, and the
 * difference is who has to act — FAILED is the server saying no for a reason the device
 * cannot fix by waiting, CONFLICT is the server saying somebody else got there first, which
 * is the only case where a person has to choose between two versions.</p>
 *
 * <p>SYNCED rows are kept briefly rather than deleted on the spot, so the sync screen can
 * show "18 sent" instead of an empty list that looks like the work went nowhere.</p>
 */
export type SyncStatus = 'PENDING' | 'SYNCED' | 'FAILED' | 'CONFLICT';

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
  lastError?: string | undefined;
  /**
   * What to show on the sync screen. Written when the draft is queued, because by the time
   * anybody reads the queue the screen that could name the site and the workers is gone.
   */
  summary: string;
  /**
   * Earliest instant the queue may try this again, in epoch milliseconds. Backoff lives on
   * the row rather than in memory so a reload does not reset a failing record to "try now"
   * and hammer a server that is already unwell.
   */
  nextAttemptAt: number;
  clientUpdatedAt: string; // ISO instant, used for conflict resolution
  createdAt: string;
  syncedAt?: string | undefined;
}

/** Photos are queued separately: form data syncs first so a record is never lost to a large upload. */
export interface PendingUpload {
  uploadId: string;
  clientId: string;
  entityType: DraftEntityType;
  fileName: string;
  contentType: string;
  blob: Blob;
  /** Bytes before compression, kept so the sync screen can show what the device saved. */
  originalBytes: number;
  status: SyncStatus;
  attempts: number;
  lastError?: string | undefined;
  nextAttemptAt: number;
  createdAt: string;
}

export class NirmanOfflineDb extends Dexie {
  drafts!: Table<DraftRecord, string>;
  uploads!: Table<PendingUpload, string>;

  constructor(name = 'nirman-offline') {
    super(name);
    this.version(1).stores({
      drafts: 'clientId, entityType, status, siteId, businessDate',
      uploads: 'uploadId, clientId, status',
    });
    /*
      Version 2 adds the scheduling and description columns the queue needs. Dexie only
      indexes what is listed here, so `nextAttemptAt` is indexed (the drain query orders by
      it) while `summary` is not — it is read, never searched.

      The upgrade stamps the new columns on rows written by version 1 rather than dropping
      them. A supervisor who installed the app on Friday and opens it after the update on
      Monday still has Friday's unsent muster, and losing it silently would be the worst
      possible way to ship an offline feature.
    */
    this.version(2)
      .stores({
        drafts: 'clientId, entityType, status, siteId, businessDate, nextAttemptAt',
        uploads: 'uploadId, clientId, status, nextAttemptAt',
      })
      .upgrade(async (tx) => {
        await tx
          .table<DraftRecord>('drafts')
          .toCollection()
          .modify((draft) => {
            draft.summary ??= `${draft.entityType} · ${draft.businessDate}`;
            draft.nextAttemptAt ??= 0;
            // 'DRAFT' was a fifth status in the version-1 schema. Anything that survived an
            // upgrade was never sent, which is exactly what PENDING means now.
            if ((draft.status as string) === 'DRAFT') {
              draft.status = 'PENDING';
            }
          });
        await tx
          .table<PendingUpload>('uploads')
          .toCollection()
          .modify((upload) => {
            upload.nextAttemptAt ??= 0;
            upload.originalBytes ??= upload.blob?.size ?? 0;
            if ((upload.status as string) === 'DRAFT') {
              upload.status = 'PENDING';
            }
          });
      });
  }
}

export const offlineDb = new NirmanOfflineDb();

/** Statuses that still owe the server something, in the order the sync screen lists them. */
export const UNSENT_STATUSES: SyncStatus[] = ['CONFLICT', 'FAILED', 'PENDING'];

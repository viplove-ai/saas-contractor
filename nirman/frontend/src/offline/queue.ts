import {
  offlineDb,
  type DraftEntityType,
  type DraftRecord,
  type PendingUpload,
  type SyncStatus,
} from './db';
import { uploadAttachment } from './uploads';
import { syncHandlers } from './handlers';
import { classifyFailure, nextAttemptAt, OUTCOME_STATUS } from './retry';

/** What one pass over the queue did. */
export interface DrainSummary {
  sent: number;
  stillWaiting: number;
  conflicted: number;
  failed: number;
  uploaded: number;
}

const EMPTY_SUMMARY: DrainSummary = {
  sent: 0,
  stillWaiting: 0,
  conflicted: 0,
  failed: 0,
  uploaded: 0,
};

/** How long a sent record stays visible before the queue forgets it. */
const KEEP_SYNCED_MS = 6 * 60 * 60_000;

/** A record the queue has given up retrying on its own, after this many tries. */
const MAX_ATTEMPTS = 8;

export interface QueueInput {
  clientId: string;
  entityType: DraftEntityType;
  siteId: string;
  businessDate: string;
  payload: unknown;
  /** One line naming what this is, for the sync screen. Written now; the screen is gone later. */
  summary: string;
}

/**
 * Puts a record in the queue.
 *
 * <p>{@code put} rather than {@code add}: a supervisor who edits the muster and saves again
 * before the first save has gone out has one unsent batch, not two. The id is the same
 * because it came from the same screen, and the second payload is the one they meant.</p>
 */
export async function enqueue(input: QueueInput): Promise<void> {
  const now = new Date().toISOString();
  const existing = await offlineDb.drafts.get(input.clientId);
  const record: DraftRecord = {
    ...input,
    status: 'PENDING',
    attempts: 0,
    nextAttemptAt: 0,
    clientUpdatedAt: now,
    createdAt: existing?.createdAt ?? now,
  };
  await offlineDb.drafts.put(record);
}

let draining: Promise<DrainSummary> | null = null;

/**
 * Sends everything that is due, oldest first, one at a time.
 *
 * <p>Sequential on purpose. Records from one site and day frequently touch the same rows —
 * a muster saved twice, an expense and the bill behind it — and firing them together turns
 * an ordering the device controls into a race the server has to arbitrate. The queue is a
 * few dozen records after a morning with no signal, so serial costs seconds and buys the
 * property that a record which cannot go out does not hold up an unrelated one.</p>
 *
 * <p>Single-flight: the online event, the periodic tick and the sync screen's Retry button
 * all call this, and two drains at once would send the same record twice concurrently.
 * Idempotency on the server makes that harmless rather than correct, and the queue should
 * not lean on the server to cover for it.</p>
 */
export function drain(now: number = Date.now()): Promise<DrainSummary> {
  draining ??= runDrain(now).finally(() => {
    draining = null;
  });
  return draining;
}

async function runDrain(now: number): Promise<DrainSummary> {
  if (typeof navigator !== 'undefined' && navigator.onLine === false) {
    return EMPTY_SUMMARY;
  }
  const summary: DrainSummary = { ...EMPTY_SUMMARY };

  const due = await offlineDb.drafts
    .where('status')
    .equals('PENDING' satisfies SyncStatus)
    .toArray();
  due.sort((a, b) => a.createdAt.localeCompare(b.createdAt));

  for (const draft of due) {
    if (draft.nextAttemptAt > now) {
      summary.stillWaiting++;
      continue;
    }
    const outcome = await sendOne(draft, now);
    summary[outcome]++;
    // A record that could not go out because there is no signal ends the pass. Every record
    // behind it would fail the same way, and eight identical timeouts is eight times the
    // battery for the same answer.
    if (outcome === 'stillWaiting') {
      break;
    }
  }

  summary.uploaded = await drainUploads(now);
  await pruneSynced(now);
  return summary;
}

type DraftOutcome = 'sent' | 'stillWaiting' | 'conflicted' | 'failed';

async function sendOne(draft: DraftRecord, now: number): Promise<DraftOutcome> {
  const handler = syncHandlers[draft.entityType];
  if (!handler) {
    await offlineDb.drafts.update(draft.clientId, {
      status: 'FAILED' satisfies SyncStatus,
      lastError: `${draft.entityType} records cannot be sent from this queue.`,
    });
    return 'failed';
  }

  try {
    await handler.send(draft.payload);
    await offlineDb.drafts.update(draft.clientId, {
      status: 'SYNCED' satisfies SyncStatus,
      syncedAt: new Date(now).toISOString(),
      lastError: undefined,
    });
    return 'sent';
  } catch (error) {
    const failure = classifyFailure(error);
    const attempts = draft.attempts + 1;
    const exhausted = failure.kind !== 'CONFLICT' && attempts >= MAX_ATTEMPTS;
    const status: SyncStatus = exhausted ? 'FAILED' : OUTCOME_STATUS[failure.kind];
    await offlineDb.drafts.update(draft.clientId, {
      status,
      attempts,
      nextAttemptAt: status === 'PENDING' ? nextAttemptAt(attempts, now) : 0,
      lastError: exhausted
        ? `${failure.detail} Given up after ${attempts} tries — send it from here when you can.`
        : failure.detail,
    });
    if (status === 'CONFLICT') return 'conflicted';
    if (status === 'FAILED') return 'failed';
    return 'stillWaiting';
  }
}

/**
 * Photos go after the records they belong to and never before.
 *
 * <p>A bill photograph is evidence for an expense; an expense with no photograph is still an
 * expense, and a photograph with no expense is a file nobody can find. It is also the large
 * one — a phone camera's output is a hundred times the size of the form beside it even
 * after compression — so putting it second is what stops a slow upload on the edge of
 * coverage from holding back the day's figures.</p>
 */
async function drainUploads(now: number): Promise<number> {
  const due = await offlineDb.uploads
    .where('status')
    .equals('PENDING' satisfies SyncStatus)
    .toArray();
  let uploaded = 0;
  for (const upload of due) {
    if (upload.nextAttemptAt > now) continue;
    // The record has to be on the server before its evidence can point at it.
    const owner = await offlineDb.drafts.get(upload.clientId);
    if (owner && owner.status !== 'SYNCED') continue;
    try {
      await uploadAttachment(upload);
      await offlineDb.uploads.update(upload.uploadId, {
        status: 'SYNCED' satisfies SyncStatus,
      });
      uploaded++;
    } catch (error) {
      const failure = classifyFailure(error);
      const attempts = upload.attempts + 1;
      const exhausted = attempts >= MAX_ATTEMPTS;
      await offlineDb.uploads.update(upload.uploadId, {
        status: exhausted ? 'FAILED' : OUTCOME_STATUS[failure.kind],
        attempts,
        nextAttemptAt: exhausted ? 0 : nextAttemptAt(attempts, now),
        lastError: failure.detail,
      });
      if (failure.kind === 'OFFLINE') break;
    }
  }
  return uploaded;
}

/**
 * Keeps this device's version over the server's objection, with a reason that goes on the
 * record. Only available where the server has a door for it — see {@link SyncHandler.force}.
 */
export async function keepMine(clientId: string, reason: string): Promise<void> {
  const draft = await offlineDb.drafts.get(clientId);
  if (!draft) return;
  const handler = syncHandlers[draft.entityType];
  if (!handler?.force) {
    throw new Error('This one cannot be forced through. Discard it and fix the record on the server.');
  }
  try {
    await handler.force(draft.payload, reason);
    await offlineDb.drafts.update(clientId, {
      status: 'SYNCED' satisfies SyncStatus,
      syncedAt: new Date().toISOString(),
      lastError: undefined,
    });
  } catch (error) {
    const failure = classifyFailure(error);
    await offlineDb.drafts.update(clientId, { lastError: failure.detail });
    throw error;
  }
}

/**
 * Drops this device's version and leaves the server's alone.
 *
 * <p>The record is deleted rather than marked, because the thing it was going to say is now
 * decided and a row that says "not sent, on purpose" is one more thing to explain to the
 * next person who opens the screen.</p>
 */
export async function discard(clientId: string): Promise<void> {
  await offlineDb.transaction('rw', offlineDb.drafts, offlineDb.uploads, async () => {
    await offlineDb.drafts.delete(clientId);
    await offlineDb.uploads.where('clientId').equals(clientId).delete();
  });
}

/** Clears the backoff and puts a failed record back in line. */
export async function retryNow(clientId: string): Promise<void> {
  await offlineDb.drafts.update(clientId, {
    status: 'PENDING' satisfies SyncStatus,
    attempts: 0,
    nextAttemptAt: 0,
    lastError: undefined,
  });
}

/** Forgets records that went out long enough ago that nobody is still looking for them. */
export async function pruneSynced(now: number): Promise<void> {
  const cutoff = new Date(now - KEEP_SYNCED_MS).toISOString();
  const stale = await offlineDb.drafts
    .where('status')
    .equals('SYNCED' satisfies SyncStatus)
    .filter((draft) => (draft.syncedAt ?? draft.createdAt) < cutoff)
    .primaryKeys();
  if (stale.length > 0) {
    await Promise.all(stale.map(discard));
  }
}

/** Everything the sync screen shows, unsent first and oldest first within that. */
export async function listQueue(): Promise<DraftRecord[]> {
  const all = await offlineDb.drafts.toArray();
  const rank: Record<SyncStatus, number> = {
    CONFLICT: 0,
    FAILED: 1,
    PENDING: 2,
    SYNCED: 3,
  };
  return all.sort(
    (a, b) => rank[a.status] - rank[b.status] || a.createdAt.localeCompare(b.createdAt),
  );
}

/** The uploads still owed for a record, for the sync screen's per-row detail. */
export async function uploadsFor(clientId: string): Promise<PendingUpload[]> {
  return offlineDb.uploads.where('clientId').equals(clientId).toArray();
}

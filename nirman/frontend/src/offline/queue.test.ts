import { AxiosError, AxiosHeaders } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { offlineDb } from './db';
import { discard, drain, enqueue, keepMine, listQueue, retryNow } from './queue';

const send = vi.fn();
const force = vi.fn();
const uploadAttachment = vi.fn();

// The handlers are the queue's one dependency on the network, so they are the seam. Nothing
// else is stubbed: the store underneath is a real Dexie database over fake-indexeddb, because
// the behaviours under test here are almost all about what survives in it.
vi.mock('./handlers', () => ({
  syncHandlers: {
    ATTENDANCE: { send: (p: unknown) => send(p) },
    EXPENSE: { send: (p: unknown) => send(p), force: (p: unknown, r: string) => force(p, r) },
  },
}));
vi.mock('./uploads', () => ({
  uploadAttachment: (u: unknown) => uploadAttachment(u),
}));

function offline(): AxiosError {
  return new AxiosError('Network Error');
}

function refused(status: number, detail: string): AxiosError {
  const error = new AxiosError('refused');
  error.response = {
    status,
    statusText: '',
    headers: new AxiosHeaders(),
    config: { headers: new AxiosHeaders() },
    data: { detail },
  };
  return error;
}

const muster = {
  clientId: 'attendance:site-a:2026-08-05',
  entityType: 'ATTENDANCE' as const,
  siteId: 'site-a',
  businessDate: '2026-08-05',
  payload: { siteId: 'site-a', date: '2026-08-05', entries: [{ id: 'e1' }] },
  summary: '12 attendance mark(s) — KSN-A Kausani',
};

const bill = {
  clientId: 'expense-1',
  entityType: 'EXPENSE' as const,
  siteId: 'site-a',
  businessDate: '2026-08-05',
  payload: { id: 'expense-1', description: 'Cartage' },
  summary: 'Cartage — KSN-A Kausani',
};

describe('the offline sync queue', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    send.mockResolvedValue(undefined);
    force.mockResolvedValue(undefined);
    uploadAttachment.mockResolvedValue('attachment-1');
    await offlineDb.drafts.clear();
    await offlineDb.uploads.clear();
    vi.stubGlobal('navigator', { onLine: true });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  // ---------------------------------------------------------------- the exit criterion

  /**
   * The phase's exit criterion, from the client's side. The server is idempotent on the
   * client-generated id and has its own test saying so; this one says the queue does not lean
   * on that. A record that has gone out is marked, and a second drain does not touch it.
   */
  it('sends a queued record once, however many times the queue is drained', async () => {
    await enqueue(muster);

    await drain(0);
    await drain(1000);
    await drain(2000);

    expect(send).toHaveBeenCalledTimes(1);
    expect(send).toHaveBeenCalledWith(muster.payload);
    const [row] = await listQueue();
    expect(row?.status).toBe('SYNCED');
  });

  /**
   * Two drains fired at once — the online event and the periodic tick landing together, which
   * on a reconnect is exactly what happens. Without the single-flight guard both would read
   * the same PENDING row and send it concurrently.
   */
  it('does not send twice when two drains start together', async () => {
    await enqueue(muster);

    await Promise.all([drain(0), drain(0), drain(0)]);

    expect(send).toHaveBeenCalledTimes(1);
  });

  /**
   * A supervisor who marks ten more men after lunch has one unsent muster carrying everything,
   * not two that overlap. The screen re-queues under the same id and the later payload wins.
   */
  it('replaces an unsent record rather than queueing a second copy of it', async () => {
    await enqueue(muster);
    await enqueue({ ...muster, payload: { ...muster.payload, entries: [{ id: 'e1' }, { id: 'e2' }] } });

    expect(await offlineDb.drafts.count()).toBe(1);
    await drain(0);
    expect(send).toHaveBeenCalledTimes(1);
    expect(send.mock.calls[0]?.[0]).toMatchObject({ entries: [{ id: 'e1' }, { id: 'e2' }] });
  });

  // ---------------------------------------------------------------- staying queued

  it('keeps a record waiting when there is no answer, and backs off before trying again', async () => {
    send.mockRejectedValue(offline());
    await enqueue(muster);

    await drain(0);
    const afterFirst = await offlineDb.drafts.get(muster.clientId);
    expect(afterFirst?.status).toBe('PENDING');
    expect(afterFirst?.attempts).toBe(1);
    expect(afterFirst?.nextAttemptAt).toBe(30_000);

    // Too soon: the queue leaves it alone rather than spending the radio on it.
    await drain(10_000);
    expect(send).toHaveBeenCalledTimes(1);

    // Due, and now it goes.
    send.mockResolvedValue(undefined);
    await drain(31_000);
    expect(send).toHaveBeenCalledTimes(2);
    expect((await offlineDb.drafts.get(muster.clientId))?.status).toBe('SYNCED');
  });

  it('stops the pass at the first record with no connection behind it', async () => {
    send.mockRejectedValue(offline());
    await enqueue(muster);
    await enqueue(bill);

    await drain(0);

    // One attempt, not two: the second record would have failed identically, and eight
    // identical timeouts is eight times the battery for the same answer.
    expect(send).toHaveBeenCalledTimes(1);
    const rows = await listQueue();
    expect(rows.every((row) => row.status === 'PENDING')).toBe(true);
  });

  it('does not reach for the network at all when the device says it is offline', async () => {
    vi.stubGlobal('navigator', { onLine: false });
    await enqueue(muster);

    await drain(0);

    expect(send).not.toHaveBeenCalled();
  });

  // ---------------------------------------------------------------- giving up

  it('gives up after enough tries and asks for a person', async () => {
    send.mockRejectedValue(refused(503, 'The server is restarting.'));
    await enqueue(muster);

    // Eight attempts, each one due because the clock is moved past its backoff.
    for (let attempt = 1; attempt <= 8; attempt++) {
      await drain(attempt * 3_600_000);
    }

    const row = await offlineDb.drafts.get(muster.clientId);
    expect(row?.status).toBe('FAILED');
    expect(row?.lastError).toContain('Given up after 8 tries');
  });

  /**
   * The give-up budget is for a server that keeps saying something unhelpful. Silence is not
   * the server saying anything at all, and a phone at the edge of a cell — where the browser
   * reports a connection that is actually dead, so the drain does run — produces a whole day
   * of it. Counting those would hand the supervisor an evening's work of pressing Retry on
   * records that were never refused by anybody.
   */
  it('never gives up on a record that simply got no answer', async () => {
    send.mockRejectedValue(offline());
    await enqueue(muster);

    for (let attempt = 1; attempt <= 20; attempt++) {
      await drain(attempt * 3_600_000);
    }

    const row = await offlineDb.drafts.get(muster.clientId);
    expect(row?.status).toBe('PENDING');
    expect(row?.attempts).toBe(20);

    // And it still goes out under its own id the moment somebody answers.
    send.mockResolvedValue(undefined);
    await drain(21 * 3_600_000);
    expect((await offlineDb.drafts.get(muster.clientId))?.status).toBe('SYNCED');
  });

  it('marks a refusal on the merits failed at once, without eight rounds of hope', async () => {
    send.mockRejectedValue(refused(422, 'August is closed for attendance.'));
    await enqueue(muster);

    await drain(0);

    const row = await offlineDb.drafts.get(muster.clientId);
    expect(row?.status).toBe('FAILED');
    expect(row?.lastError).toBe('August is closed for attendance.');
    expect(row?.attempts).toBe(1);
  });

  it('puts a failed record back in line when somebody asks it to', async () => {
    send.mockRejectedValue(refused(422, 'August is closed for attendance.'));
    await enqueue(muster);
    await drain(0);

    send.mockResolvedValue(undefined);
    await retryNow(muster.clientId);
    await drain(1000);

    expect((await offlineDb.drafts.get(muster.clientId))?.status).toBe('SYNCED');
  });

  // ---------------------------------------------------------------- conflicts

  it('parks a conflict for a decision instead of retrying it', async () => {
    send.mockRejectedValue(refused(409, 'This looks like an expense already on file.'));
    await enqueue(bill);

    await drain(0);
    await drain(10_000_000);

    expect(send).toHaveBeenCalledTimes(1);
    const row = await offlineDb.drafts.get(bill.clientId);
    expect(row?.status).toBe('CONFLICT');
    expect(row?.lastError).toBe('This looks like an expense already on file.');
  });

  it('books it anyway when a person keeps their version, with their reason attached', async () => {
    send.mockRejectedValue(refused(409, 'This looks like an expense already on file.'));
    await enqueue(bill);
    await drain(0);

    await keepMine(bill.clientId, 'Second lorry the same afternoon');

    expect(force).toHaveBeenCalledWith(bill.payload, 'Second lorry the same afternoon');
    expect((await offlineDb.drafts.get(bill.clientId))?.status).toBe('SYNCED');
  });

  /**
   * A worker already marked for the day is not a judgement call — there is one man and one
   * day. The handler has no force door, and the queue refuses rather than sending something
   * that would 409 again.
   */
  it('refuses to force a record the server has no door for', async () => {
    send.mockRejectedValue(refused(409, 'Naresh is already marked for 2026-08-05.'));
    await enqueue(muster);
    await drain(0);

    await expect(keepMine(muster.clientId, 'I marked him myself')).rejects.toThrow(
      /cannot be forced through/,
    );
    expect((await offlineDb.drafts.get(muster.clientId))?.status).toBe('CONFLICT');
  });

  it('forgets a discarded record and the photographs queued behind it', async () => {
    await enqueue(bill);
    await offlineDb.uploads.put({
      uploadId: 'u1',
      clientId: bill.clientId,
      entityType: 'EXPENSE',
      fileName: 'bill.jpg',
      contentType: 'image/jpeg',
      blob: new Blob(['x']),
      originalBytes: 4_000_000,
      status: 'PENDING',
      attempts: 0,
      nextAttemptAt: 0,
      createdAt: new Date().toISOString(),
    });

    await discard(bill.clientId);

    expect(await offlineDb.drafts.count()).toBe(0);
    expect(await offlineDb.uploads.count()).toBe(0);
  });

  // ---------------------------------------------------------------- photographs

  it('sends the record before the photograph that belongs to it', async () => {
    await enqueue(bill);
    await offlineDb.uploads.put({
      uploadId: 'u1',
      clientId: bill.clientId,
      entityType: 'EXPENSE',
      fileName: 'bill.jpg',
      contentType: 'image/jpeg',
      blob: new Blob(['x']),
      originalBytes: 4_000_000,
      status: 'PENDING',
      attempts: 0,
      nextAttemptAt: 0,
      createdAt: new Date().toISOString(),
    });
    // The expense cannot go out yet, so its evidence must not either — an attachment linked
    // to an expense the server has never heard of is a broken row on somebody's approval list.
    send.mockRejectedValueOnce(offline());

    await drain(0);
    expect(uploadAttachment).not.toHaveBeenCalled();

    send.mockResolvedValue(undefined);
    await drain(60_000);
    expect(uploadAttachment).toHaveBeenCalledTimes(1);
    expect((await offlineDb.uploads.get('u1'))?.status).toBe('SYNCED');
  });
});

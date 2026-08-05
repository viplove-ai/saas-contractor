import { apiClient } from '../shared/apiClient';
import type { DraftEntityType } from './db';

/**
 * How the queue sends one kind of record.
 *
 * <p>Every endpoint behind these takes an id the device generated, and every one of them
 * answers a repeat of an id it already has by returning what it stored rather than storing
 * it again. That is the whole basis of the queue: it may send the same thing twice — after
 * a response that never arrived, after a reload mid-drain — and the second send is a
 * read.</p>
 */
export interface SyncHandler {
  /**
   * Sends it. Resolves on success, throws whatever the api client threw.
   *
   * <p>The payload is {@code unknown} because that is what it is: it came back out of
   * IndexedDB, possibly written by a version of the app that has since been updated. Each
   * handler states the shape it expects in a payload interface beside it, and the queueing
   * helpers hold the screens to that shape on the way in — which is where a type can
   * actually stop a mistake.</p>
   */
  send(payload: unknown): Promise<void>;
  /**
   * Sends it again over a conflict the person has chosen to keep, with their stated reason.
   *
   * <p>Absent where the server has no such door, and that absence is deliberate rather than
   * an omission. An expense that looks like a duplicate can genuinely be a second delivery,
   * so somebody who can see the site is allowed to say so and book it. A worker already
   * marked present for the day by another device is not a judgement call — there is one
   * man and one day, and the only honest resolutions are to drop this copy or to go and
   * correct the row that exists.</p>
   */
  force?(payload: unknown, reason: string): Promise<void>;
}

/** The muster batch exactly as {@code POST /attendance/bulk} takes it. */
export interface AttendancePayload {
  siteId: string;
  date: string;
  entries: {
    id: string;
    workerId: string;
    status: string;
    checkInTime?: string | undefined;
    checkOutTime?: string | undefined;
    breakMinutes: number;
    enteredHours?: number | undefined;
    overtimeReason?: string | undefined;
  }[];
}

/** The expense exactly as {@code POST /expenses} takes it, id included. */
export interface ExpensePayload {
  id: string;
  siteId: string;
  expenseDate: string;
  categoryId: string;
  description: string;
  billNumber?: string | undefined;
  amountBeforeTax: number;
  gstPercent: number;
  noBillReason?: string | undefined;
  duplicateOverrideReason?: string | undefined;
}

const attendance: SyncHandler = {
  async send(payload) {
    await apiClient.post('/attendance/bulk', payload as AttendancePayload);
  },
};

const expense: SyncHandler = {
  async send(payload) {
    await apiClient.post('/expenses', payload as ExpensePayload);
  },
  async force(payload, reason) {
    await apiClient.post(
      '/expenses',
      { ...(payload as ExpensePayload), duplicateOverrideReason: reason },
      { params: { force: true } },
    );
  },
};

/**
 * Types with no handler cannot be queued, and the queue says so rather than retrying
 * forever. The DPR is the one that is missing on purpose: its draft already lives on the
 * server from the first save and the wizard edits it there across several sittings, so it
 * has a life the queue's send-once-and-forget shape does not fit.
 */
export const syncHandlers: Partial<Record<DraftEntityType, SyncHandler>> = {
  ATTENDANCE: attendance,
  EXPENSE: expense,
};

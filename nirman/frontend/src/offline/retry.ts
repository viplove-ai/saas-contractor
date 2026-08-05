import axios from 'axios';
import type { ApiErrorBody } from '../shared/apiClient';

/**
 * What the queue learned from one failed send. Four outcomes because there are four
 * different things to do about them, and collapsing any two would make the sync screen
 * either nag about something it can fix itself or hide something only a person can.
 */
export type SendFailure =
  /** No response at all — the usual case at site. Stays queued, retried on its own. */
  | { kind: 'OFFLINE'; detail: string }
  /** The server is up but unwell (5xx, 429). Also retried, with a widening gap. */
  | { kind: 'TRANSIENT'; detail: string }
  /** Somebody else changed the record, or it collides with one already on file. A person decides. */
  | { kind: 'CONFLICT'; detail: string }
  /** The server refused on the merits (validation, permission, a locked month). Retrying cannot help. */
  | { kind: 'REJECTED'; detail: string };

/** The statuses a queued record can end a send attempt in. */
export const OUTCOME_STATUS = {
  OFFLINE: 'PENDING',
  TRANSIENT: 'PENDING',
  CONFLICT: 'CONFLICT',
  REJECTED: 'FAILED',
} as const;

/**
 * Reads a failed send. The classification is the whole of the queue's judgement, so it is a
 * pure function of the error and nothing else — no clock, no database, no network.
 *
 * <p>A 401 is deliberately TRANSIENT rather than REJECTED. The api client refreshes the
 * session and retries once on its own; a 401 that still reaches here means the refresh was
 * itself offline or racing a rotation, and burning the record as "refused" would throw away
 * a perfectly good muster because a token expired while the phone was in a pocket.</p>
 */
export function classifyFailure(error: unknown): SendFailure {
  if (!axios.isAxiosError(error)) {
    return { kind: 'REJECTED', detail: messageOf(error) };
  }
  const response = error.response;
  if (!response) {
    return { kind: 'OFFLINE', detail: 'No connection. This is still waiting to be sent.' };
  }
  const detail = (response.data as ApiErrorBody | undefined)?.detail ?? error.message;
  if (response.status === 409) {
    return { kind: 'CONFLICT', detail };
  }
  if (response.status === 401 || response.status === 429 || response.status >= 500) {
    return { kind: 'TRANSIENT', detail };
  }
  return { kind: 'REJECTED', detail };
}

/** Steps, in milliseconds: half a minute, two, ten, then half-hourly. */
const BACKOFF_STEPS_MS = [30_000, 120_000, 600_000, 1_800_000];

/**
 * When a record that has just failed its {@code attempts}-th try may be tried again.
 *
 * <p>Deterministic, with no jitter. A jittered backoff exists to stop a thousand clients
 * synchronising on a server; here the clients are a few dozen phones that come back into
 * signal at whatever moment the lorry clears the ridge, which is jitter enough — and a
 * predictable "in about two minutes" is something the sync screen can say honestly.</p>
 */
export function nextAttemptAt(attempts: number, now: number): number {
  const index = Math.min(Math.max(attempts, 1), BACKOFF_STEPS_MS.length) - 1;
  return now + BACKOFF_STEPS_MS[index]!;
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : 'Could not be sent.';
}

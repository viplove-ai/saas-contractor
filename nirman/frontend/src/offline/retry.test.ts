import { AxiosError, AxiosHeaders } from 'axios';
import { describe, expect, it } from 'vitest';
import { classifyFailure, nextAttemptAt, OUTCOME_STATUS } from './retry';

/**
 * The queue's entire judgement is in {@link classifyFailure}, so it is tested as the pure
 * function it is. Everything downstream — whether a record is retried, parked for a person,
 * or given up on — follows from the four answers below and nothing else.
 */
function axiosFailure(status: number, detail?: string): AxiosError {
  const error = new AxiosError('request failed');
  error.response = {
    status,
    statusText: '',
    headers: new AxiosHeaders(),
    config: { headers: new AxiosHeaders() },
    data: detail ? { detail } : undefined,
  };
  return error;
}

describe('classifyFailure', () => {
  it('reads a request that never got an answer as offline, not as a refusal', () => {
    const noResponse = new AxiosError('Network Error');
    expect(classifyFailure(noResponse).kind).toBe('OFFLINE');
    expect(OUTCOME_STATUS.OFFLINE).toBe('PENDING');
  });

  it('reads a 409 as a conflict for a person to settle', () => {
    const failure = classifyFailure(axiosFailure(409, 'This looks like a bill already on file.'));
    expect(failure.kind).toBe('CONFLICT');
    expect(failure.detail).toBe('This looks like a bill already on file.');
    expect(OUTCOME_STATUS.CONFLICT).toBe('CONFLICT');
  });

  it('reads a refusal on the merits as final, because retrying cannot change it', () => {
    // A locked month is the clearest case: it will still be locked in ten minutes.
    expect(classifyFailure(axiosFailure(422, 'June is closed for attendance.')).kind)
      .toBe('REJECTED');
    expect(classifyFailure(axiosFailure(403)).kind).toBe('REJECTED');
    expect(classifyFailure(axiosFailure(400)).kind).toBe('REJECTED');
    expect(OUTCOME_STATUS.REJECTED).toBe('FAILED');
  });

  it('keeps a server that is merely unwell in the queue', () => {
    expect(classifyFailure(axiosFailure(500)).kind).toBe('TRANSIENT');
    expect(classifyFailure(axiosFailure(503)).kind).toBe('TRANSIENT');
    expect(classifyFailure(axiosFailure(429)).kind).toBe('TRANSIENT');
  });

  /**
   * The one classification that looks wrong and is not. The api client refreshes and retries
   * once by itself, so a 401 reaching the queue means the refresh was itself offline or
   * racing a rotation. Burning the record as "refused" would throw away a morning's muster
   * because a token expired while the phone was in a pocket.
   */
  it('treats a 401 that survived the client refresh as worth another try', () => {
    expect(classifyFailure(axiosFailure(401)).kind).toBe('TRANSIENT');
  });

  it('does not mistake a programming error for a network one', () => {
    expect(classifyFailure(new TypeError('payload.entries is not iterable')).kind)
      .toBe('REJECTED');
  });
});

describe('nextAttemptAt', () => {
  it('widens the gap with each failure and then holds', () => {
    const now = 1_000_000;
    expect(nextAttemptAt(1, now)).toBe(now + 30_000);
    expect(nextAttemptAt(2, now)).toBe(now + 120_000);
    expect(nextAttemptAt(3, now)).toBe(now + 600_000);
    expect(nextAttemptAt(4, now)).toBe(now + 1_800_000);
    // Held rather than doubled forever: half-hourly is already the point at which the app
    // is checking for a signal roughly as often as a person would.
    expect(nextAttemptAt(9, now)).toBe(now + 1_800_000);
  });

  it('never schedules the past, whatever it is handed', () => {
    expect(nextAttemptAt(0, 500)).toBeGreaterThan(500);
    expect(nextAttemptAt(-3, 500)).toBeGreaterThan(500);
  });
});

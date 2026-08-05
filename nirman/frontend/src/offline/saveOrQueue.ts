import { enqueue, type QueueInput } from './queue';
import { classifyFailure } from './retry';

export type SaveOutcome<T> =
  /** It went to the server and the server answered. Nothing is queued. */
  | { outcome: 'SENT'; result: T }
  /** There was no connection. It is on the device and the queue owns it now. */
  | { outcome: 'QUEUED' };

/**
 * Sends a record if there is a connection, and keeps it on the device if there is not.
 *
 * <p>Not queue-first, and the difference is the point. When there is signal the record goes
 * straight out and the person who typed it sees what the server said — including the things
 * only the server knows, like an expense that looks like a bill already on file, or a worker
 * somebody else already marked. Those are questions for the person standing there with the
 * paperwork, and routing them through a queue would deliver them hours later to a screen
 * that has forgotten the context.</p>
 *
 * <p>The queue takes over at exactly one boundary: no answer at all. Everything else — a
 * refusal, a duplicate, a locked month — is the server having an opinion, and an opinion is
 * not something to retry.</p>
 */
export async function saveOrQueue<T>(input: {
  send: () => Promise<T>;
  queue: QueueInput;
}): Promise<SaveOutcome<T>> {
  // Skipped rather than attempted when the browser already knows there is nothing there:
  // the alternative is thirty seconds of a spinner to learn what navigator.onLine said at
  // once, and thirty seconds is long enough for a supervisor to decide the app is broken.
  if (typeof navigator !== 'undefined' && navigator.onLine === false) {
    await enqueue(input.queue);
    return { outcome: 'QUEUED' };
  }
  try {
    return { outcome: 'SENT', result: await input.send() };
  } catch (error) {
    if (classifyFailure(error).kind === 'OFFLINE') {
      await enqueue(input.queue);
      return { outcome: 'QUEUED' };
    }
    throw error;
  }
}

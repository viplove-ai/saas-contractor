import { describe, expect, it } from 'vitest';
import { previewHours } from './WorkerHoursDrawer';

/**
 * The screen's hours preview duplicates the server's AttendanceCalculator, so it is tested
 * against the same cases the Java suite uses. If the two ever disagree, a supervisor is
 * shown one number and paid against another — these are the cases that would catch it.
 *
 * Kausani figures: a seven-hour standard shift.
 */
const SHIFT = 7;

describe('previewHours', () => {
  it('splits nine hours into seven regular and two overtime', () => {
    expect(previewHours(9, SHIFT)).toEqual({ regular: 7, overtime: 2 });
  });

  it('books no overtime for exactly one standard shift', () => {
    expect(previewHours(SHIFT, SHIFT)).toEqual({ regular: 7, overtime: 0 });
  });

  it('books no overtime, and no negative regular, for a short day', () => {
    expect(previewHours(4, SHIFT)).toEqual({ regular: 4, overtime: 0 });
  });

  it('treats a zero-hour day as zero rather than as a shift', () => {
    expect(previewHours(0, SHIFT)).toEqual({ regular: 0, overtime: 0 });
  });

  it('starts overtime after the site shift, not after a hard-coded eight', () => {
    expect(previewHours(9, 8)).toEqual({ regular: 8, overtime: 1 });
    expect(previewHours(9, 9)).toEqual({ regular: 9, overtime: 0 });
  });

  it('keeps float noise off the muster roll', () => {
    expect(previewHours(9.5, SHIFT)).toEqual({ regular: 7, overtime: 2.5 });
    // 7.3 - 7 is 0.29999999999999982 in IEEE 754, and 0.3 on the roll.
    expect(previewHours(7.3, SHIFT)).toEqual({ regular: 7, overtime: 0.3 });
  });

  it('carries a full round-the-clock day through as overtime', () => {
    expect(previewHours(24, SHIFT)).toEqual({ regular: 7, overtime: 17 });
  });
});

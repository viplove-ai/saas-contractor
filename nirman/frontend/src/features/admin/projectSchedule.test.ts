import { describe, expect, it } from 'vitest';
import { completionLabel, daysBetween } from './projectSchedule';
import type { ProjectStatus } from './types';

/** Nothing here reads the clock: every case says which day it is standing on. */
const TODAY = new Date(2026, 7, 30); // 30 Aug 2026, local, as a browser would have it

function project(status: ProjectStatus, expectedCompletionDate?: string, actualCompletionDate?: string) {
  return { status, expectedCompletionDate, actualCompletionDate };
}

describe('daysBetween', () => {
  it('counts whole days forward and back', () => {
    expect(daysBetween(TODAY, '2026-08-31')).toBe(1);
    expect(daysBetween(TODAY, '2026-09-30')).toBe(31);
    expect(daysBetween(TODAY, '2026-08-20')).toBe(-10);
    expect(daysBetween(TODAY, '2026-08-30')).toBe(0);
  });

  it('does not lose a day to a daylight-saving boundary', () => {
    // A six-month span in either direction, counted on the calendar and not on a clock.
    expect(daysBetween(new Date(2026, 0, 1), '2026-07-01')).toBe(181);
    expect(daysBetween(new Date(2026, 6, 1), '2026-01-01')).toBe(-181);
  });

  it('says nothing about a value that is not a date', () => {
    expect(daysBetween(TODAY, 'soon')).toBeNull();
  });
});

describe('completionLabel', () => {
  it('prints the date with the countdown in brackets', () => {
    expect(completionLabel(project('ACTIVE', '2026-12-31'), TODAY)).toEqual({
      date: '31 Dec 2026',
      note: '123 days left',
      late: false,
    });
  });

  it('turns the countdown over once the date has passed, and flags it', () => {
    expect(completionLabel(project('ACTIVE', '2026-08-20'), TODAY)).toEqual({
      date: '20 Aug 2026',
      note: '10 days late',
      late: true,
    });
    expect(completionLabel(project('ACTIVE', '2026-08-29'), TODAY).note).toBe('1 day late');
  });

  it('gives the last two days their names', () => {
    expect(completionLabel(project('ACTIVE', '2026-08-30'), TODAY).note).toBe('today');
    expect(completionLabel(project('ACTIVE', '2026-08-31'), TODAY).note).toBe('tomorrow');
    // Due today is not yet late: the day it is due is a day it can still be finished on.
    expect(completionLabel(project('ACTIVE', '2026-08-30'), TODAY).late).toBe(false);
  });

  it('shows the day a finished project actually finished, and counts nothing', () => {
    expect(completionLabel(project('COMPLETED', '2026-12-31', '2026-06-15'), TODAY)).toEqual({
      date: '15 Jun 2026',
      note: '',
      late: false,
    });
  });

  it('counts nothing on a project the office has closed without recording the day', () => {
    // The target stopped being the useful figure; a countdown here could only mislead.
    expect(completionLabel(project('CLOSED', '2026-12-31'), TODAY)).toEqual({
      date: '31 Dec 2026',
      note: '',
      late: false,
    });
  });

  it('says nothing at all about a project with no date on it', () => {
    expect(completionLabel(project('PLANNED'), TODAY)).toEqual({
      date: '—',
      note: '',
      late: false,
    });
  });
});

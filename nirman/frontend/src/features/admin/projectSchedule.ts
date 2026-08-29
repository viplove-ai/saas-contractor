import type { ProjectStatus } from './types';

/**
 * When a contract is due, and how long that leaves.
 *
 * <p>The date alone is a fact nobody does arithmetic on at a glance — "31 Mar 2026" answers
 * *when* and not *how long*, and how long is the question somebody scanning a list of live
 * contracts is actually asking. So the countdown travels with it, in brackets, and turns over
 * to how far past the date a job has run once it is past it.</p>
 */
export interface CompletionLabel {
  /** The date, formatted, or an em dash when the project carries none. */
  date: string;
  /** What goes in brackets after it, empty when a countdown would say nothing useful. */
  note: string;
  /** Past its date and still running — the one state the column should catch an eye on. */
  late: boolean;
}

const DASH = '—';

/** Whole days from one calendar date to another, sign included, on neither one's clock. */
export function daysBetween(from: Date, isoDate: string): number | null {
  const parts = /^(\d{4})-(\d{2})-(\d{2})/.exec(isoDate);
  if (!parts) {
    return null;
  }
  /*
    Both ends are pinned to UTC midnight before subtracting. A local-time subtraction crosses a
    daylight-saving boundary somewhere in a six-month contract and comes back a day out, and a
    contract that says it has 90 days left on one machine and 89 on another is a figure nobody
    trusts twice.
  */
  const target = Date.UTC(Number(parts[1]), Number(parts[2]) - 1, Number(parts[3]));
  const today = Date.UTC(from.getFullYear(), from.getMonth(), from.getDate());
  return Math.round((target - today) / 86_400_000);
}

function formatDate(isoDate: string): string {
  const parts = /^(\d{4})-(\d{2})-(\d{2})/.exec(isoDate);
  if (!parts) {
    return DASH;
  }
  return new Date(
    Date.UTC(Number(parts[1]), Number(parts[2]) - 1, Number(parts[3])),
  ).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
}

/** How a countdown reads, once it is worth reading at all. */
function countdown(days: number): string {
  if (days === 0) return 'today';
  if (days === 1) return 'tomorrow';
  if (days > 1) return `${days} days left`;
  return days === -1 ? '1 day late' : `${-days} days late`;
}

/**
 * The completion column for one project.
 *
 * <p>Three cases, and two of them print no countdown. A project that has actually finished
 * shows the day it did, because the target it was working to stopped being the useful figure
 * the day it was met or missed. A project the office has marked completed or closed without
 * recording the day shows what it was due, and still no countdown: counting down to a target
 * on a job that is over is a number that can only mislead.</p>
 */
export function completionLabel(
  project: {
    expectedCompletionDate?: string | undefined;
    actualCompletionDate?: string | undefined;
    status: ProjectStatus;
  },
  today: Date,
): CompletionLabel {
  if (project.actualCompletionDate) {
    return { date: formatDate(project.actualCompletionDate), note: '', late: false };
  }
  if (!project.expectedCompletionDate) {
    return { date: DASH, note: '', late: false };
  }
  const date = formatDate(project.expectedCompletionDate);
  if (project.status === 'COMPLETED' || project.status === 'CLOSED') {
    return { date, note: '', late: false };
  }
  const days = daysBetween(today, project.expectedCompletionDate);
  if (days == null) {
    return { date, note: '', late: false };
  }
  return { date, note: countdown(days), late: days < 0 };
}

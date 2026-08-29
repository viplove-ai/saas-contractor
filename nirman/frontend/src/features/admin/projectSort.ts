import type { AdminProject, ProjectStatus } from './types';

/** Which header the list is ordered by. */
export type SortColumn = 'code' | 'quotedPercent' | 'quotedCost' | 'completion' | 'status';

export type SortDirection = 'asc' | 'desc';

export interface SortOrder {
  column: SortColumn;
  direction: SortDirection;
}

/** What the server already gives, so the first paint is not a re-sort of itself. */
export const DEFAULT_SORT: SortOrder = { column: 'code', direction: 'asc' };

/**
 * Life of a contract, not the alphabet.
 *
 * <p>Sorting the status column by its label would put Active between Closed and Completed,
 * which tells a reader nothing. Sorted this way the column groups the work that has not
 * started, the work running and the work finished, in that order — which is the only reason
 * anybody sorts by status.</p>
 */
const STATUS_RANK: Record<ProjectStatus, number> = {
  PLANNED: 0,
  ACTIVE: 1,
  ON_HOLD: 2,
  COMPLETED: 3,
  CLOSED: 4,
};

/**
 * The date the completion column is showing.
 *
 * <p>The cell prints the day a project actually finished where there is one and what it was
 * due otherwise, so the sort follows the same rule. Ordering by the target while the eye is
 * reading actuals is a column that appears not to be sorted at all.</p>
 */
function completionKey(project: AdminProject): string | undefined {
  return project.actualCompletionDate ?? project.expectedCompletionDate;
}

/**
 * Compares one field, with anything missing pushed to the bottom in **both** directions.
 *
 * <p>A project with no quoted value is not the cheapest one and a project with no date is not
 * the most urgent: the answer is unknown, and an unknown that floats to the top of a descending
 * sort is a row somebody has to scroll past every time. Missing rows therefore sink whichever
 * way the arrow points, and the sort is only over what is known.</p>
 */
function compare(
  a: number | string | undefined,
  b: number | string | undefined,
  direction: SortDirection,
): number {
  if (a == null && b == null) return 0;
  if (a == null) return 1;
  if (b == null) return -1;
  const order = a < b ? -1 : a > b ? 1 : 0;
  return direction === 'asc' ? order : -order;
}

function keyOf(project: AdminProject, column: SortColumn): number | string | undefined {
  switch (column) {
    case 'code':
      return project.code.toLowerCase();
    case 'quotedPercent':
      return project.quotedPercent;
    case 'quotedCost':
      return project.quotedCost;
    case 'completion':
      return completionKey(project);
    case 'status':
      return STATUS_RANK[project.status];
  }
}

/**
 * The projects in the order the headers ask for.
 *
 * <p>Returns a new array — the query cache holds the server's own order, and sorting it in
 * place would reorder every other reader of the same rows.</p>
 *
 * <p>Ties fall back to the code, which is unique, so two projects due the same day keep the
 * same order on every render rather than swapping places when anything else changes.</p>
 */
export function sortProjects(projects: AdminProject[], order: SortOrder): AdminProject[] {
  return [...projects].sort((a, b) => {
    const primary = compare(keyOf(a, order.column), keyOf(b, order.column), order.direction);
    return primary !== 0 ? primary : compare(a.code.toLowerCase(), b.code.toLowerCase(), 'asc');
  });
}

/** Mirrors the labour module's response shapes. Kept apart from the Zod form schemas. */

export type AttendanceStatus = 'PRESENT' | 'HALF_DAY' | 'ABSENT' | 'LEAVE';

export type WorkflowStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'VERIFIED'
  | 'REJECTED'
  | 'LOCKED'
  | 'CANCELLED';

export interface AttendanceRecord {
  id: string;
  workerId: string;
  attendanceDate: string;
  status: AttendanceStatus;
  checkInTime?: string;
  checkOutTime?: string;
  breakMinutes: number;
  /** What the supervisor typed. Absent on rows whose hours came from the clock instead. */
  enteredHours?: number;
  workedHours: number;
  regularHours: number;
  overtimeHours: number;
  overtimeReason?: string;
  workLocation?: string;
  remarks?: string;
  computedWageAmount?: number;
  computedOtAmount?: number;
  totalAmount?: number;
  workflowStatus: WorkflowStatus;
  rejectionReason?: string;
  version: number;
}

/**
 * A row as it comes back from `GET /attendance`, which carries the worker's name and the
 * site alongside the record — the verification queue spans workers, so it cannot lean on a
 * roster to resolve names the way the mark screen does.
 *
 * <p>The applied rates and computed amounts stay null until verification freezes them, so
 * the queue shows hours rather than money.</p>
 */
export interface AttendanceListRow extends AttendanceRecord {
  siteId: string;
  projectId?: string;
  workerName?: string;
  appliedNormalRate?: number;
  appliedOtRate?: number;
}

/** The backend's uniform pagination envelope. */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export type VerifyAction = 'VERIFY' | 'REJECT';

export interface VerifyResult {
  affected: number;
  action: VerifyAction;
}

export interface RosterEntry {
  workerId: string;
  workerCode: string;
  workerName: string;
  skillCategoryId?: string;
  labourSupplierId?: string;
  normalRate?: number;
  overtimeRate?: number;
  /** Absent from the payload entirely when the worker has not been marked yet. */
  attendance?: AttendanceRecord;
}

export interface Roster {
  siteId: string;
  date: string;
  standardShiftHours: number;
  overtimeReasonRequiredAboveHours: number;
  periodLocked: boolean;
  entries: RosterEntry[];
}

export interface BulkOutcome {
  id: string;
  workerId: string;
  outcome: 'CREATED' | 'UPDATED' | 'UNCHANGED' | 'REJECTED';
  reason?: string;
}

export interface BulkResult {
  accepted: number;
  unchanged: number;
  rejected: number;
  outcomes: BulkOutcome[];
}

/**
 * What the mark screen holds per worker before it is sent. The optional fields spell out
 * `| undefined` because tsconfig sets exactOptionalPropertyTypes: "absent" and "explicitly
 * undefined" are different things there, and these get built by spreading values that may
 * legitimately be undefined.
 */
export interface MarkDraft {
  status: AttendanceStatus;
  checkInTime?: string | undefined;
  checkOutTime?: string | undefined;
  breakMinutes: number;
  /**
   * Hours worked. Always set for a present day — the screen fills it with the site's
   * standard shift the moment Present is tapped — and undefined for an absent one, where
   * there are no hours to assert.
   */
  enteredHours?: number | undefined;
  overtimeReason?: string | undefined;
}

export interface Site {
  id: string;
  code: string;
  name: string;
  standardShiftHours: number;
}

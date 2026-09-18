/** Mirrors the labour module's worker master shapes. */

export type WageType = 'DAILY' | 'HOURLY' | 'MONTHLY';
export type EmploymentType = 'PERMANENT' | 'CONTRACT' | 'CASUAL';

/**
 * Whose names the register is showing. Everybody is the default, which is what the screen
 * has always shown — a filter that hid men on arrival would answer "where has he gone" with
 * silence. It is spelled `all` rather than the empty string a select usually uses for this,
 * because an empty value leaves the box looking unset, and a filter nobody can see the state
 * of is how a man goes missing from a list without anybody knowing why.
 */
export type WorkerStatusFilter = 'all' | 'active' | 'inactive';

export interface WageRate {
  id: string;
  workerId: string;
  normalRate: number;
  overtimeRate: number;
  effectiveFrom: string;
  effectiveTo?: string;
  remarks?: string;
}

export interface Worker {
  id: string;
  workerCode: string;
  fullName: string;
  mobile?: string;
  skillCategoryId?: string;
  employmentType: EmploymentType;
  labourSupplierId?: string;
  wageType: WageType;
  joiningDate?: string;
  exitDate?: string;
  aadhaarLast4?: string;
  /*
    Never shown on this screen, and here because of it: the update call replaces every
    particular it is given, so anything the edit form does not carry back would be wiped by
    the act of correcting a man's mobile number.
  */
  bankAccountNo?: string;
  bankIfsc?: string;
  bankName?: string;
  active: boolean;
  /** Null until the office sets one; a man with no rate cannot be paid. */
  currentWageRate?: WageRate;
  /** Where he is posted today, or absent if he is not posted anywhere. */
  currentSiteId?: string;
  version: number;
}

export interface Allocation {
  id: string;
  workerId: string;
  siteId: string;
  effectiveFrom: string;
  effectiveTo?: string;
}

/**
 * A site as somewhere to send a man. Comes from /sites/directory, which unlike /sites is
 * not narrowed to your postings — you cannot transfer to a site you are unable to name.
 */
export interface SiteDirectoryEntry {
  id: string;
  projectId: string;
  code: string;
  name: string;
  status: 'PLANNED' | 'ACTIVE' | 'SUSPENDED' | 'CLOSED';
}

export interface SkillCategory {
  id: string;
  code: string;
  name: string;
  skilled: boolean;
  active: boolean;
}

export interface LabourSupplier {
  id: string;
  code: string;
  name: string;
  active: boolean;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

// ---------------------------------------------------------------- advances and paydays

export type AdvancePaymentMode = 'CASH' | 'BANK' | 'UPI' | 'CHEQUE' | 'GOODS';
export type WagePaymentMode = 'CASH' | 'BANK' | 'UPI' | 'CHEQUE';

/** Recovery against wages — a payday's act, not the approval's. */
export type AdvanceStatus = 'OPEN' | 'PARTIALLY_RECOVERED' | 'RECOVERED' | 'WRITTEN_OFF';
export type AdvanceWorkflow = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'CANCELLED';

/**
 * Cash or goods handed to a worker against his wages. Mirrors AdvanceResponse. Two statuses,
 * because they answer two questions: whether the office has agreed it comes out of his
 * wages, and how much of it has since come out.
 */
export interface WorkerAdvance {
  id: string;
  advanceNumber: string;
  siteId: string;
  workerId: string;
  workerName?: string;
  advanceDate: string;
  amount: number;
  paymentMode: AdvancePaymentMode;
  purpose?: string;
  /** Whether it comes back out of his wages. Ration the contractor bears does not. */
  recoverable: boolean;
  recoveredAmount: number;
  balanceAmount: number;
  status: AdvanceStatus;
  workflowStatus: AdvanceWorkflow;
  approvedAt?: string;
  remarks?: string;
  version: number;
}

/** Wages handed over on a payday. Mirrors PaymentResponse. */
export interface WorkerPayment {
  id: string;
  paymentNumber: string;
  siteId: string;
  workerId: string;
  workerName?: string;
  paymentDate: string;
  amount: number;
  paymentMode: WagePaymentMode;
  referenceNumber?: string;
  /** What this payday closed of his open advances. */
  advancesRecovered: number;
  remarks?: string;
  version: number;
}

export type LedgerEntryType =
  | 'WAGE_EARNED'
  | 'OT_EARNED'
  | 'ADVANCE'
  | 'PAYMENT'
  | 'DEDUCTION'
  | 'ADJUSTMENT'
  | 'OPENING';

export interface LedgerEntry {
  id: string;
  entryDate: string;
  periodYearMonth: string;
  entryType: LedgerEntryType;
  /** +1 increases what he is owed, −1 reduces it; the amount is always positive. */
  direction: 1 | -1;
  amount: number;
  balanceAfter?: number;
  sourceType: 'ATTENDANCE' | 'WORKER_ADVANCE' | 'PAYMENT' | 'MANUAL';
  sourceId?: string;
  reason?: string;
}

/**
 * The settlement sheet: what the field sheet computes by hand, per man.
 * `earned − advance − paid − deduction = netPayable`.
 */
export interface Settlement {
  workerId: string;
  workerCode: string;
  workerName: string;
  earnedAmount: number;
  advanceAmount: number;
  /** The part of advanceAmount no payday has yet closed. */
  openAdvanceAmount: number;
  paidAmount: number;
  deductionAmount: number;
  netPayable: number;
  lastEntryAt?: string;
  entries: LedgerEntry[];
}

export const LEDGER_ENTRY_LABEL: Record<LedgerEntryType, string> = {
  WAGE_EARNED: 'Wage',
  OT_EARNED: 'Overtime',
  ADVANCE: 'Advance',
  PAYMENT: 'Paid',
  DEDUCTION: 'Deduction',
  ADJUSTMENT: 'Correction',
  OPENING: 'Opening balance',
};

export const PAYMENT_MODE_LABEL: Record<AdvancePaymentMode, string> = {
  CASH: 'Cash',
  BANK: 'Bank transfer',
  UPI: 'UPI',
  CHEQUE: 'Cheque',
  GOODS: 'Goods',
};

export const WAGE_TYPE_LABEL: Record<WageType, string> = {
  DAILY: 'Per day',
  HOURLY: 'Per hour',
  MONTHLY: 'Per month',
};

export const EMPLOYMENT_LABEL: Record<EmploymentType, string> = {
  PERMANENT: 'Permanent',
  CONTRACT: 'Contract',
  CASUAL: 'Casual',
};

/** Mirrors the expense and approval modules' response shapes. */

export type ExpenseWorkflow =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'L1_APPROVED'
  | 'APPROVED'
  | 'REJECTED'
  | 'RETURNED'
  | 'VOIDED';

export type PaymentStatus = 'UNPAID' | 'PARTIAL' | 'PAID';

/**
 * Whose cost an expense is (V36).
 *
 * <p>Every expense is typed at a site, which is not the same thing as being the site's cost:
 * the lorry of sand is, the accountant's salary is not, and both are typed by somebody
 * standing at a site. {@code SPLIT} is the diesel bill that ran the mixer and the office
 * car — one bill, two costs, and tearing it into two rows would stop it matching the paper.</p>
 */
export type CostAllocation = 'SITE' | 'COMPANY' | 'SPLIT';

/** What the register's current filter adds up to. Computed server-side, over every row. */
export interface AllocationSummary {
  totalBooked: number;
  siteCost: number;
  companyCost: number;
  paid: number;
  payable: number;
  expenseCount: number;
  awaitingApproval: number;
  /** Still carrying its head's proposal — approved without anybody reading the question. */
  unallocatedCount: number;
}

export type ApprovalAction = 'APPROVE' | 'REJECT' | 'RETURN';

export interface ExpenseCategory {
  id: string;
  code: string;
  name: string;
  parentId?: string;
  materialPurchase: boolean;
  labourPayment: boolean;
  requiresVendor: boolean;
  active: boolean;
  /** Named at a site while booking an expense, and nobody at the office has vetted it. */
  provisional?: boolean;
  /**
   * What an expense under this head proposes to the approver. Never a split — a split is an
   * amount, and an amount is a fact about one bill rather than about a category.
   */
  defaultAllocation?: Exclude<CostAllocation, 'SPLIT'>;
}

/**
 * The value the "what kind" picker carries for spending the taxonomy has no head for.
 *
 * <p>A sentinel rather than an empty string, for the reason {@code MATERIAL_NOT_LISTED} is
 * one: an unanswered picker and a picker answered with "none of these" are different states,
 * and only the second one asks what to call it.</p>
 */
export const CATEGORY_OTHER = '__other__';

export interface Vendor {
  id: string;
  code: string;
  name: string;
  vendorType: string;
}

export interface ExpenseAttachment {
  id: string;
  attachmentId: string;
  docType: string;
  fileName?: string;
  contentType?: string;
  sizeBytes: number;
}

export interface Expense {
  id: string;
  expenseNumber: string;
  siteId: string;
  projectId?: string;
  expenseDate: string;
  categoryId: string;
  categoryName?: string;
  vendorId?: string;
  vendorName?: string;
  boqItemId?: string;
  description: string;
  billNumber?: string;
  billDate?: string;
  amountBeforeTax: number;
  gstPercent: number;
  gstAmount: number;
  totalAmount: number;
  paymentMode?: string;
  paymentStatus: PaymentStatus;
  paidAmount: number;
  /** Approved cost less cash paid. Derived server-side; never typed. */
  payableAmount: number;
  noBillReason?: string;
  siteAdvanceId?: string;
  /** Whose cost it is. The label every screen shows and the register groups by. */
  costAllocation: CostAllocation;
  /** What the site's project carries: the total, nothing, or the split's site part. */
  siteCost: number;
  /** Overhead. Derived server-side from the total and the site's part. */
  companyCost: number;
  allocationNote?: string;
  /** Absent on a row nobody has decided yet: it is carrying its head's proposal. */
  allocatedAt?: string;
  /** How many times the author has re-opened it after approval. Zero for almost all. */
  revision: number;
  revisedAt?: string;
  revisionReason?: string;
  workflowStatus: ExpenseWorkflow;
  /**
   * Who typed it. Absent on a row from before auditing, or on a user since removed — the
   * screen says so rather than showing the id, which names nobody.
   */
  createdBy?: string;
  createdByName?: string;
  /** Which level is waiting, and on whom. Absent once the record is through. */
  pendingLevel?: number;
  pendingWithRole?: string;
  submittedAt?: string;
  approvedAt?: string;
  rejectionReason?: string;
  duplicateOfId?: string;
  remarks?: string;
  version: number;
  attachments: ExpenseAttachment[];
}

/** A row the new expense looks like a copy of, with why it was flagged. */
export interface DuplicateCandidate {
  id: string;
  expenseNumber: string;
  expenseDate: string;
  description: string;
  billNumber?: string;
  totalAmount: number;
  workflowStatus: ExpenseWorkflow;
  matchedOn: string;
}

/** The 409 body. Carries the candidates so the screen can show what it collided with. */
export interface DuplicateExpenseError {
  detail: string;
  candidates: DuplicateCandidate[];
}

export interface Approval {
  id: string;
  entityType: string;
  entityId: string;
  siteId?: string;
  level: number;
  assignedRole: string;
  status: string;
  remarks?: string;
  createdAt: string;
}

export interface Payment {
  id: string;
  paymentNumber: string;
  expenseId?: string;
  expenseNumber?: string;
  vendorId?: string;
  vendorName?: string;
  paymentDate: string;
  amount: number;
  paymentMode: string;
  referenceNumber?: string;
  remarks?: string;
  /**
   * What proves the cash went out — the UPI screenshot, the signed receipt, the bank slip.
   *
   * <p>Empty on a payment recorded with nothing, which is a real case: a cash payment across
   * a table has nothing to photograph. A reference number was all this record could carry
   * until V45, so a supplier disputing a payment nine months later was arguing with a string
   * of twelve digits.</p>
   */
  attachments: PaymentAttachment[];
}

export interface PaymentAttachment {
  id: string;
  attachmentId: string;
  /** RECEIPT | SCREENSHOT | BANK_SLIP | OTHER. What the picture is of, not what it is. */
  docType: string;
  fileName?: string;
  contentType?: string;
}

export interface VendorBalance {
  vendorId: string;
  vendorCode?: string;
  vendorName?: string;
  approvedAmount: number;
  paidAmount: number;
  payableAmount: number;
  openBills: number;
  oldestUnpaidDate?: string;
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

export interface Site {
  id: string;
  code: string;
  name: string;
}

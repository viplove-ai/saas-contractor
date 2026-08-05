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
}

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
  workflowStatus: ExpenseWorkflow;
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

/** The supplier register and what one supplier's account looks like. */

export type VendorType = 'MATERIAL' | 'SUBCONTRACTOR' | 'SERVICE' | 'TRANSPORT' | 'OTHER';

export const VENDOR_TYPE_LABEL: Record<VendorType, string> = {
  MATERIAL: 'Material dealer',
  SUBCONTRACTOR: 'Subcontractor',
  SERVICE: 'Service provider',
  TRANSPORT: 'Transporter',
  OTHER: 'Other',
};

/** A dealer as the register holds him. Everything an employer has to be able to reach. */
export interface Vendor {
  id: string;
  code: string;
  name: string;
  vendorType: VendorType;
  contactPerson?: string;
  mobile?: string;
  email?: string;
  address?: string;
  gstin?: string;
  pan?: string;
  bankAccountNo?: string;
  bankIfsc?: string;
  /** Days from his bill to when it is due. Zero means cash on delivery. */
  creditDays: number;
  /** What he was owed the day the organisation started using this system. Typed once. */
  openingBalance?: number;
  active: boolean;
  version: number;
}

/**
 * Where his account stands.
 *
 * <p>Five figures rather than one balance, and the server means it — see
 * VendorAccountResponse. The advance is the one both sides forget, and a single net number
 * is the one figure that reconciles against nothing the supplier is holding.</p>
 */
export interface VendorAccount {
  vendorId: string;
  vendorCode: string;
  vendorName: string;
  openingBalance: number;
  billedAmount: number;
  paidAgainstBills: number;
  advancePaid: number;
  outstanding: number;
  /** Outstanding less the unadjusted advance. Negative means we are ahead of him. */
  netPosition: number;
  openBills: number;
  oldestUnpaidDate?: string;
  purchasedValue: number;
  deliveryCount: number;
}

/** One line he delivered: what, how much, at what rate, on which bill. */
export interface VendorPurchase {
  receiptId: string;
  grnNumber: string;
  receiptDate: string;
  invoiceNumber?: string;
  siteId?: string;
  materialId: string;
  materialCode?: string;
  materialName?: string;
  unitCode?: string;
  quantity: number;
  /** Absent while the office has not priced the line. The delivery still happened. */
  rate?: number;
  amount: number;
  /** False until the delivery was verified into the store. */
  received: boolean;
}

export interface VendorPayment {
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

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

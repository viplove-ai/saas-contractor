/** Mirrors the inventory module's response shapes. Kept apart from the Zod form schemas. */

export type DocumentWorkflow =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'VERIFIED'
  | 'APPROVED'
  | 'REJECTED'
  | 'CANCELLED';

export type TxnType =
  | 'OPENING_STOCK'
  | 'RECEIPT'
  | 'ISSUE'
  | 'TRANSFER_OUT'
  | 'TRANSFER_IN'
  | 'RETURN'
  | 'WASTAGE'
  | 'DAMAGE'
  | 'ADJUSTMENT';

export interface Unit {
  id: string;
  code: string;
  name: string;
  decimalPlaces: number;
}

export interface Material {
  id: string;
  code: string;
  name: string;
  baseUnitId: string;
  minStockLevel: number;
  gstPercent: number;
  active: boolean;
  /** Named at the gate rather than set up by the office, and nobody has vetted it yet. */
  provisional: boolean;
}

/**
 * The value the material picker carries for "not in the list".
 *
 * <p>A sentinel rather than an empty string, because an unanswered picker and a picker
 * answered with "none of these" are different states and only the second one asks for a
 * name.</p>
 */
export const MATERIAL_NOT_LISTED = '__not-listed__';

export interface Store {
  id: string;
  siteId: string;
  code: string;
  name: string;
  location?: string;
  defaultStore: boolean;
  active: boolean;
}

export interface BoqItem {
  id: string;
  projectId: string;
  siteId?: string;
  itemNumber: string;
  description: string;
  category?: string;
  synthetic: boolean;
}

/**
 * One line of the stock position. {@link low} is decided by the server against each
 * material's own reorder level, so the screen never has to know what the threshold is.
 */
export interface StockRow {
  storeId: string;
  storeCode?: string;
  storeName?: string;
  siteId?: string;
  materialId: string;
  materialCode?: string;
  materialName?: string;
  baseUnitCode?: string;
  quantityBase: number;
  movingAvgRate: number;
  stockValue: number;
  /** Dispatched from another store and not yet arrived. Issuable by nobody. */
  inTransitQtyBase: number;
  minStockLevel: number;
  low: boolean;
  lastTxnAt?: string;
}

export interface StockPosition {
  rows: StockRow[];
  totalValue: number;
}

export interface LedgerRow {
  id: string;
  txnDate: string;
  storeId: string;
  storeName?: string;
  materialId: string;
  materialCode?: string;
  materialName?: string;
  baseUnitCode?: string;
  txnType: TxnType;
  direction: number;
  quantityBase: number;
  rateBase: number;
  value: number;
  balanceAfter?: number;
  avgRateAfter?: number;
  sourceType: string;
  boqItemId?: string;
  reason?: string;
  createdAt: string;
}

export interface ReceiptLineResponse {
  id: string;
  materialId: string;
  materialCode?: string;
  materialName?: string;
  unitCode?: string;
  quantity: number;
  quantityBase: number;
  baseUnitCode?: string;
  rate: number;
  rateBase: number;
  gstPercent: number;
  gstAmount: number;
  amount: number;
}

export interface Receipt {
  id: string;
  grnNumber: string;
  siteId: string;
  storeId: string;
  storeName?: string;
  vendorId?: string;
  receiptDate: string;
  invoiceNumber?: string;
  challanNumber?: string;
  vehicleNumber?: string;
  subTotal: number;
  gstAmount: number;
  totalAmount: number;
  workflowStatus: DocumentWorkflow;
  rejectionReason?: string;
  version: number;
  lines: ReceiptLineResponse[];
}

export interface IssueLineResponse {
  id: string;
  materialId: string;
  materialCode?: string;
  materialName?: string;
  unitCode?: string;
  quantity: number;
  quantityBase: number;
  baseUnitCode?: string;
  /** The store's moving average, frozen when the stock left. Absent until approval. */
  issuedRate?: number;
  value?: number;
  boqItemId?: string;
}

export interface Issue {
  id: string;
  issueNumber: string;
  siteId: string;
  storeId: string;
  storeName?: string;
  issueDate: string;
  issuedToName?: string;
  boqItemId?: string;
  workLocation?: string;
  purpose?: string;
  workflowStatus: DocumentWorkflow;
  rejectionReason?: string;
  totalValue: number;
  version: number;
  lines: IssueLineResponse[];
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

export interface Site {
  id: string;
  code: string;
  name: string;
}

/** One line being composed on the receive or issue screen, before it is sent. */
export interface LineDraft {
  key: string;
  /** A material id, or {@link MATERIAL_NOT_LISTED} while {@link LineDraft.newName} is being typed. */
  materialId: string;
  unitId: string;
  quantity: string;
  /** Receipts only. Issues are valued by the server at the store's average. */
  rate?: string;
  boqItemId?: string | undefined;
  /** What the storekeeper called it, when the catalogue has no name for it. */
  newName?: string | undefined;
}

/**
 * The billing screens' shapes, mirroring `BillingDtos` on the server.
 *
 * <p>The one idea worth carrying in your head while reading these: a measurement sheet is a
 * piece of paper. It has one contract item on it, a dozen ruled rows, and a total the engineer
 * worked out by hand at the foot. `writtenTotal` is that hand-worked figure and `computedTotal`
 * is what the rows actually come to — and a sheet cannot be signed while they disagree.</p>
 */

export type SheetType = 'MEASUREMENT' | 'BAR_BENDING';
export type SheetStatus = 'DRAFT' | 'SIGNED';
export type BillStatus = 'DRAFT' | 'SUBMITTED' | 'CHECKED' | 'PASSED';

/**
 * Which dimension boxes the entry grid shows, decided by the item's unit.
 *
 * <p>Six shapes cover every measurement line in a real bill, and they are all one formula with
 * the unused dimensions left blank. <b>Blank is not zero</b>: a linear item has no breadth, and
 * sending a zero would claim the work had none rather than that the question does not arise.</p>
 */
export type MeasurementShape = 'VOLUME' | 'AREA' | 'LINEAR' | 'COUNT';

export interface MeasurementLineInput {
  location?: string | null;
  nos?: number | null;
  mult?: number | null;
  length?: number | null;
  breadth?: number | null;
  height?: number | null;
  deduction?: boolean;
  barDia?: number | null;
}

export interface MeasurementLineResponse extends MeasurementLineInput {
  id: string;
  lineNo: number;
  contents: string;
}

export interface Sheet {
  id: string;
  projectId: string;
  siteId: string;
  boqItemId: string;
  itemNumber: string;
  itemDescription: string;
  sheetSerial: string | null;
  sheetType: SheetType;
  measuredOn: string;
  measuredBy: string | null;
  locationNote: string | null;
  writtenTotal: string | null;
  computedTotal: string;
  /** The figure that reaches the bill: the rows, or the rows times a tested kg/m. */
  claimedQuantity: string;
  totalsAgree: boolean;
  unitWeight: string | null;
  status: SheetStatus;
  signedAt: string | null;
  signedBy: string | null;
  raBillId: string | null;
  attachmentId: string | null;
  remarks: string | null;
  version: number;
  lines: MeasurementLineResponse[];
}

export interface UnbilledItem {
  boqItemId: string;
  itemNumber: string;
  description: string;
  contractQuantity: string;
  quantityPaidToDate: string;
  quantityUnbilled: string;
  rate: string;
  valueUnbilled: string;
  sheetCount: number;
}

export interface UnbilledSummary {
  projectId: string;
  cutoffDate: string | null;
  items: UnbilledItem[];
  totalValue: string;
  sheetCount: number;
  /** Item numbers measured past their contract quantity. Named, never silently refused. */
  overClaimed: string[];
}

export interface BillItem {
  boqItemId: string;
  itemNumber: string;
  description: string;
  contractQuantity: string;
  qtySincePrevious: string;
  qtyToDate: string;
  rate: string;
  amountToDate: string;
  amountPrevious: string;
  amountSince: string;
  sortOrder: number;
}

export interface Bill {
  id: string;
  projectId: string;
  serialNo: number;
  title: string;
  cutoffDate: string;
  previousBillId: string | null;
  status: BillStatus;
  /** True once passed, when the items are the snapshot rather than anything computed now. */
  frozen: boolean;
  frozenAt: string | null;
  frozenBy: string | null;
  grossWorkDone: string | null;
  valueSincePrevious: string | null;
  revision: number;
  remarks: string | null;
  version: number;
  items: BillItem[];
}

export interface BillSummary {
  id: string;
  projectId: string;
  serialNo: number;
  title: string;
  cutoffDate: string;
  status: BillStatus;
  grossWorkDone: string | null;
  revision: number;
}

export interface Agreement {
  id: string;
  projectId: string;
  agreementNo: string | null;
  division: string | null;
  subDivision: string | null;
  dsrScheduleId: string | null;
  dsrCoefficient: string;
  costIndexPct: string;
  tenderPct: string;
  deviationLimitPct: string;
  contractorName: string | null;
  measuredByName: string | null;
  measuredByDesignation: string | null;
  preparedByName: string | null;
  preparedByDesignation: string | null;
  checkedByName: string | null;
  checkedByDesignation: string | null;
  executiveEngineer: string | null;
  cmbNo: string | null;
  estimatedCost: string | null;
  tenderedCost: string | null;
  dateOfStart: string | null;
  stipulatedCompletion: string | null;
  /** What the rate chain does to a round 1000, so the form can show its arithmetic. */
  sampleDerivation: string | null;
  version: number;
}

export interface BoqItem {
  id: string;
  itemNumber: string;
  description: string;
  unitId: string;
  contractQuantity: string;
  contractRate: string;
}

/** What every paginated list endpoint returns. See `PageResponse` on the server. */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface Project {
  id: string;
  code: string;
  name: string;
  mode?: 'FULL' | 'BILLING_ONLY';
}

export interface Unit {
  id: string;
  code: string;
  name: string;
}

/**
 * Which boxes to show for a unit.
 *
 * <p>Read off the unit code because that is the fact the contract states. An item measured
 * against convention is rare enough to be handled by the engineer leaving a box blank rather
 * than by a setting nobody would find.</p>
 */
export function shapeForUnit(unitCode: string | undefined): MeasurementShape {
  switch ((unitCode ?? '').toUpperCase()) {
    case 'CUM':
      return 'VOLUME';
    case 'SQM':
      return 'AREA';
    case 'MTR':
    case 'RMT':
    case 'KG':
    case 'QTL':
    case 'MT':
      return 'LINEAR';
    default:
      return 'COUNT';
  }
}

export function shapeColumns(shape: MeasurementShape): Array<'length' | 'breadth' | 'height'> {
  switch (shape) {
    case 'VOLUME':
      return ['length', 'breadth', 'height'];
    case 'AREA':
      return ['length', 'breadth'];
    case 'LINEAR':
      return ['length'];
    default:
      return [];
  }
}

/**
 * Two decimal places, half away from zero — what `BigDecimal.setScale(2, HALF_UP)` does on
 * the server.
 *
 * <p>`Math.round(v * 100) / 100` is not that, and the difference is not academic. 1.005 is
 * held in binary as 1.00499999…, so the naive form rounds it <i>down</i> to 1.00 while the
 * server rounds it up to 1.01. The engineer's screen would then add up to one figure and the
 * saved sheet to another — and since the two totals are exactly what the signature checks
 * against each other, he would be told his sheet balanced and then refused at the signature,
 * with nothing on screen to explain it.</p>
 *
 * <p>`toPrecision(12)` snaps the value back to the decimal a human would have written before
 * the rounding is applied, which is enough for every magnitude a measurement book holds.</p>
 */
function round2(value: number): number {
  if (!Number.isFinite(value)) return 0;
  const sign = value < 0 ? -1 : 1;
  const magnitude = Math.abs(value);
  return sign * Number(`${Math.round(Number(`${magnitude.toPrecision(12)}e+2`))}e-2`);
}

/** What one row comes to, mirroring `MeasurementLine.computeContents` exactly. */
export function lineContents(line: MeasurementLineInput): number {
  const factor = (value: number | null | undefined, fallback: number) =>
    value === null || value === undefined || Number.isNaN(value) ? fallback : value;
  let value = factor(line.nos, 1) * factor(line.mult, 1);
  for (const dimension of [line.length, line.breadth, line.height]) {
    if (dimension !== null && dimension !== undefined && !Number.isNaN(dimension)) {
      value *= dimension;
    }
  }
  const rounded = round2(value);
  return line.deduction ? -rounded : rounded;
}

export function sheetTotal(lines: MeasurementLineInput[]): number {
  return round2(lines.reduce((sum, line) => sum + lineContents(line), 0));
}

export const isEditable = (sheet: Sheet): boolean =>
  sheet.status === 'DRAFT' && sheet.raBillId === null;

// ------------------------------------------------------------------ the vault

/**
 * What a document is, which decides what it may be attached to. DSR and DAR price work;
 * COST_INDEX moves a station's percentage; the rest are read by people and price nothing.
 */
export type DocumentKind = 'DSR' | 'DAR' | 'COST_INDEX' | 'SPECIFICATION' | 'CIRCULAR' | 'OTHER';
export type DocumentStatus = 'CURRENT' | 'SUPERSEDED' | 'WITHDRAWN';
export type DocumentRole = 'SCHEDULE_OF_RATES' | 'COST_INDEX' | 'SPECIFICATION' | 'OTHER';

export const DOCUMENT_KIND_LABEL: Record<DocumentKind, string> = {
  DSR: 'Schedule of Rates',
  DAR: 'Analysis of Rates',
  COST_INDEX: 'Cost index',
  SPECIFICATION: 'Specification',
  CIRCULAR: 'Circular',
  OTHER: 'Other',
};

export interface ReferenceDocument {
  id: string;
  kind: DocumentKind;
  code: string;
  title: string;
  editionYear: number | null;
  station: string | null;
  indexPercent: string | null;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  attachmentId: string | null;
  supersedesId: string | null;
  status: DocumentStatus;
  notes: string | null;
  version: number;
}

export interface TenderDocument {
  id: string;
  documentId: string;
  role: DocumentRole;
  workPart: string | null;
  code: string | null;
  title: string | null;
  editionYear: number | null;
  status: DocumentStatus | null;
  attachmentId: string | null;
}

/** What the NIT said, offered so the office confirms a figure rather than transcribing one. */
export interface AgreementSuggestion {
  civilDsrYear: number | null;
  civilCostIndexPercent: string | null;
  electricalDsrYear: number | null;
  electricalCostIndexPercent: string | null;
  suggestedDocuments: ReferenceDocument[];
}

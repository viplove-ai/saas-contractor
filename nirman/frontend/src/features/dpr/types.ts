/** Mirrors the DPR module's response shapes. */

export type DprWorkflow = 'DRAFT' | 'SUBMITTED' | 'VERIFIED' | 'REJECTED';

/** Exactly the server's enum. A value it does not know is refused on save, not stored. */
export type Weather = 'CLEAR' | 'CLOUDY' | 'RAIN' | 'HEAVY_RAIN' | 'EXTREME_HEAT';

/**
 * Why the site did not work. A picked cause rather than a sentence, because the value of
 * recording a lost day is that it can be added up — "nine days to rain in July" is a claim
 * against the department and "some days, see the notes" is not.
 */
export type NonOperationalCause =
  | 'WEATHER'
  | 'HOLIDAY'
  | 'STRIKE'
  | 'NO_LABOUR'
  | 'MATERIAL_SHORTAGE'
  | 'FUNDS'
  | 'DEPARTMENT_INSTRUCTION'
  | 'SITE_NOT_READY'
  | 'OTHER';

/** The same words the printed report uses, so the screen and the paper agree. */
export const NON_OPERATIONAL_CAUSES: { value: NonOperationalCause; label: string }[] = [
  { value: 'WEATHER', label: 'Weather' },
  { value: 'HOLIDAY', label: 'Holiday' },
  { value: 'STRIKE', label: 'Strike or bandh' },
  { value: 'NO_LABOUR', label: 'No labour available' },
  { value: 'MATERIAL_SHORTAGE', label: 'Material not available' },
  { value: 'FUNDS', label: 'Funds not released' },
  { value: 'DEPARTMENT_INSTRUCTION', label: 'Stopped by the department' },
  { value: 'SITE_NOT_READY', label: 'Site not ready for work' },
  { value: 'OTHER', label: 'Other' },
];

export function causeLabel(cause: NonOperationalCause | undefined): string {
  return NON_OPERATIONAL_CAUSES.find((entry) => entry.value === cause)?.label ?? 'Not stated';
}

/** "Other" says nothing on its own, so it is the one cause the server also wants a note for. */
export function causeNeedsNote(cause: NonOperationalCause | ''): boolean {
  return cause === 'OTHER';
}

/** Draft and returned reports are still the preparer's to change — the server says the same. */
export function isEditable(status: DprWorkflow): boolean {
  return status === 'DRAFT' || status === 'REJECTED';
}

/**
 * Whether this user may still write to the report, and which half of it.
 *
 * <p>The report has two authors. A draft is the supervisor's: he records what the day was and
 * hands it over. A submitted report is the engineer's, and he has to be able to write to it —
 * work done and the day's observations are his, and a handover he could not finish would leave
 * every report permanently half-written. The server enforces exactly this; here it decides
 * which buttons exist.</p>
 */
export function isWritable(status: DprWorkflow, canVerify: boolean): boolean {
  return isEditable(status) || (status === 'SUBMITTED' && canVerify);
}

export interface Site {
  id: string;
  code: string;
  name: string;
}

export interface BoqItem {
  id: string;
  projectId: string;
  siteId?: string;
  itemNumber: string;
  description: string;
  category?: string;
  synthetic: boolean;
  unitId?: string;
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

// ---------------------------------------------------------------- prefill

export interface LabourLine {
  skillCategoryId?: string;
  skillCategoryName?: string;
  labourSupplierId?: string;
  labourSupplierName?: string;
  headCount: number;
  regularHours: number;
  overtimeHours: number;
  /**
   * A head count from a contractor-run site rather than a line of the muster roll. Its hours
   * are zero because nobody clocked them, not because the men stood idle — so it is never
   * added into the muster's head count.
   */
  outsourced?: boolean;
}

export interface LabourPrefill {
  presentCount: number;
  absentCount: number;
  regularHours: number;
  overtimeHours: number;
  cost: number;
  unverifiedCost: number;
  recordCount: number;
  unverifiedCount: number;
  lines: LabourLine[];
}

export interface MaterialLine {
  materialId: string;
  materialCode: string;
  materialName: string;
  baseUnitCode?: string;
  receivedQty: number;
  receivedValue: number;
  consumedQty: number;
  consumedValue: number;
}

export interface MaterialPrefill {
  receivedValue: number;
  consumedValue: number;
  receiptCount: number;
  issueCount: number;
  lines: MaterialLine[];
}

/**
 * The four figures, never one. Only {@link ExpensePrefill.costIncurred} may be added to
 * labour and material consumption — the other two are costed elsewhere.
 */
export interface ExpensePrefill {
  totalBooked: number;
  costIncurred: number;
  materialPurchases: number;
  labourDisbursements: number;
  expenseCount: number;
  unapprovedCount: number;
}

export interface SuggestedWorkItem {
  boqItemId: string;
  itemNumber: string;
  description: string;
  unitId?: string;
  /** Why the line is being suggested — "material issued", "labour charged", or both. */
  because: string;
}

export interface DprPrefill {
  siteId: string;
  siteName: string;
  reportDate: string;
  reportExists: boolean;
  existingDprId?: string;
  labour: LabourPrefill;
  outsourcedLabour: OutsourcedPrefill;
  material: MaterialPrefill;
  expense: ExpensePrefill;
  labourCostProvisional: boolean;
  suggestedWorkItems: SuggestedWorkItem[];
  caveat: string;
}

/**
 * External labour, counted rather than marked. Never added into the muster's head count:
 * these men have no wage behind them, and the hours they do carry are unpriced.
 */
export interface OutsourcedPrefill {
  /** False for a site that keeps its own muster roll — the section is not drawn at all. */
  enabled: boolean;
  headCount: number;
  /** Man-hours over the trades that recorded hours. Zero when none did. */
  manHours: number;
  lines: OutsourcedLine[];
}

export interface OutsourcedLine {
  skillCategoryId: string;
  skillCategoryName?: string;
  labourSupplierId?: string;
  labourSupplierName?: string;
  headCount: number;
  /** Hours each man worked. Absent when nobody recorded them, which is not zero. */
  hours?: number;
  manHours?: number;
}

/** A material as the report's quick-entry rows need it: what to call it and what it counts in. */
export interface DprMaterial {
  id: string;
  code: string;
  name: string;
  baseUnitId: string;
  baseUnitCode?: string;
}

/**
 * The value the material picker carries for "not in the list" — a sentinel rather than an
 * empty string, because a picker nobody has answered and one answered with "none of these"
 * are different states, and only the second one asks for a name.
 */
export const MATERIAL_NOT_LISTED = '__not-listed__';

export interface LabourCountLine {
  skillCategoryId: string;
  /**
   * Which supplier sent the men. Off the one supplier register (V23), so naming him here is
   * naming somebody who already has an account, an address and a history — which is what
   * makes the question worth asking at all.
   */
  labourSupplierId?: string | undefined;
  headCount: number;
  /**
   * Hours <b>each</b> man of the trade worked, not the gang's total. Undefined when nobody
   * recorded them, which is not the same as zero — and no money follows from it either way.
   */
  hours?: number | undefined;
  remarks?: string | undefined;
}

/**
 * Whether a supplier, or the man he sends to run his gang, stood on the site that day.
 *
 * <p>Per supplier and not per trade: one supplier who sent masons, helpers and bar benders
 * has three count lines, and the same question answered three times can contradict itself.</p>
 */
export interface SupplierDayLine {
  labourSupplierId: string;
  supplierPresent: boolean;
  /** Who came, when it was not the supplier himself. */
  representativeName?: string | undefined;
  remarks?: string | undefined;
}

export interface SupplierDay extends SupplierDayLine {
  labourSupplierName?: string;
  /** His men on site that day, added across the trades he supplied. */
  headCount: number;
}

export interface DayCounts {
  siteId: string;
  date: string;
  enabled: boolean;
  periodLocked: boolean;
  totalHeadCount: number;
  /** Man-hours over the trades that recorded hours; zero when none did. */
  totalManHours: number;
  lines: (LabourCountLine & {
    id: string;
    skillCategoryName?: string;
    labourSupplierName?: string;
    manHours?: number;
  })[];
  /** One entry per supplier the day names, whether or not anybody answered for him. */
  suppliers: SupplierDay[];
}

// ---------------------------------------------------------------- the report

export interface WorkItemResponse {
  id: string;
  boqItemId?: string;
  itemNumber?: string;
  activity: string;
  workLocation?: string;
  quantity?: number;
  unitId?: string;
  remarks?: string;
  sortOrder: number;
  /** Whether this row claims measured work against the contract. */
  measured: boolean;
}

export interface MachineryResponse {
  id: string;
  machineryName: string;
  count: number;
  hoursUsed: number;
  idleHours: number;
  remarks?: string;
}

export interface PhotoResponse {
  id: string;
  attachmentId: string;
  caption?: string;
  takenAt?: string;
  fileName?: string;
  contentType?: string;
  sizeBytes: number;
  sortOrder: number;
}

export interface Dpr {
  id: string;
  dprNumber: string;
  siteId: string;
  siteName?: string;
  projectId: string;
  reportDate: string;
  /** False when the site did not work. The report is then about why, and nothing else. */
  siteOperational: boolean;
  nonOperationalCause?: NonOperationalCause;
  nonOperationalNote?: string;
  weather?: Weather;
  temperatureC?: number;
  workingHoursLost?: number;
  labourPresentCount?: number;
  /** Contractor's men counted at the gate. Frozen beside the present count, never inside it. */
  outsourcedHeadCount?: number;
  labourRegularHours?: number;
  labourOvertimeHours?: number;
  labourCost?: number;
  materialReceivedValue?: number;
  materialConsumedValue?: number;
  expenseAmount?: number;
  /** Labour + material consumed + cost incurred. The one total that adds up. */
  dayCost?: number;
  /** True once submitted: from then on the figures are the document's own. */
  snapshotFrozen: boolean;
  workSummary?: string;
  delays?: string;
  safetyObservations?: string;
  qualityObservations?: string;
  instructionsReceived?: string;
  managementAttention?: string;
  nextDayPlan?: string;
  workflowStatus: DprWorkflow;
  preparedBy?: string;
  preparedByName?: string;
  submittedAt?: string;
  verifiedBy?: string;
  verifiedByName?: string;
  verifiedAt?: string;
  rejectionReason?: string;
  progressPosted?: number;
  version: number;
  workItems: WorkItemResponse[];
  labour: LabourLine[];
  machinery: MachineryResponse[];
  photos: PhotoResponse[];
}

// ---------------------------------------------------------------- write shapes

export interface WorkItemInput {
  boqItemId?: string | undefined;
  activity: string;
  workLocation?: string | undefined;
  /** Null or zero describes; a figure claims. Only a claim reaches the measurement book. */
  quantity?: number | undefined;
  unitId?: string | undefined;
  remarks?: string | undefined;
}

export interface MachineryInput {
  machineryName: string;
  count: number;
  hoursUsed: number;
  idleHours: number;
  remarks?: string | undefined;
}

/**
 * The supervisor's half: what the day <i>was</i>.
 *
 * <p>Split from the engineer's half below rather than kept as one flat shape, because that is
 * the line the server enforces — a caller holding only `dpr:draft` may send this and not the
 * other, and once the report has been handed over neither its author nor anybody else may send
 * this again. A type that hid the split would leave every call site guessing at it.</p>
 */
export interface DprConditions {
  siteOperational?: boolean | undefined;
  nonOperationalCause?: NonOperationalCause | undefined;
  nonOperationalNote?: string | undefined;
  weather?: Weather | undefined;
  temperatureC?: number | undefined;
  workingHoursLost?: number | undefined;
  /**
   * Plant that stood on the site, on the supervisor's side of the line because the line runs
   * where claiming does: a quantity is a claim against the contract and a mixer's running
   * hours are not.
   */
  machinery?: MachineryInput[];
}

/**
 * The engineer's half: what was built, and the two things the office cannot learn elsewhere.
 *
 * <p>The summary, the delays, the safety and quality boxes and the note to management are not
 * here any more. Four of them were prose the site was asked for every evening and answered with
 * "nil", and the fifth restated the work rows above it — a form that asks more than it needs
 * teaches the man filling it that nothing on it is worth reading. The fields survive on
 * {@link Dpr} because reports were signed with them in, and what was signed does not change
 * because the form did.</p>
 */
export interface DprObservations {
  instructionsReceived?: string | undefined;
  nextDayPlan?: string | undefined;
  workItems?: WorkItemInput[];
}

export interface CreateDprInput extends DprConditions, DprObservations {
  id: string;
  siteId: string;
  reportDate: string;
}

export interface UpdateDprInput extends DprConditions, DprObservations {
  version: number;
}

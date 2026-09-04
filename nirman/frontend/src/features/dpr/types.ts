/** Mirrors the DPR module's response shapes. */

export type DprWorkflow = 'DRAFT' | 'SUBMITTED' | 'VERIFIED' | 'REJECTED' | 'APPROVED';

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

/**
 * The same words the printed report uses, so the screen and the paper agree.
 *
 * <p>Every cause the server knows, including the ones the picker no longer offers: reports
 * were signed carrying them and a register that renders a signed report as "Not stated" has
 * lost the reason it was written down for.</p>
 */
const CAUSE_LABELS: Record<NonOperationalCause, string> = {
  WEATHER: 'Weather',
  HOLIDAY: 'Holiday',
  STRIKE: 'Strike or bandh',
  NO_LABOUR: 'No labour available',
  MATERIAL_SHORTAGE: 'Material not available',
  FUNDS: 'Funds not released',
  DEPARTMENT_INSTRUCTION: 'Stopped by the department',
  SITE_NOT_READY: 'Site not ready for work',
  OTHER: 'Other',
};

/**
 * What the picker offers, which is a subset of what the column holds.
 *
 * <p>Strike, no labour, material shortage and funds were dropped from the list a supervisor
 * chooses from. They stay in {@link CAUSE_LABELS} and in the server's enum, so a report
 * already signed with one still reads as it did — offering a cause and understanding a cause
 * are two different jobs, and only the first one narrowed.</p>
 */
export const NON_OPERATIONAL_CAUSES: { value: NonOperationalCause; label: string }[] = (
  ['WEATHER', 'HOLIDAY', 'DEPARTMENT_INSTRUCTION', 'SITE_NOT_READY', 'OTHER'] as const
).map((value) => ({ value, label: CAUSE_LABELS[value] }));

export function causeLabel(cause: NonOperationalCause | undefined): string {
  return cause ? CAUSE_LABELS[cause] : 'Not stated';
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
 * <p>The report has two authors and, once it has been handed over, both of them at once. The
 * engineer has the work and the observations — a handover he could not write to would leave
 * every report permanently half-written. The day's account stays with whoever is standing on
 * the site: the same supervisor or the one who relieved him, because the weather typed wrong at
 * six and the second mixer that arrived at four are facts he can see and the office cannot.</p>
 *
 * <p>It closes at the signature, not at the handover. The server enforces exactly this; here it
 * decides which buttons exist.</p>
 */
export function isWritable(status: DprWorkflow): boolean {
  return isEditable(status) || status === 'SUBMITTED';
}

/**
 * Whether the office still has to accept it.
 *
 * <p>The engineer's signature claims the work; it is no longer the end of the document. A
 * report sits VERIFIED until somebody holding {@code dpr:approve} countersigns it, and that
 * approval claims nothing — the quantities were already in the measurement book.</p>
 */
export function awaitsOffice(status: DprWorkflow): boolean {
  return status === 'VERIFIED';
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
 * The figures, never one. Only {@link ExpensePrefill.costIncurred} may be added to labour and
 * material consumption — the others are costed elsewhere or not costs at all.
 */
export interface ExpensePrefill {
  totalBooked: number;
  costIncurred: number;
  materialPurchases: number;
  labourDisbursements: number;
  /** Money placed rather than spent, and coming back. Zero on almost every day. */
  refundableDeposits: number;
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

/** What the rate is per. No MONTH: this is one day's report, and a divisor assumed is two
 * screens disagreeing about the same machine. */
export type RateBasis = 'HOUR' | 'DAY';

export interface MachineryResponse {
  id: string;
  machineryName: string;
  count: number;
  hoursUsed: number;
  idleHours: number;
  remarks?: string;
  /**
   * What the machine is charged at, or absent while nobody has said. Filled after the
   * handover by whoever the report goes to — never by the supervisor, who records what stood
   * on the site and not what it costs.
   */
  hireRate?: number;
  rateBasis?: RateBasis;
  /** The rate against this row's own hours or count, derived by the server on every read. */
  hireAmount?: number;
  rateSetAt?: string;
}

/** One machine's price. `rate` absent takes the price off, which is the same act as changing it. */
export interface PlantRateInput {
  machineryId: string;
  rate?: number | undefined;
  basis?: RateBasis | undefined;
}

/**
 * The parts of the printed form a reader may be given or not given.
 *
 * <p>Mirrors {@code DprPdfService.Section}. The identity block, the conditions, a lost day's
 * cause and the signatures are not on the list and never come off: a sheet that can lose its
 * own date is not a document.</p>
 *
 * <p>The order here is the order they appear on the page, which is the order the tick list
 * shows them in — a print dialog whose boxes run in a different order from the paper makes
 * the reader check twice.</p>
 */
export const PRINT_SECTIONS = [
  'WORK',
  'LABOUR',
  'PLANT',
  'MATERIAL',
  'COST',
  'OBSERVATIONS',
  'PHOTOS',
] as const;

export type PrintSection = (typeof PRINT_SECTIONS)[number];

/** What each one is called on the dialog, and what leaving it out actually costs the reader. */
export const PRINT_SECTION_LABEL: Record<PrintSection, string> = {
  WORK: 'Work done',
  LABOUR: 'Labour on site',
  PLANT: 'Plant and machinery',
  MATERIAL: 'Material in and out',
  COST: 'What the day cost',
  OBSERVATIONS: 'Observations',
  PHOTOS: 'Photographs',
};

export const PRINT_SECTION_HINT: Record<PrintSection, string> = {
  WORK: 'The quantities claimed against the contract, and what was recorded but not claimed.',
  LABOUR: 'The muster roll, with names and hours. Rarely wanted outside the firm.',
  PLANT: 'What machinery stood on the site, its hours, and what it was charged at.',
  /*
    The one section read from the store rather than from the report. A lorry booked in at
    half past nine at night against a report verified at six appears here and nowhere else
    on the sheet, and the table says which moment it was taken at.
  */
  MATERIAL: 'What the store received and issued that day, read from the ledger as it stands now.',
  COST: 'Labour, material and other spending for the day. The firm’s business, not the department’s.',
  OBSERVATIONS: 'Instructions from the department and the plan for tomorrow.',
  PHOTOS: 'The list of photographs and their captions. The pictures themselves stay in the app.',
};

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

/**
 * One photograph in a project's gallery, with the day and the place it stands for. Read off
 * the reports and stored nowhere else — see {@code DprGalleryService}.
 */
export interface GalleryPhoto {
  id: string;
  attachmentId: string;
  caption?: string;
  takenAt?: string;
  fileName?: string;
  dprId: string;
  dprNumber: string;
  reportDate: string;
  workflowStatus: DprWorkflow;
  siteId: string;
  siteCode?: string;
  siteName?: string;
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
  /**
   * Every man who stood on the site — the muster roll's plus the suppliers' gangs.
   *
   * <p>Derived by the server from the two figures above, which stay frozen and separate:
   * only one of them has a wage behind it. This is the head count the report leads with,
   * and it is a head count alone — no hours and no money are added along with it.</p>
   */
  menOnSite?: number;
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
  /** The office's countersignature. Absent until it is given; it claims nothing when it is. */
  approvedByName?: string;
  approvedAt?: string;
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

/** Mirrors the DPR module's response shapes. */

export type DprWorkflow = 'DRAFT' | 'SUBMITTED' | 'VERIFIED' | 'REJECTED';

export type Weather = 'CLEAR' | 'CLOUDY' | 'RAIN' | 'HEAVY_RAIN' | 'FOG' | 'SNOW' | 'STORM';

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
  labourContractorId?: string;
  labourContractorName?: string;
  headCount: number;
  regularHours: number;
  overtimeHours: number;
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
  material: MaterialPrefill;
  expense: ExpensePrefill;
  labourCostProvisional: boolean;
  suggestedWorkItems: SuggestedWorkItem[];
  caveat: string;
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
  weather?: Weather;
  temperatureC?: number;
  workingHoursLost?: number;
  labourPresentCount?: number;
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

export interface DprNarrative {
  weather?: Weather | undefined;
  temperatureC?: number | undefined;
  workingHoursLost?: number | undefined;
  workSummary?: string | undefined;
  delays?: string | undefined;
  safetyObservations?: string | undefined;
  qualityObservations?: string | undefined;
  instructionsReceived?: string | undefined;
  managementAttention?: string | undefined;
  nextDayPlan?: string | undefined;
}

export interface CreateDprInput extends DprNarrative {
  id: string;
  siteId: string;
  reportDate: string;
  workItems?: WorkItemInput[];
  machinery?: MachineryInput[];
}

export interface UpdateDprInput extends DprNarrative {
  workItems?: WorkItemInput[];
  machinery?: MachineryInput[];
  version: number;
}

/** Mirrors the identity and project modules' admin response shapes. */

export interface AdminUser {
  id: string;
  username: string;
  fullName: string;
  email?: string;
  mobile?: string;
  active: boolean;
  /** True until the member has replaced the password the admin handed them. */
  mustChangePassword: boolean;
  lastLoginAt?: string;
  roles: string[];
  /** Sites with a live assignment today. Empty for the company-wide roles. */
  siteIds: string[];
  version: number;
}

export interface RoleOption {
  code: string;
  name: string;
  description?: string;
}

export type SiteStatus = 'PLANNED' | 'ACTIVE' | 'SUSPENDED' | 'CLOSED';

export interface AdminSite {
  id: string;
  projectId: string;
  code: string;
  name: string;
  address?: string;
  latitude?: number;
  longitude?: number;
  /**
   * Who is named on the site, and as what. Lists rather than one of each, because a block
   * split between a day and a night supervisor is an ordinary site and one column each
   * described it as an impossible one. Naming somebody here is what grants them access to
   * the site; see SiteStaff and SiteStaffing on the server.
   */
  siteEngineerIds: string[];
  supervisorIds: string[];
  status: SiteStatus;
  startDate?: string;
  standardShiftHours: number;
  monthlyWageDays: number;
  /** Work here is let to labour contractors, so the day is head counts rather than a muster. */
  usesOutsourcedLabour: boolean;
  /** Set only on rows from the deleted list; live lists never carry it. */
  deletedAt?: string;
  deletedBy?: string;
  deletedReason?: string;
  version: number;
}

/**
 * A stock location inside a site — the shed by the gate, and every lockup after it.
 *
 * <p>Every site is given one when it is created, named after the site, so nobody has to think
 * about stores before the first lorry arrives. The register is for the sites that need more
 * than one: a cement store and a separate steel yard are two balances, not one.</p>
 */
export interface AdminStore {
  id: string;
  siteId: string;
  /** Carried on the row because "site-NTL-01" means nothing without the site beside it. */
  siteCode?: string;
  siteName?: string;
  code: string;
  name: string;
  location?: string;
  /** Which store the receive and issue screens open on. One per site. */
  defaultStore: boolean;
  active: boolean;
  version: number;
}

export type ProjectStatus = 'PLANNED' | 'ACTIVE' | 'ON_HOLD' | 'COMPLETED' | 'CLOSED';

/** A contract. Sites hang off it, and every transaction reaches a project through its site. */
export interface AdminProject {
  id: string;
  code: string;
  name: string;
  clientDepartment?: string;
  agreementNo?: string;
  nitNumber?: string;
  tenderReference?: string;
  contractValue?: number;
  budgetAmount?: number;
  startDate?: string;
  expectedCompletionDate?: string;
  actualCompletionDate?: string;
  projectManagerId?: string;
  status: ProjectStatus;
  description?: string;
  /** Set only on rows from the deleted list; live lists never carry it. */
  deletedAt?: string;
  deletedBy?: string;
  deletedReason?: string;
  version: number;
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

/**
 * What the reader made of an uploaded tender notice.
 *
 * <p>Every field is nullable because a NIT is a scanned government document with no schema.
 * A field the reader could not find comes back null so the form can show it as blank and
 * ask, rather than presenting a confident wrong value.</p>
 */
export interface NitFields {
  nitNo: string | null;
  workName: string | null;
  estimatedCost: number | null;
  civilEstimatedCost: number | null;
  electricalEstimatedCost: number | null;
  emdAmount: number | null;
  completionPeriod: string | null;
  submissionClosing: string | null;
  bidOpening: string | null;
  division: string | null;
  location: string | null;
  bidType: string | null;
  contractorEligibility: string | null;
  similarWorkCriteria: string | null;
  performanceGuaranteePercent: number | null;
  securityDepositPercent: number | null;
  civilDsrYear: number | null;
  civilCostIndexPercent: number | null;
  electricalDsrYear: number | null;
  electricalCostIndexPercent: number | null;
}

/** One schedule row as read, before the user has had a chance to correct it. */
export interface PreviewBoqLine {
  index: number;
  itemNumber: string;
  description: string;
  quantity: number | null;
  unit: string | null;
  unitCode: string;
  unitRecognised: boolean;
  rate: number | null;
  amount: number | null;
  /** quantity × rate — what will actually be stored, which the printed amount may not match. */
  derivedAmount: number | null;
  workPart: string | null;
  category: string | null;
  synthetic: boolean;
  renumbered: boolean;
}

/**
 * One row of the tender's table of milestones.
 *
 * Both percentages may be null, and that is a reading rather than a gap: a milestone states a
 * share of the tendered value, or names the work expected finished, or both joined by "or".
 * `physical` says which — and a physical description is the department's own phasing of the
 * work, which is what a plan is built from.
 */
export interface MilestoneTerm {
  sequence: number;
  description: string;
  timeAllowedValue: number | null;
  timeAllowedUnit: 'DAYS' | 'MONTHS' | null;
  financialPercent: number | null;
  /** Held back on a miss, released when a later milestone is met. A timing event, not a cost. */
  withheldPercent: number | null;
  physical: boolean;
}

/** Clause 7's threshold for one work part, or for the whole contract when `workPart` is null. */
export interface InterimMinimumTerm {
  workPart: string | null;
  amount: number;
}

/**
 * The contractual terms read out of Schedule F, round-tripped through the client because the
 * server holds no draft between preview and confirm. Not edited here — correcting a milestone
 * table row by row would be retyping the contract rather than reviewing a reading — but it must
 * travel back on confirm or none of it is stored.
 */
export interface ScheduleTerms {
  completionValue: number | null;
  completionUnit: 'DAYS' | 'MONTHS' | null;
  startReckoningDays: number | null;
  clause7aApplicable: boolean | null;
  milestones: MilestoneTerm[];
  interimMinimums: InterimMinimumTerm[];
}

export interface NitPreview {
  attachmentId: string;
  fileName: string;
  pageCount: number;
  suggestedCode: string | null;
  suggestedName: string | null;
  nitNumber: string | null;
  tenderReference: string | null;
  contractValue: number | null;
  fields: NitFields;
  scheduleTerms: ScheduleTerms;
  boqLines: PreviewBoqLine[];
  boqTotal: number | null;
  derivedTotal: number | null;
  warnings: string[];
}

/** A schedule row as the user confirmed it. The amount is derived server-side, never sent. */
export interface ConfirmedBoqLine {
  itemNumber: string;
  description: string;
  unitCode: string;
  quantity: number;
  rate: number;
  workPart?: string | null;
  category?: string | null;
  synthetic: boolean;
}

/** A priced contract line as stored, with the unit resolved for display. */
export interface BoqItem {
  id: string;
  projectId: string;
  siteId: string | null;
  itemNumber: string;
  description: string;
  unitId: string;
  contractQuantity: number;
  contractRate: number;
  contractAmount: number;
  completedQuantity: number;
  status: 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETED' | 'ON_HOLD' | 'CANCELLED';
  workPart: string | null;
  category: string | null;
  synthetic: boolean;
  sortOrder: number;
  version: number;
}

export interface UnitOption {
  id: string;
  code: string;
  name: string;
  decimalPlaces: number;
  active: boolean;
}

/** The stored reading of the tender a project was created from. */
export interface NitDocument {
  id: string;
  projectId: string;
  attachmentId: string | null;
  fileName: string;
  pageCount: number;
  parserVersion: string;
  fields: NitFields;
  scheduleTerms: ScheduleTerms;
  boqTotal: number | null;
  extractedItemCount: number;
  warnings: string[];
}

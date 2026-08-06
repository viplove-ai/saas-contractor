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
  /** Naming these two is what grants them access to the site; see SiteStaffing on the server. */
  siteEngineerId?: string;
  supervisorId?: string;
  status: SiteStatus;
  startDate?: string;
  standardShiftHours: number;
  monthlyWageDays: number;
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

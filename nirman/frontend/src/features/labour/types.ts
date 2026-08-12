/** Mirrors the labour module's worker master shapes. */

export type WageType = 'DAILY' | 'HOURLY' | 'MONTHLY';
export type EmploymentType = 'PERMANENT' | 'CONTRACT' | 'CASUAL';

export interface WageRate {
  id: string;
  workerId: string;
  normalRate: number;
  overtimeRate: number;
  effectiveFrom: string;
  effectiveTo?: string;
  remarks?: string;
}

export interface Worker {
  id: string;
  workerCode: string;
  fullName: string;
  mobile?: string;
  skillCategoryId?: string;
  employmentType: EmploymentType;
  labourSupplierId?: string;
  wageType: WageType;
  joiningDate?: string;
  exitDate?: string;
  aadhaarLast4?: string;
  active: boolean;
  /** Null until the office sets one; a man with no rate cannot be paid. */
  currentWageRate?: WageRate;
  /** Where he is posted today, or absent if he is not posted anywhere. */
  currentSiteId?: string;
  version: number;
}

export interface Allocation {
  id: string;
  workerId: string;
  siteId: string;
  effectiveFrom: string;
  effectiveTo?: string;
}

/**
 * A site as somewhere to send a man. Comes from /sites/directory, which unlike /sites is
 * not narrowed to your postings — you cannot transfer to a site you are unable to name.
 */
export interface SiteDirectoryEntry {
  id: string;
  projectId: string;
  code: string;
  name: string;
  status: 'PLANNED' | 'ACTIVE' | 'SUSPENDED' | 'CLOSED';
}

export interface SkillCategory {
  id: string;
  code: string;
  name: string;
  skilled: boolean;
  active: boolean;
}

export interface LabourSupplier {
  id: string;
  code: string;
  name: string;
  active: boolean;
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

export const WAGE_TYPE_LABEL: Record<WageType, string> = {
  DAILY: 'Per day',
  HOURLY: 'Per hour',
  MONTHLY: 'Per month',
};

export const EMPLOYMENT_LABEL: Record<EmploymentType, string> = {
  PERMANENT: 'Permanent',
  CONTRACT: 'Contract',
  CASUAL: 'Casual',
};

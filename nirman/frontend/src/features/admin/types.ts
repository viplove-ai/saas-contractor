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

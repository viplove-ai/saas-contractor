/** The staff records: who somebody is, how they are engaged, and what they are paid. */

export type EmploymentType = 'PERMANENT' | 'CONTRACTUAL' | 'PROBATION';

export const EMPLOYMENT_LABEL: Record<EmploymentType, string> = {
  PERMANENT: 'Permanent',
  CONTRACTUAL: 'Contractual',
  PROBATION: 'On probation',
};

/**
 * One member's whole record, with the login's own fields alongside.
 *
 * <p>Almost everything is optional, and that is the state the screen exists to change: a
 * member onboarded as a login has none of this until somebody fills it in. A null
 * {@link StaffProfile.version} is the tell — no record has been saved yet.</p>
 */
export interface StaffProfile {
  userId: string;
  username: string;
  fullName: string;
  mobile?: string;
  email?: string;
  active: boolean;
  roles: string[];

  alternateMobile?: string;
  dateOfBirth?: string;
  /** The last four digits only. The whole number is deliberately never stored. */
  aadhaarLast4?: string;
  pan?: string;
  currentAddress?: string;
  permanentAddress?: string;
  emergencyContactName?: string;
  emergencyContactMobile?: string;
  emergencyContactRelation?: string;
  bankAccountName?: string;
  bankAccountNo?: string;
  bankIfsc?: string;
  bankName?: string;

  employmentType: EmploymentType;
  joinedOn?: string;
  probationDays?: number;
  probationMonthlySalary?: number;
  confirmedMonthlySalary?: number;
  confirmedOn?: string;
  contractEndsOn?: string;
  /** Derived from the joining date and the agreed length — never stored, never sent back. */
  probationEndsOn?: string;
  /** The probation ran out and nobody confirmed them: a pay dispute forming. */
  probationOverdue: boolean;
  /** What the salary history says applies today, which is not always what was agreed. */
  currentSalary?: number;

  exitDate?: string;
  exitReason?: string;
  notes?: string;
  /** Absent until the first save. Its absence is how the form knows it is creating. */
  version?: number;
}

export interface SalaryRevision {
  id: string;
  monthlyAmount: number;
  effectiveFrom: string;
  reason: string;
}

/** One person the dashboard is pointing at, and what about them. */
export interface StaffAlert {
  userId: string;
  fullName: string;
  reason: string;
  on?: string;
}

export interface StaffDashboard {
  totalActive: number;
  permanent: number;
  contractual: number;
  onProbation: number;
  probationOverdue: number;
  probationEndingSoon: number;
  contractsEndingSoon: number;
  missingBankDetails: number;
  noRecordYet: number;
  /** Summed from the salary history on every call. Nothing stores a payroll total. */
  monthlyPayroll: number;
  attentionNeeded: StaffAlert[];
}

/**
 * What a paper on a staff record is.
 *
 * <p>A closed list because the office's question is countable — who has no PAN copy on file
 * — and a caption spelled four ways answers it four times. The note beside it carries
 * whatever the list cannot say.</p>
 */
export type StaffDocumentType =
  | 'AADHAAR'
  | 'PAN'
  | 'BANK'
  | 'APPOINTMENT'
  | 'EDUCATION'
  | 'POLICE_VERIFICATION'
  | 'PHOTOGRAPH'
  | 'OTHER';

export const DOCUMENT_LABEL: Record<StaffDocumentType, string> = {
  AADHAAR: 'Aadhaar card',
  PAN: 'PAN card',
  BANK: 'Bank passbook or cheque',
  APPOINTMENT: 'Appointment or contract letter',
  EDUCATION: 'Qualification certificate',
  POLICE_VERIFICATION: 'Police verification',
  PHOTOGRAPH: 'Photograph',
  OTHER: 'Something else',
};

/** One paper on the record, as the register holds it. */
export interface StaffDocument {
  id: string;
  userId: string;
  attachmentId: string;
  docType: StaffDocumentType;
  note?: string;
  /** What it was called on the device. Absent if the file behind the row has gone. */
  fileName?: string;
  contentType?: string;
  /** Whether the browser can draw it: a scan is shown, a PDF is offered to be opened. */
  image: boolean;
  uploadedAt: string;
  uploadedBy?: string;
}

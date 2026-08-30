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

  /** The short number the office writes on a payslip and a bank advice — not the id. */
  employeeNumber?: string;
  designation?: string;
  /** Twelve digits, issued once and carried between employers. */
  uan?: string;
  esicNumber?: string;
  /**
   * Whether the two statutes reach this member. Stored decisions rather than tests run every
   * month: insurance coverage runs for a whole contribution period and does not lapse
   * mid-period because a raise crossed the ceiling.
   */
  pfApplicable: boolean;
  esiApplicable: boolean;
  /** Contribute to the fund on the whole wage rather than only on the statutory ceiling. */
  pfOnFullWages: boolean;
  noticePeriodDays?: number;

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

/**
 * One revision, with its parts.
 *
 * <p>A salary is not one number, because the law does not treat it as one: the provident fund
 * is computed on basic and dearness allowance, the state insurance on the whole packet, and
 * the Code on Wages then overrules both wherever the allowances have been let run past half
 * of it. {@link SalaryRevision.statutoryWages} is that answer, and it is what tells an office
 * writing a structure whether the split it chose is doing what it thinks.</p>
 */
export interface SalaryRevision {
  id: string;
  monthlyAmount: number;
  /** False for a row recorded before the structure existed — true, and no payslip can be drawn from it. */
  structured: boolean;
  basic?: number;
  dearnessAllowance?: number;
  hra?: number;
  conveyance?: number;
  otherAllowance?: number;
  professionalTax?: number;
  statutoryWages?: number;
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
  | 'OFFER_LETTER'
  | 'APPOINTMENT'
  | 'EDUCATION'
  | 'POLICE_VERIFICATION'
  | 'PHOTOGRAPH'
  | 'OTHER';

export const DOCUMENT_LABEL: Record<StaffDocumentType, string> = {
  AADHAAR: 'Aadhaar card',
  PAN: 'PAN card',
  BANK: 'Bank passbook or cheque',
  OFFER_LETTER: 'Offer letter',
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

/**
 * What the offer letter needs that the record does not already hold.
 *
 * <p>Deliberately short: the designation, the joining date, the probation, the notice period
 * and the whole salary structure are read off the record. Asking for them again here would be
 * a second place to state the same terms, and the letter and the payroll would disagree about
 * the man they both describe within a year.</p>
 */
export interface OfferLetterInput {
  joiningOn?: string | undefined;
  letterDate?: string | undefined;
  reference?: string | undefined;
  placeOfPosting?: string | undefined;
  reportingTo?: string | undefined;
  respondBy?: string | undefined;
  signatoryName?: string | undefined;
  signatoryDesignation?: string | undefined;
}

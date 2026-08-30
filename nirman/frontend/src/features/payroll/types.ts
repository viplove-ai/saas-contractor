/** The month's payroll: what each member earned in it, and what the statutes took. */

export type PayrollRunStatus = 'DRAFT' | 'FINALISED';

/**
 * One frozen payslip.
 *
 * <p>Frozen, unlike every other roll-up in this app, because a payslip is a document issued
 * to a person and reconciled against a transfer that has already left the bank. A slip that
 * recomputed itself would change the day somebody corrected a salary revision, and the copy
 * in the office would stop matching the copy in his hand.</p>
 */
export interface Payslip {
  id: string;
  runId: string;
  userId: string;
  periodMonth: string;
  employeeName: string;
  employeeNumber?: string;
  designation?: string;
  uan?: string;
  esicNumber?: string;

  structBasic: number;
  structDa: number;
  structHra: number;
  structConveyance: number;
  structOther: number;
  structGross: number;

  payableDays: number;
  paidDays: number;
  earnedBasic: number;
  earnedDa: number;
  earnedHra: number;
  earnedConveyance: number;
  earnedOther: number;
  overtimeHours: number;
  overtimeAmount: number;
  totalEarnings: number;

  /**
   * What the Code on Wages makes of this month's packet: basic and dearness allowance, lifted
   * to half the packet where the allowances exceed that. The basis the fund was charged on,
   * and the number an office looking at an unexpected provident fund figure is looking for.
   */
  statutoryWages: number;
  pfWages: number;
  pfEmployee: number;
  esiWages: number;
  esiEmployee: number;
  professionalTax: number;
  /** Section 392 of the Income-tax Act 2025. Typed by the office, never computed here. */
  tds: number;
  salaryAdvance: number;
  otherDeduction: number;
  otherDeductionNote?: string;
  totalDeductions: number;
  netAmount: number;

  /** The employer's own, outside the deductions and outside the net: never his money. */
  pfEmployer: number;
  epsEmployer: number;
  esiEmployer: number;
  employerCost: number;

  remarks?: string;
  finalised: boolean;
  version: number;
}

export interface PayrollTotals {
  headcount: number;
  grossEarnings: number;
  totalDeductions: number;
  netPayable: number;
  pfEmployee: number;
  pfEmployer: number;
  epsEmployer: number;
  esiEmployee: number;
  esiEmployer: number;
  professionalTax: number;
  tds: number;
  employerCost: number;
}

/**
 * Somebody on the books with no slip in this run.
 *
 * <p>Named rather than counted: "three members were skipped" is a sentence somebody reads
 * past, and "Ramesh Yadav — no salary structure recorded" is one he acts on.</p>
 */
export interface NotDrawn {
  userId: string;
  fullName: string;
  reason: string;
}

export interface PayrollRun {
  id: string;
  periodMonth: string;
  periodEnd: string;
  status: PayrollRunStatus;
  payableDays: number;
  notes?: string;
  finalisedAt?: string;
  finalisedBy?: string;
  totals: PayrollTotals;
  payslips: Payslip[];
  notDrawn: NotDrawn[];
  version: number;
}

export interface PayrollRunSummary {
  id: string;
  periodMonth: string;
  status: PayrollRunStatus;
  payableDays: number;
  headcount: number;
  netPayable: number;
  finalisedAt?: string;
}

/** The four things no rule can know, typed onto one slip. */
export interface PayslipInput {
  paidDays: number;
  overtimeHours?: number | undefined;
  /** Sent only where the office has agreed a rate other than the statutory double. */
  overtimeAmount?: number | undefined;
  professionalTax?: number | undefined;
  tds?: number | undefined;
  salaryAdvance?: number | undefined;
  otherDeduction?: number | undefined;
  otherDeductionNote?: string | undefined;
  remarks?: string | undefined;
  version: number;
}

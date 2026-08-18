/** Mirrors the treasury module's response shapes. */

export type SecurityType =
  | 'EMD'
  | 'PERFORMANCE_GUARANTEE'
  | 'ADDITIONAL_PG'
  | 'SECURITY_DEPOSIT';

export type SecurityInstrument = 'FDR' | 'BANK_GUARANTEE' | 'DD' | 'CASH' | 'BILL_RETENTION';

export type SecurityStatus = 'DUE' | 'LODGED' | 'RELEASED' | 'FORFEITED';

/**
 * One parcel of money a department is holding.
 *
 * <p>{@link amount} is the whole liability; {@link heldAmount} is what is actually with them
 * today. They differ for one kind only, and that difference is the point: a fixed deposit is
 * bought once for its whole value, and a security deposit arrives 2.5% at a time as each bill
 * is passed, so on any given day part of it exists and part of it does not.</p>
 */
export interface Security {
  id: string;
  projectId: string;
  projectCode?: string;
  projectName?: string;
  securityType: SecurityType;
  instrument: SecurityInstrument;
  status: SecurityStatus;
  amount: number;
  heldAmount: number;
  /** How the figure was arrived at, in words. */
  basis?: string;
  referenceNo?: string;
  bankName?: string;
  branch?: string;
  lodgedOn?: string;
  /** The deposit's own maturity — not the day the department releases it. */
  maturityOn?: string;
  expectedReleaseOn?: string;
  releasedOn?: string;
  releaseReference?: string;
  redeployedToProjectId?: string;
  redeployedToProjectCode?: string;
  /** The certificate out of the FDR register pledged against this, where one is. */
  bankDepositId?: string;
  forfeitedReason?: string;
  notes?: string;
  /** Negative once the release date has passed. Null unless the deposit is lodged. */
  daysToRelease?: number;
  /** Overdue for release, or maturing before the department is due to give it back. */
  needsAttention: boolean;
  freeToReuse: boolean;
  version: number;
}

/** What the contract says a deposit ought to be. Read once, on the form. */
export interface SecurityProposal {
  securityType: SecurityType;
  instrument: SecurityInstrument;
  amount: number;
  basis: string;
  expectedReleaseOn?: string;
  alreadyRecorded: boolean;
}

export interface ReleaseBucket {
  /** ISO year-month, `2026-08`. */
  month: string;
  lodged: number;
  retained: number;
  count: number;
}

export interface TypeSlice {
  securityType: SecurityType;
  held: number;
  count: number;
  /** Still to be found — the deposits of this kind nobody has placed yet. */
  awaiting: number;
}

export interface BankSlice {
  bankName: string;
  held: number;
  count: number;
}

export interface ProjectTreasuryRow {
  projectId: string;
  projectCode?: string;
  projectName?: string;
  status?: string;
  contractValue?: number;
  emdHeld: number;
  performanceGuaranteeHeld: number;
  additionalPgHeld: number;
  securityDepositHeld: number;
  totalHeld: number;
  awaiting: number;
  releasableNow: number;
  nextReleaseOn?: string;
  nextReleaseAmount?: number;
  attentionCount: number;
}

/**
 * The company's blocked money.
 *
 * <p>{@link totalBlocked} is always shown with {@link lodgedFromOwnFunds} and
 * {@link retainedFromBills} beside it and never in place of them. One is cash the contractor
 * took out of his own working capital and can get back into a bank account; the other is money
 * the department kept out of bills he never received. Reporting only the sum claims a bank
 * balance the company never had.</p>
 */
export interface TreasuryDashboard {
  asOf: string;
  totalBlocked: number;
  lodgedFromOwnFunds: number;
  retainedFromBills: number;
  awaitingLodgement: number;
  awaitingLodgementCount: number;
  releasableNow: number;
  releasableNowCount: number;
  releasingIn30Days: number;
  releasingIn90Days: number;
  releasingIn365Days: number;
  freeToReuse: number;
  freeToReuseCount: number;
  releasedThisFinancialYear: number;
  redeployedThisFinancialYear: number;
  /** All time. Forfeiture carries no date of its own. */
  forfeitedToDate: number;
  byType: TypeSlice[];
  byBank: BankSlice[];
  releaseCalendar: ReleaseBucket[];
  projects: ProjectTreasuryRow[];
  needsAttention: Security[];
  reusable: Security[];
  caveat: string;
}

/** A contract's hold on a certificate, live or finished. */
export interface DepositPledge {
  securityId: string;
  projectId: string;
  projectCode?: string;
  projectName?: string;
  securityType: SecurityType;
  status: SecurityStatus;
  lodgedOn?: string;
  releasedOn?: string;
}

export interface DepositPhoto {
  attachmentId: string;
  caption?: string;
}

export type DepositStatus = 'HELD' | 'CLOSED';

/**
 * One fixed deposit the company holds, whatever it is pledged against.
 *
 * <p>{@link Security} is a contract's side of the story. This is the bank's, and it outlives any
 * one contract: {@code pledgedTo} is who has it now — absent when it is in hand and free for the
 * next tender — and {@code history} is every contract it has ever been lodged with, which is
 * what makes one certificate's reuse legible as a single thread.</p>
 */
export interface BankDeposit {
  id: string;
  depositNumber: string;
  bankName: string;
  branch?: string;
  amount: number;
  issuedOn: string;
  maturityOn?: string;
  interestRate?: number;
  status: DepositStatus;
  closedOn?: string;
  closedReason?: string;
  notes?: string;
  pledgedTo?: DepositPledge;
  history: DepositPledge[];
  photos: DepositPhoto[];
  /** Negative once matured. Absent where no maturity date has been read off the certificate. */
  daysToMaturity?: number;
  version: number;
}

/**
 * @param idleAmount what is held and pledged to nothing — the money free for the next tender,
 *                   which the per-contract register could not answer at all
 */
export interface DepositSummary {
  heldCount: number;
  heldAmount: number;
  pledgedCount: number;
  pledgedAmount: number;
  idleCount: number;
  idleAmount: number;
  closedCount: number;
  maturingSoonCount: number;
}

export interface DepositRegister {
  deposits: BankDeposit[];
  summary: DepositSummary;
}

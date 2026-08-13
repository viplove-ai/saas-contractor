/** The plan a tender is turned into: phases, labour, procurement and cash. */

export interface PhaseView {
  sequence: number;
  description: string | null;
  startDate: string;
  endDate: string;
  /** Null on a physical milestone, which states activities rather than a percentage. */
  targetPercent: number | null;
  plannedValue: number | null;
  plannedPercent: number | null;
  /** Held back on a miss, released when a later milestone is met. Timing, not cost. */
  withheldPercent: number | null;
  physical: boolean;
  onTarget: boolean;
}

export interface PackageView {
  category: string;
  workPart: string | null;
  value: number;
  startDate: string;
  endDate: string;
  gangs: number;
  lineCount: number;
  /** False where no productivity norm matched: value, but no men or dates to act on. */
  normed: boolean;
}

export interface LabourView {
  month: string;
  skillCode: string;
  skilled: boolean;
  manDays: number;
  headCount: number;
  cost: number | null;
}

export interface MaterialView {
  month: string;
  materialCode: string;
  materialName: string | null;
  unitCode: string | null;
  /** What the month consumes. */
  requiredQty: number;
  /** What must be ordered this month for a later one to happen. A different question. */
  procureQty: number;
  procureValue: number | null;
  orderByDate: string | null;
}

export interface CashView {
  month: string;
  labourCost: number;
  materialCost: number;
  staffCost: number;
  plantTransport: number;
  setupCost: number;
  overheadCost: number;
  totalOutflow: number;
  grossBilled: number;
  deductions: number;
  /** What actually arrives, and what funds the next phase. */
  netReceived: number;
  netMovement: number;
  cumulative: number;
}

export interface WorkingCapitalView {
  /** The deepest point of the trough — the money to find. Not the first month's cost. */
  peakFundingRequired: number | null;
  peakMonth: string | null;
  moneyBeforeDayOne: number | null;
  breakEvenMonth: string | null;
  totalRetentionHeld: number | null;
  retentionReleasedOn: string | null;
  totalOutflow: number | null;
  totalNetReceipts: number | null;
}

export interface NoteView {
  kind: 'ASSUMPTION' | 'FINDING';
  severity: 'BLOCKING' | 'WARNING' | 'NOTE' | null;
  subject: string | null;
  value: string | null;
  message: string;
}

export interface Plan {
  /** Absent on a preview, which persists nothing. */
  id: string | null;
  projectId: string | null;
  name: string;
  scenario: string;
  commencementDate: string;
  allowedDays: number;
  quotedPercent: number;
  contractValue: number | null;
  baselined: boolean;
  revision: number;
  phases: PhaseView[];
  packages: PackageView[];
  labour: LabourView[];
  material: MaterialView[];
  cash: CashView[];
  workingCapital: WorkingCapitalView;
  notes: NoteView[];
}

/** Only what somebody deliberately changed; a blank means "use what you read". */
export interface GeneratePlanInput {
  workTypeProfileId?: string | undefined;
  commencementDate?: string;
  allowedDays?: number;
  quotedPercent?: number;
  paymentLagDays?: number;
  defaultDailyWage?: number;
  workingDaysPerMonth?: number;
  monthlyStaffCost?: number;
  siteSetupCost?: number;
  monthlyPlantAndTransport?: number;
  scenario?: string;
  name?: string;
}

export interface WorkTypeProfile {
  id: string;
  code: string;
  name: string;
  description: string | null;
  phaseBasis: 'FLOOR' | 'CHAINAGE' | 'BLOCK' | 'PARALLEL' | 'SEASON';
  monsoonSensitive: boolean;
  defaultOverheadPercent: number;
  active: boolean;
  version: number;
}

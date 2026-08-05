/** Mirrors the dashboard module's response shapes. The module owns no tables. */

export interface Site {
  id: string;
  code: string;
  name: string;
}

/**
 * The three material figures, and the arithmetic that ties them together:
 *
 * <pre>  opening + received − consumed = inventory value</pre>
 *
 * <p>They are three separate figures because they answer three different questions — what
 * arrived, what got used, what is standing in the store. {@link purchased} comes from the
 * bills rather than the ledger and is not the same thing as {@link received}: a bill and its
 * lorry rarely arrive the same day, and freight is booked separately.</p>
 */
export interface MaterialPosition {
  openingValue: number;
  received: number;
  consumed: number;
  inventoryValue: number;
  /** opening + received − consumed − inventory value. Zero when ledger and cache agree. */
  residual: number;
  reconciles: boolean;
  issued: number;
  wasted: number;
  purchased: number;
  purchasedNotReceived: number;
  note: string;
}

/** One day of the cost trend. Split into three series, never blended. */
export interface DailyCost {
  date: string;
  labourCost: number;
  materialConsumed: number;
  otherCost: number;
}

export interface ProjectRow {
  projectId: string;
  projectCode: string;
  projectName: string;
  status: string;
  contractValue: number;
  budgetAmount?: number;
  costIncurred: number;
  /** Null when the project carries no budget — an unanswerable question, not a zero. */
  percentBudgetUsed?: number;
  contractedWorkDone: number;
  percentWorkDone?: number;
  siteCount: number;
}

export interface CompanyDashboard {
  from: string;
  to: string;
  activeProjects: number;
  activeSites: number;
  contractValue: number;
  budgetAmount: number;
  /** Labour + material consumed + other expense. The one total comparable with a budget. */
  costIncurred: number;
  labourCost: number;
  materialConsumed: number;
  otherCost: number;
  /** What left the books. A cash figure, and not a cost figure. */
  totalBooked: number;
  payable: number;
  material: MaterialPosition;
  projects: ProjectRow[];
  trend: DailyCost[];
  caveat: string;
}

export interface LabourTile {
  manDays: number;
  regularHours: number;
  overtimeHours: number;
  cost: number;
  verifiedCost: number;
  /** The part nobody has signed for, and which can therefore still move. */
  unverifiedCost: number;
  pendingVerification: number;
  daysWithAttendance: number;
  daysWithoutAttendance: number;
}

export interface CashTile {
  totalBooked: number;
  costIncurred: number;
  materialPurchases: number;
  labourDisbursements: number;
  paid: number;
  payable: number;
  awaitingApproval: number;
}

export interface ProgressTile {
  contractValue: number;
  valueOfWorkDone: number;
  /** By value, not by line count. */
  percentComplete?: number;
  itemsTotal: number;
  itemsCompleted: number;
  itemsInProgress: number;
  itemsOverClaimed: number;
}

export interface DprTile {
  reportsInRange: number;
  verified: number;
  awaitingVerification: number;
  draft: number;
  daysWithoutReport: string[];
}

export interface WorkItemRow {
  boqItemId: string;
  itemNumber: string;
  description: string;
  contractQuantity: number;
  completedQuantity: number;
  percentComplete?: number;
  overClaimedQuantity: number;
  contractAmount: number;
  status: string;
}

export interface SiteDashboard {
  siteId: string;
  siteCode: string;
  siteName: string;
  projectId: string;
  projectName?: string;
  from: string;
  to: string;
  labour: LabourTile;
  material: MaterialPosition;
  cash: CashTile;
  progress: ProgressTile;
  dpr: DprTile;
  trend: DailyCost[];
  topWorkItems: WorkItemRow[];
  caveat: string;
}

/**
 * A named problem with the records, the count of it, and what to do about it. Every finding
 * carries {@link whatToDo}, because a dashboard that only counts problems gets ignored.
 */
export interface QualityFinding {
  code: string;
  title: string;
  count: number;
  severity: 'ACT' | 'WATCH';
  detail: string;
  whatToDo: string;
  examples: string[];
}

export interface DataQualityDashboard {
  from: string;
  to: string;
  siteId?: string;
  scopeName: string;
  actCount: number;
  watchCount: number;
  findings: QualityFinding[];
  caveat: string;
}

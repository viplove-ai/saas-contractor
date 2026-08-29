import type { AdminProject, ProjectStatus } from './types';

/**
 * The projects list totalled by where each contract stands in its life.
 *
 * <p>Four bands rather than five statuses: COMPLETED and CLOSED are one answer to "how much
 * work have we finished" — the difference between them is whether the paperwork is done, and
 * that is a question about a single contract rather than about the year.</p>
 *
 * <p>Money here is the <b>quoted cost</b> and nothing else. The contract value is the
 * department's estimate and the quoted cost is what the work pays at the rate bid, and a
 * total that took whichever of the two a row happened to carry would be an amount no project
 * is worth. A row with no quoted cost is therefore counted and not totalled, and says so —
 * `unpriced` is what stops a company that never fills the quote box reading a headline of
 * ₹0 as the truth about its order book.</p>
 */
export interface PortfolioBand {
  /** Projects in the band, priced or not. */
  count: number;
  /** Their quoted cost added up, over the priced ones alone. */
  value: number;
  /** How many of the count carry no quoted cost and so are absent from the value. */
  unpriced: number;
}

export interface PortfolioSummary {
  /** ACTIVE: what is being built right now. */
  running: PortfolioBand;
  /** ON_HOLD: stopped, but still on the books. */
  onHold: PortfolioBand;
  /** PLANNED: won or awaited, nothing recorded against it yet. */
  planned: PortfolioBand;
  /** COMPLETED and CLOSED together. */
  finished: PortfolioBand;
  /** Every band added, which is every live project the org has. */
  all: PortfolioBand;
  /**
   * Rows the server holds beyond the ones counted, normally zero.
   *
   * <p>The list asks for one page of a hundred, which is every project a contractor has for
   * years and then one day is not. A total short by the difference looks exactly like a
   * complete one, so the count travels with the figures and the strip says so out loud
   * rather than quietly understating the order book.</p>
   */
  uncounted: number;
}

const BAND_OF: Record<ProjectStatus, keyof Omit<PortfolioSummary, 'all' | 'uncounted'>> = {
  ACTIVE: 'running',
  ON_HOLD: 'onHold',
  PLANNED: 'planned',
  COMPLETED: 'finished',
  CLOSED: 'finished',
};

function empty(): PortfolioBand {
  return { count: 0, value: 0, unpriced: 0 };
}

function add(band: PortfolioBand, quotedCost: number | undefined): void {
  band.count += 1;
  if (quotedCost == null) {
    band.unpriced += 1;
  } else {
    band.value += quotedCost;
  }
}

/**
 * Adds the loaded projects up into the four bands.
 *
 * @param projects the page the list is showing, unfiltered — a summary that followed the
 *   search box would answer a question nobody asked, since filtering to COMPLETED would
 *   report an order book of nothing.
 * @param totalElements what the server said it holds, when it said anything. Anything past
 *   the loaded rows becomes {@link PortfolioSummary.uncounted}.
 */
export function summarise(projects: AdminProject[], totalElements?: number): PortfolioSummary {
  const summary: PortfolioSummary = {
    running: empty(),
    onHold: empty(),
    planned: empty(),
    finished: empty(),
    all: empty(),
    uncounted: Math.max(0, (totalElements ?? projects.length) - projects.length),
  };
  for (const project of projects) {
    add(summary[BAND_OF[project.status]], project.quotedCost);
    add(summary.all, project.quotedCost);
  }
  return summary;
}

const CRORE = 1_00_00_000;
const LAKH = 1_00_000;

/** Rupees with no paise, grouped the way a contractor reads them. */
const rupees = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  maximumFractionDigits: 0,
});

/**
 * A total as a headline: `₹1.85 Cr`, `₹12.5 L`, `₹40,000`.
 *
 * <p>The strip answers "how much work is running" from across the room, and
 * `₹18,50,00,000.00` read at that distance is a number of digits rather than an amount —
 * four of them side by side are unreadable. The exact figure is a row of the table below,
 * which is where somebody who needs the rupees is already looking.</p>
 */
export function headlineAmount(value: number): string {
  if (value >= CRORE) return `₹${trim(value / CRORE)} Cr`;
  if (value >= LAKH) return `₹${trim(value / LAKH)} L`;
  return rupees.format(Math.round(value));
}

/** Two decimals at most, and none at all on a round figure. */
function trim(value: number): string {
  return String(Number(value.toFixed(2)));
}

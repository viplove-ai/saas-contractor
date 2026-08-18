/**
 * The three money figures on a project, and which of them follow from which.
 *
 * <p>The notice states the estimated cost put to tender, the contractor states his quote
 * against it, and everything else is arithmetic somebody was doing on a calculator and typing
 * back in:</p>
 *
 * <pre>
 *   contract value   what the notice put to tender
 *   quoted %         what he bid against it, above (+) or below (-)
 *   quoted cost      the first moved by the second — what the work actually pays
 *   budget           a quarter below the quoted cost — what the office allows itself
 * </pre>
 *
 * <p>Both derived figures are filled in as they are worked out and both stay plain editable
 * boxes: a quoted cost is sometimes a rounder figure than the multiplication gives, and a
 * budget answers to things this arithmetic cannot see — a ceiling the office imposed, a
 * revised estimate. Typing over one holds until the quote changes, because a figure derived
 * from a percentage that has since moved is not an override, it is a stale number nobody
 * decided.</p>
 */

/** The money regex the project form validates against: digits, at most two decimals. */
const AMOUNT = /^\d{1,16}(\.\d{1,2})?$/;

/** Signed, up to three decimals, matching what the quoted-% box accepts. */
const PERCENT = /^-?\d{1,3}(\.\d{1,3})?$/;

/**
 * How far below the quoted cost the budget is proposed.
 *
 * <p>A quarter, and it is a proposal rather than a rule: the office spends against the work
 * and keeps the rest, and how much of it it keeps is a decision. The figure being one number
 * in one place is what stops two screens disagreeing about it.</p>
 */
export const BUDGET_MARGIN = 0.25;

/**
 * What the work pays at the rate bid, as the form would have it typed, or null when the two
 * boxes do not yet say anything.
 *
 * <p>Null rather than a guess in three cases that all look the same to a naive parse: either
 * box empty (nothing has been claimed yet), a half-typed entry such as a lone minus sign, and
 * anything that is not a number the form itself would accept. A quote of zero is not one of
 * them — bidding at par is a real bid, and the answer there is the contract value.</p>
 */
export function quotedCost(
  contractValue: string | undefined,
  quotedPercent: string | undefined,
): string | null {
  const value = contractValue?.trim() ?? '';
  const percent = quotedPercent?.trim() ?? '';
  if (!AMOUNT.test(value) || !PERCENT.test(percent)) {
    return null;
  }
  return format(Number(value) * (1 + Number(percent) / 100));
}

/**
 * The budget that goes with a quoted cost: a quarter below it.
 *
 * <p>Takes what {@link quotedCost} produced rather than recomputing from the contract value,
 * so the box the user is looking at and the box below it can never disagree by a rounding
 * step.</p>
 */
export function budgetFor(cost: string | null | undefined): string | null {
  const value = cost?.trim() ?? '';
  if (!AMOUNT.test(value)) {
    return null;
  }
  return format(Number(value) * (1 - BUDGET_MARGIN));
}

/**
 * Two decimals, and no trailing pair of zeros on a whole number of rupees — the box is one the
 * user may go on to edit, and `10500` is what he would have typed himself.
 */
function format(amount: number): string {
  const fixed = amount.toFixed(2);
  return fixed.endsWith('.00') ? fixed.slice(0, -3) : fixed;
}

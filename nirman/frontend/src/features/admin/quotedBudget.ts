/**
 * What a percentage-rate tender is actually worth once the bid is applied.
 *
 * <p>A percentage-rate tender prices the BOQ at DSR rates and pays the contractor those rates
 * adjusted by his own quote, so the contract value on the notice is the estimate put to tender
 * and the money that will really arrive is that estimate moved by the quote. Nobody should be
 * made to do that multiplication on a phone and type the answer in.</p>
 *
 * <p>It is a suggestion, not a derivation the form enforces: a project's budget can be set to
 * something else for reasons this figure knows nothing about — a ceiling the office imposed, a
 * revised estimate — so the dialog fills the box and then leaves it alone the moment somebody
 * types over it.</p>
 */

/** The money regex the project form validates against: digits, at most two decimals. */
const AMOUNT = /^\d{1,16}(\.\d{1,2})?$/;

/** Signed, up to three decimals, matching what the quoted-% box accepts. */
const PERCENT = /^-?\d{1,3}(\.\d{1,3})?$/;

/**
 * The bid-adjusted contract value as the form would have it typed, or null when the two
 * figures do not yet say anything.
 *
 * <p>Null rather than a guess in three cases that all look the same to a naive parse: either
 * box empty (nothing has been claimed yet), a half-typed entry such as a lone minus sign, and
 * anything that is not a number the form itself would accept. A quote of zero is not one of
 * them — bidding at par is a real bid, and the answer there is the contract value.</p>
 */
export function quotedBudget(
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
 * Two decimals, and no trailing pair of zeros on a whole number of rupees — the box is one the
 * user may go on to edit, and `10500` is what he would have typed himself.
 */
function format(amount: number): string {
  const fixed = amount.toFixed(2);
  return fixed.endsWith('.00') ? fixed.slice(0, -3) : fixed;
}

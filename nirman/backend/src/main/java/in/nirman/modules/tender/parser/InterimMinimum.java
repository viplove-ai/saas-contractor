package in.nirman.modules.tender.parser;

import java.math.BigDecimal;

/**
 * The Clause 7 threshold: gross work to be done before a running account bill may be raised.
 *
 * <p>Stated <b>per work part</b> in every composite notice in the corpus — "Civil Works Rs. 21
 * Lakhs, Electrical Works Rs 05 Lakhs" — so civil and E&amp;M bill on their own rhythms and a
 * single figure could not hold both. A non-composite notice states one amount and
 * {@link #workPart} is null, meaning the whole contract.</p>
 *
 * <p>This is the number that sets the depth of the cash trough: the contractor funds roughly a
 * bill and a half at all times, so a ₹47 lakh threshold is ₹70 lakh of working capital before
 * the payment lag is counted at all.</p>
 *
 * @param workPart {@link BoqLine#CIVIL}, {@link BoqLine#ELECTRICAL}, or null for the whole work
 * @param amount   in rupees, with the notice's lakh or crore multiplier already applied
 */
public record InterimMinimum(String workPart, BigDecimal amount) {
}

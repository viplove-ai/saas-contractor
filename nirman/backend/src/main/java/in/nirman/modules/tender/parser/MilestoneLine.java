package in.nirman.modules.tender.parser;

import java.math.BigDecimal;

/**
 * One row of the tender's table of milestones.
 *
 * <p>A milestone states what must be finished, by when, and what fraction of the contract is
 * withheld if it is not. Both percentage fields are nullable and that is the design: the corpus
 * carries three shapes, and flattening them into one number would throw away the half that
 * matters most.</p>
 *
 * <ul>
 *   <li><b>Financial only</b> — {@code 50% of Tendered Amount | 15 Days | 2.5% withheld}.
 *       {@link #financialPercent} is set, {@link #physical} is false.</li>
 *   <li><b>Physical only or physical with a financial equivalent</b> — a prose description
 *       naming the actual activities ("100% RRM/Retaining Wall, excavation of foundation, lean
 *       concrete, RCC upto plinth level beams") joined by <i>or</i> to "Financially Gross value
 *       of work done : 10% of tendered Value". Both are kept: the description is the
 *       department's own phasing of the work and the percentage is the test it is measured
 *       against.</li>
 * </ul>
 *
 * @param sequence         the row's number as printed, 1-based
 * @param description      everything the row said, whitespace-collapsed; never null
 * @param timeAllowed      from the date of start, unit preserved; null when unreadable
 * @param financialPercent cumulative percentage of the tendered value, when the row states one
 * @param withheldPercent  percentage of the accepted tendered value withheld on a miss.
 *                         Recoverable — released when a later milestone is met — so it is a
 *                         cash-flow timing event and not a cost.
 * @param physical         whether the description names activities rather than only a figure
 */
public record MilestoneLine(
        int sequence,
        String description,
        AllowedTime timeAllowed,
        BigDecimal financialPercent,
        BigDecimal withheldPercent,
        boolean physical) {
}

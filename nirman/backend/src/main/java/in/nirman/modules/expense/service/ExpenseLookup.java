package in.nirman.modules.expense.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The expense module's read API for the DPR and the dashboards.
 *
 * <h2>The one figure a DPR must not report</h2>
 *
 * <p>Total booked. It is what left the books, and it is not what the day cost: a material
 * purchase becomes inventory and is costed again when the material is issued, and a wage
 * payment settles a wage already costed through verified attendance. Add total booked to
 * labour cost and material consumption and the project is overstated twice over — at Kausani
 * by most of ₹4,99,528 on the labour side alone (docs/09).</p>
 *
 * <p>So {@link DailySpend} and {@link PeriodSpend} both carry the split, and
 * {@code costIncurred} is the only one of the four that may be added to anything.</p>
 *
 * <h2>And one the site does not carry at all</h2>
 *
 * <p>V36 gave the approver a fourth answer: this bill was typed at the site and is the
 * company's — an office rent, a staff salary, the half of a diesel bill that ran the office
 * car. {@code companyOverhead} is that part, and it is out of {@code costIncurred} for the
 * same reason the other two are: the site did not incur it. The four still add to
 * {@code totalBooked}, which is what makes the omission checkable rather than a matter of
 * trust.</p>
 */
public interface ExpenseLookup {

    /**
     * @param costIncurred        the part that adds to <i>this site's</i> project cost — total
     *                            booked less material purchases, labour disbursements and
     *                            whatever the approver charged to the company
     * @param companyOverhead     booked here and carried by the organisation (V36). The fourth
     *                            figure, and the reason the other three still add up
     * @param materialPurchases   becomes inventory value; costed again at issue
     * @param labourDisbursements settles wages already costed through attendance
     */
    record DailySpend(
            LocalDate date,
            BigDecimal totalBooked,
            BigDecimal costIncurred,
            BigDecimal companyOverhead,
            BigDecimal materialPurchases,
            BigDecimal labourDisbursements,
            int expenseCount,
            int unapprovedCount) {
    }

    record PeriodSpend(
            LocalDate from,
            LocalDate to,
            BigDecimal totalBooked,
            BigDecimal costIncurred,
            BigDecimal companyOverhead,
            BigDecimal materialPurchases,
            BigDecimal labourDisbursements,
            BigDecimal approvedCost,
            BigDecimal paid,
            BigDecimal payable,
            int expenseCount,
            int awaitingApproval) {
    }

    /** One day's cost incurred — never total booked. For a dashboard trend line. */
    record DailyCost(LocalDate date, BigDecimal costIncurred) {
    }

    /** No permission check: the caller has already passed the one that got it here. */
    DailySpend day(UUID siteId, LocalDate date);

    /**
     * The period's cost incurred bucketed by day, in one pass over the rows rather than one
     * call per day.
     */
    List<DailyCost> dailyCostIncurred(UUID siteId, LocalDate from, LocalDate to);

    /** {@code siteId} null means every site the organisation runs. */
    PeriodSpend period(UUID siteId, LocalDate from, LocalDate to);

    /**
     * Expenses above the evidence threshold carrying neither a bill number nor a photograph,
     * for the data-quality dashboard. The threshold is the one {@code expense_settings}
     * already holds, so the dashboard and the submit-time rule cannot disagree.
     */
    int missingEvidenceCount(UUID siteId, LocalDate from, LocalDate to);
}

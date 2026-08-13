package in.nirman.modules.planning.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * The plan: what gets built when, by how many men, off what material ordered how far ahead, and
 * how much money has to be found before the department pays any of it back.
 *
 * <p>Bucketed by calendar month throughout, because every other monthly thing in this system
 * already is — {@code period_locks.year_month}, payroll, the billing rhythm. A plan on its own
 * week grid would have to be re-bucketed by hand every time it was compared to anything.</p>
 */
public record PlanOutput(
        List<Phase> phases,
        List<Package> packages,
        List<MonthlyLabour> labour,
        List<MonthlyMaterial> material,
        List<MonthlyCash> cash,
        WorkingCapital workingCapital,
        List<Assumption> assumptions,
        List<Finding> findings) {

    /**
     * A window between two milestones.
     *
     * <p>The description is the tender's own, kept verbatim. Where a milestone is physical it
     * names the activities the department expects finished, and that text is the department's
     * phasing of the work — the thing worth printing in a submission and the thing a plan should
     * be built to satisfy rather than checked against afterwards.</p>
     */
    public record Phase(
            int sequence,
            String description,
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal targetPercent,
            BigDecimal plannedValue,
            BigDecimal plannedPercent,
            BigDecimal withheldPercent,
            boolean physical,
            boolean onTarget) {
    }

    /** A schedulable block of work: one trade category on one side of a composite contract. */
    public record Package(
            String category,
            String workPart,
            BigDecimal value,
            LocalDate startDate,
            LocalDate endDate,
            int gangs,
            int lineCount,
            /** False when no productivity norm matched, so this block carries value but no men. */
            boolean normed) {
    }

    /**
     * @param headCount man-days over the month's working days — what a supervisor can act on
     * @param cost      null where the trade is unpriced, which is not the same as zero
     */
    public record MonthlyLabour(
            YearMonth month,
            String skillCode,
            boolean skilled,
            BigDecimal manDays,
            BigDecimal headCount,
            BigDecimal cost) {
    }

    /**
     * Two quantities, and they are different questions.
     *
     * @param requiredQty  what will be consumed this month
     * @param procureQty   what has to be ordered this month so a later month can happen —
     *                     the requirement shifted back by the lead time and its buffer
     * @param orderByDate  the latest date the order can leave and still arrive in time
     */
    public record MonthlyMaterial(
            YearMonth month,
            String materialCode,
            String materialName,
            String unitCode,
            BigDecimal requiredQty,
            BigDecimal procureQty,
            BigDecimal procureValue,
            LocalDate orderByDate) {
    }

    /**
     * One month of money.
     *
     * @param grossBilled  work certified this month at the contractor's own rates
     * @param netReceived  what actually arrives, after retention and the statutory deductions,
     *                     and after the payment lag. This is what funds the next phase, and the
     *                     gap between it and {@code grossBilled} is where optimistic plans die.
     * @param cumulative   running total of inflow minus outflow. Its lowest point is the answer
     *                     to "how much money do we need to start".
     */
    public record MonthlyCash(
            YearMonth month,
            BigDecimal labourCost,
            BigDecimal materialCost,
            BigDecimal staffCost,
            BigDecimal plantAndTransport,
            BigDecimal setupCost,
            BigDecimal overheadCost,
            BigDecimal totalOutflow,
            BigDecimal grossBilled,
            BigDecimal deductions,
            BigDecimal netReceived,
            BigDecimal netMovement,
            BigDecimal cumulative) {
    }

    /**
     * The headline.
     *
     * @param peakFundingRequirement the deepest point of the cumulative trough. <b>Not</b> the
     *                               first month's cost: money keeps going out through the whole
     *                               payment lag, so the trough is typically two to three months
     *                               deep rather than one.
     * @param moneyBeforeDayOne      earnest money, the performance guarantee's margin and
     *                               commission, and the site setup — spent before any work is
     *                               billable
     * @param retentionReleasedOn    when the security deposit comes back, which is after the
     *                               defect liability period and not at handover. Money earned
     *                               and unusable is still money the contractor does not have.
     */
    public record WorkingCapital(
            BigDecimal peakFundingRequirement,
            YearMonth peakMonth,
            BigDecimal moneyBeforeDayOne,
            YearMonth breakEvenMonth,
            BigDecimal totalRetentionHeld,
            LocalDate retentionReleasedOn,
            BigDecimal totalOutflow,
            BigDecimal totalNetReceipts) {
    }

    /**
     * A default the plan had to substitute, named.
     *
     * <p>Not documentation. It is what makes a plan auditable six months later when the question
     * is <i>why did it say we needed forty lakh</i>, and what lets a re-plan on better norms be
     * compared with the old one rather than merely replacing it.</p>
     */
    public record Assumption(String subject, String value, String because) {
    }

    /** Something the reader of the plan has to act on, worst first. */
    public record Finding(Severity severity, String message) {

        public enum Severity { BLOCKING, WARNING, NOTE }
    }
}

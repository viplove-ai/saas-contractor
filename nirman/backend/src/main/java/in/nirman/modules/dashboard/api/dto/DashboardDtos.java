package in.nirman.modules.dashboard.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response shapes for the three dashboards. The dashboard module owns no tables. */
public final class DashboardDtos {

    private DashboardDtos() {
    }

    // ---------------------------------------------------------------- material, everywhere

    /**
     * The three material figures, and the arithmetic that ties them together.
     *
     * <p>docs/02's double-counting guard, expressed as a checkable identity rather than a
     * warning in a comment:</p>
     *
     * <pre>  opening + received − consumed = inventory value</pre>
     *
     * <p>They are three separate figures because they answer three different questions —
     * what arrived, what got used, what is standing in the store — and because reporting one
     * blended "material" number is how a project's cost gets overstated by the value of its
     * own stockyard.</p>
     *
     * @param purchased   from <b>expenses</b>, not from the ledger: what was committed to
     *                    material suppliers. It is not {@code received} and should not be
     *                    expected to equal it — freight is booked separately, a bill can
     *                    arrive before the lorry, and material can arrive before its bill.
     * @param residual    {@code opening + received − consumed − inventoryValue}. Zero when the
     *                    ledger and its balance cache agree, which is the point of computing it.
     * @param reconciles  whether that residual is within a rounding of zero
     */
    public record MaterialPosition(
            BigDecimal openingValue,
            BigDecimal received,
            BigDecimal consumed,
            BigDecimal inventoryValue,
            BigDecimal residual,
            boolean reconciles,
            BigDecimal issued,
            BigDecimal wasted,
            BigDecimal purchased,
            /** Purchased but not yet in a store — bills ahead of deliveries, or freight. */
            BigDecimal purchasedNotReceived,
            String note) {
    }

    // ---------------------------------------------------------------- company dashboard

    /**
     * @param costIncurred labour + material consumed + non-material, non-wage expense. The one
     *                     total that may be compared with a budget.
     * @param totalBooked  everything that left the books, which is a cash figure and not a cost
     *                     figure. Carried beside {@code costIncurred} so nobody has to guess
     *                     which one they are looking at.
     */
    public record CompanyDashboard(
            LocalDate from,
            LocalDate to,
            int activeProjects,
            int activeSites,
            BigDecimal contractValue,
            BigDecimal budgetAmount,
            BigDecimal costIncurred,
            BigDecimal labourCost,
            BigDecimal materialConsumed,
            BigDecimal otherCost,
            BigDecimal totalBooked,
            BigDecimal payable,
            MaterialPosition material,
            List<ProjectRow> projects,
            List<DailyCost> trend,
            String caveat) {
    }

    /**
     * @param percentBudgetUsed null when the project carries no budget — an unanswerable
     *                          question rather than a zero
     */
    public record ProjectRow(
            UUID projectId,
            String projectCode,
            String projectName,
            String status,
            BigDecimal contractValue,
            BigDecimal budgetAmount,
            BigDecimal costIncurred,
            BigDecimal percentBudgetUsed,
            BigDecimal contractedWorkDone,
            BigDecimal percentWorkDone,
            int siteCount) {
    }

    /** One day of the cost trend the charts draw. Split, never blended. */
    public record DailyCost(
            LocalDate date,
            BigDecimal labourCost,
            BigDecimal materialConsumed,
            BigDecimal otherCost) {
    }

    // ---------------------------------------------------------------- site dashboard

    public record SiteDashboard(
            UUID siteId,
            String siteCode,
            String siteName,
            UUID projectId,
            String projectName,
            LocalDate from,
            LocalDate to,
            LabourTile labour,
            /**
             * The suppliers' men, on a card of their own. Absent — {@code enabled} false and
             * every figure zero — at a site that keeps a muster roll.
             */
            OutsourcedLabourTile outsourcedLabour,
            MaterialPosition material,
            CashTile cash,
            ProgressTile progress,
            DprTile dpr,
            List<DailyCost> trend,
            List<WorkItemRow> topWorkItems,
            String caveat) {
    }

    /**
     * @param unverifiedCost the part of the wage bill nobody has signed for. Shown separately
     *                       because the wage is frozen at verification, so this part can still
     *                       move — and a single blended figure would move with it silently.
     */
    public record LabourTile(
            BigDecimal manDays,
            BigDecimal regularHours,
            BigDecimal overtimeHours,
            BigDecimal cost,
            BigDecimal verifiedCost,
            BigDecimal unverifiedCost,
            int pendingVerification,
            int daysWithAttendance,
            int daysWithoutAttendance) {
    }

    /**
     * External labour over the period: what the suppliers' men add up to, and no money.
     *
     * <p>Its own tile rather than more figures on {@link LabourTile}, for the reason the daily
     * report keeps them apart: a man-hour on the muster has a rate behind it and a man-hour at
     * the gate has none, so nothing here may be added to a wage bill or divided into a cost.
     * What the supplier charged for these men is on his bill, and it reaches the site through
     * the expense register like any other bill.</p>
     *
     * @param headDays      each day's head count, summed. Not a head count: eleven masons for
     *                      a fortnight is 154 head-days and eleven men.
     * @param peakHeadCount the largest single day, so the sum above is never read as people
     * @param manHours      over the days that recorded hours; unpriced, and it stays unpriced
     * @param daysWithoutHours days that were counted with no hours written against them —
     *                      the reason {@code manHours} is smaller than the head-days suggest
     */
    public record OutsourcedLabourTile(
            boolean enabled,
            int headDays,
            int peakHeadCount,
            BigDecimal manHours,
            int daysCounted,
            int daysWithoutCount,
            int daysWithoutHours,
            List<OutsourcedTradeRow> trades) {
    }

    /** One trade under one supplier over the period. {@code manHours} is null, never zero,
     *  on a trade no day of which recorded hours. */
    public record OutsourcedTradeRow(
            UUID skillCategoryId,
            String skillCategoryName,
            UUID labourSupplierId,
            String labourSupplierName,
            int headDays,
            BigDecimal manHours,
            int daysCounted) {
    }

    public record CashTile(
            BigDecimal totalBooked,
            BigDecimal costIncurred,
            BigDecimal materialPurchases,
            BigDecimal labourDisbursements,
            /**
             * Of what was booked here, how much was a deposit rather than spending, and how
             * much of that is still out there today (V48).
             *
             * <p>The second is not a figure about the period: a security placed in March and
             * refunded in July is outstanding in every month's reading until July, and the
             * question the office asks of this tile is "what are we holding out", which is a
             * question about now.</p>
             */
            BigDecimal depositsPlaced,
            BigDecimal depositsOutstanding,
            BigDecimal paid,
            BigDecimal payable,
            int awaitingApproval) {
    }

    /**
     * @param percentComplete by value, not by line count: finishing nine cheap lines and none
     *                        of the expensive one is not 90% of a job
     */
    public record ProgressTile(
            BigDecimal contractValue,
            BigDecimal valueOfWorkDone,
            BigDecimal percentComplete,
            int itemsTotal,
            int itemsCompleted,
            int itemsInProgress,
            int itemsOverClaimed) {
    }

    public record DprTile(
            int reportsInRange,
            int verified,
            int awaitingVerification,
            int draft,
            /** Days in range with neither a report nor a weekend excuse. */
            List<LocalDate> daysWithoutReport) {
    }

    public record WorkItemRow(
            UUID boqItemId,
            String itemNumber,
            String description,
            BigDecimal contractQuantity,
            BigDecimal completedQuantity,
            BigDecimal percentComplete,
            BigDecimal overClaimedQuantity,
            BigDecimal contractAmount,
            String status) {
    }

    // ---------------------------------------------------------------- data quality

    /**
     * A named problem with the records, the count of it, and what to do about it.
     *
     * <p>Every row carries {@code whatToDo} because a data-quality dashboard that only counts
     * problems is a dashboard that gets ignored. "Eleven days unmarked" is a complaint;
     * "eleven days unmarked, and here they are" is a task.</p>
     *
     * @param severity WATCH or ACT. Two levels on purpose: a five-level scale invites
     *                 arguments about whether something is a 3 or a 4, and nobody acts on a 3.
     */
    public record QualityFinding(
            String code,
            String title,
            int count,
            String severity,
            String detail,
            String whatToDo,
            /** Dates, ids or names behind the count, capped so the payload stays readable. */
            List<String> examples) {
    }

    public record DataQualityDashboard(
            LocalDate from,
            LocalDate to,
            UUID siteId,
            String scopeName,
            int actCount,
            int watchCount,
            List<QualityFinding> findings,
            String caveat) {
    }
}

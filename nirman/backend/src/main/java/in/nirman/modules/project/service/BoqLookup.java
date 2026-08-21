package in.nirman.modules.project.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The project module's read API for BOQ items, in the shape the other modules need.
 *
 * <p>Inventory asks two things of it. Charging an issue to a work item needs the item to
 * exist, belong to the right project and not be a parser placeholder. Computing an
 * estimated-versus-actual variance needs the item's number and description to say what the
 * scope of the comparison actually was.</p>
 */
public interface BoqLookup {

    /**
     * @param synthetic a reconciliation placeholder rather than real work. Nothing may be
     *                  charged to one, so callers check this before accepting a reference.
     */
    record BoqItemInfo(
            UUID id,
            UUID projectId,
            UUID siteId,
            String itemNumber,
            String description,
            String category,
            /** Civil Works or E&M Works. The two bill and sequence on their own rhythms. */
            String workPart,
            BigDecimal contractQuantity,
            UUID unitId,
            BigDecimal contractAmount,
            /**
             * The rate the contract states, not {@code contractAmount / contractQuantity}.
             * Billing needs the figure as tendered: a bill prices work at the agreement rate
             * and a rate recovered by division carries whatever rounding the amount was
             * stored with, which is exactly the sort of drift a checker notices.
             */
            BigDecimal contractRate,
            boolean synthetic) {
    }

    /**
     * @throws in.nirman.common.BusinessException 404 if no such live item in the caller's
     *         org, 422 if it is synthetic
     */
    BoqItemInfo requireChargeable(UUID boqItemId);

    Map<UUID, BoqItemInfo> byIds(Collection<UUID> boqItemIds);

    List<BoqItemInfo> forProject(UUID projectId);

    /**
     * How far a line has got. Read by the dashboards, which need the money as well as the
     * quantity.
     *
     * @param percentComplete null when the line quantified nothing — an unanswerable question
     *                        rather than a zero
     * @param valueOfWorkDone {@code completedQuantity × contractRate}, capped at the contract
     *                        amount. The cap is the point: work measured beyond the tendered
     *                        quantity is not automatically money the client owes, and letting
     *                        it inflate a percent-complete figure would make the dashboard
     *                        claim a project is 110% done.
     */
    record ItemProgress(
            UUID boqItemId,
            String itemNumber,
            String description,
            BigDecimal contractQuantity,
            BigDecimal completedQuantity,
            BigDecimal percentComplete,
            BigDecimal overClaimedQuantity,
            BigDecimal contractAmount,
            BigDecimal valueOfWorkDone,
            String status) {
    }

    /**
     * @param valueOfWorkDone summed over the lines, which is what makes percent-complete a
     *                        value figure rather than a line count. Finishing nine cheap lines
     *                        and none of the expensive one is not 90% of a job.
     */
    record ProgressSummary(
            BigDecimal contractValue,
            BigDecimal valueOfWorkDone,
            BigDecimal percentComplete,
            int itemsTotal,
            int itemsCompleted,
            int itemsInProgress,
            int itemsOverClaimed,
            List<ItemProgress> items) {
    }

    /**
     * Progress over a project, or over the lines belonging to one site of it.
     *
     * <p>Placeholders are excluded. A reconciliation gap the tender parser emitted is not work,
     * so counting its zero quantity against the total would drag every percentage down by the
     * number of rounding artefacts in the import.</p>
     */
    ProgressSummary progress(UUID projectId, UUID siteId);
}

package in.nirman.modules.inventory.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The inventory module's read API for the DPR and the dashboards.
 *
 * <h2>Received, consumed and held are three different figures</h2>
 *
 * <p>docs/02's double-counting guard turns on keeping them apart. Material arriving at a
 * store is not cost — it is inventory, and it becomes cost when it is issued to the work
 * face, valued at the moving average of the day it left. So a dashboard reports what came in,
 * what was used up and what is still on the ground as three numbers, and the only claim it
 * makes about them is the one that is actually true:</p>
 *
 * <pre>  opening value + received value − consumed value = closing value</pre>
 *
 * <p>That identity is checkable, which is why {@link StockMovement} carries every term of it
 * and the residual. A dashboard that showed one blended "material" figure would be reporting
 * a number that reconciles against nothing.</p>
 */
public interface InventoryLookup {

    /**
     * One store or site's movements over a period, with the arithmetic that ties them
     * together.
     *
     * @param consumedValue issues, wastage and damage — material that has left for good.
     *                      A transfer out is <b>not</b> here: it was relocated, not consumed.
     * @param residual      {@code opening + received − consumed − closing}. Zero unless a
     *                      transfer crossed the boundary of the scope or the period, in which
     *                      case it is the honest name for the difference rather than a
     *                      rounding fudge.
     */
    record StockMovement(
            LocalDate from,
            LocalDate to,
            BigDecimal openingValue,
            BigDecimal receivedValue,
            BigDecimal consumedValue,
            BigDecimal issuedValue,
            BigDecimal wastedValue,
            BigDecimal transferredOutValue,
            BigDecimal transferredInValue,
            BigDecimal closingValue,
            BigDecimal residual,
            boolean reconciles) {
    }

    /** One material's movement on one day, for the DPR's material table. */
    record MaterialMovement(
            UUID materialId,
            String materialCode,
            String materialName,
            String baseUnitCode,
            BigDecimal receivedQty,
            BigDecimal receivedValue,
            BigDecimal consumedQty,
            BigDecimal consumedValue) {
    }

    /**
     * What one site received and consumed on one day.
     *
     * @param boqItemIds work items material was charged to that day. The DPR prefills its
     *                   work-item rows from these, because a line that had cement issued
     *                   against it this morning is a line somebody worked on.
     */
    record MaterialDay(
            LocalDate date,
            BigDecimal receivedValue,
            BigDecimal consumedValue,
            int receiptCount,
            int issueCount,
            List<MaterialMovement> materials,
            List<UUID> boqItemIds) {
    }

    /** One day's consumption value, for a dashboard trend line. */
    record DailyConsumption(LocalDate date, BigDecimal consumedValue) {
    }

    /** No permission check: the caller has already passed the one that got it here. */
    MaterialDay day(UUID siteId, LocalDate date);

    /**
     * The period's consumption bucketed by day, in one pass over the ledger rather than one
     * call per day.
     */
    List<DailyConsumption> dailyConsumption(UUID siteId, LocalDate from, LocalDate to);

    /** {@code siteId} null means every site the organisation runs. */
    StockMovement movement(UUID siteId, LocalDate from, LocalDate to);

    /** The balance cache's value — what is standing in the stores right now. */
    BigDecimal inventoryValue(UUID siteId);

    /** Materials below their own reorder level, for the site dashboard's shortage tile. */
    int lowStockCount(UUID siteId);

    /** Issues charged to no work item over a period — consumption nobody can attribute. */
    int consumptionWithoutBoqItem(UUID siteId, LocalDate from, LocalDate to);
}

package in.nirman.modules.inventory.api.dto;

import in.nirman.modules.inventory.domain.StockTransaction.TxnType;
import in.nirman.modules.inventory.domain.StockTransfer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The five inventory reports docs/05 names, as JSON before they become spreadsheets. */
public final class InventoryReportDtos {

    private InventoryReportDtos() {
    }

    /**
     * What each store holds. The same figures the stock screen shows, arranged for printing
     * and signing — which is what a store position is actually used for at month end.
     */
    public record StockPositionReport(
            String scopeName,
            LocalDate asOf,
            List<InventoryDtos.StockRow> rows,
            BigDecimal totalValue) {
    }

    /**
     * What was consumed over a period, per material.
     *
     * <p>Issued and wasted are kept apart rather than summed. Both leave the store and both
     * are cost, but one went into the work and the other did not, and a site that is losing
     * eight per cent of its cement to breakage needs to see that as its own number.</p>
     */
    public record ConsumptionRow(
            UUID materialId,
            String materialCode,
            String materialName,
            String baseUnitCode,
            BigDecimal issuedQty,
            BigDecimal issuedValue,
            BigDecimal wastedQty,
            BigDecimal wastedValue,
            BigDecimal totalConsumedQty,
            BigDecimal totalConsumedValue,
            BigDecimal closingQty) {
    }

    public record ConsumptionReport(
            String siteName,
            LocalDate from,
            LocalDate to,
            List<ConsumptionRow> rows,
            BigDecimal totalConsumedValue) {
    }

    /**
     * Materials at or below their own reorder level.
     *
     * @param inTransitQty material already on its way here. Shown beside the shortfall
     *                     because a store that is short forty bags with fifty on a lorry
     *                     does not need anybody to raise a purchase order.
     */
    public record LowStockRow(
            UUID storeId,
            String storeName,
            UUID materialId,
            String materialCode,
            String materialName,
            String baseUnitCode,
            BigDecimal quantityBase,
            BigDecimal minStockLevel,
            BigDecimal shortfall,
            BigDecimal inTransitQty) {
    }

    public record LowStockReport(
            String scopeName,
            LocalDate asOf,
            List<LowStockRow> rows) {
    }

    /**
     * Transfers over a period.
     *
     * @param status what makes this report worth reading: everything still {@code IN_TRANSIT}
     *               is material counted at neither end, and a transfer that has been in
     *               transit for three weeks is a lorry nobody chased
     */
    public record TransferRegisterRow(
            UUID transferId,
            String transferNumber,
            LocalDate transferDate,
            String fromStoreName,
            String toStoreName,
            StockTransfer.Status status,
            int lineCount,
            BigDecimal dispatchedQty,
            BigDecimal receivedQty,
            BigDecimal shortageQty,
            BigDecimal value) {
    }

    public record TransferRegisterReport(
            LocalDate from,
            LocalDate to,
            List<TransferRegisterRow> rows,
            BigDecimal totalShortageQty) {
    }

    /** Wastage and damage, with the reason each one carried. */
    public record WastageRow(
            LocalDate txnDate,
            UUID storeId,
            String storeName,
            UUID materialId,
            String materialCode,
            String materialName,
            String baseUnitCode,
            TxnType txnType,
            BigDecimal quantityBase,
            BigDecimal value,
            UUID boqItemId,
            String reason) {
    }

    public record WastageReport(
            String siteName,
            LocalDate from,
            LocalDate to,
            List<WastageRow> rows,
            BigDecimal totalValue) {
    }
}

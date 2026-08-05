package in.nirman.modules.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * One material on a transfer.
 *
 * <p>{@code quantityBase} is what left; {@code receivedQtyBase} is what arrived. They are
 * separate columns rather than one because they genuinely differ — sand blows off a tipper
 * and bags split — and the difference is the shortage, which somebody has to answer for.
 * The receiving store is credited with what arrived, never with what was promised.</p>
 *
 * <p>{@code rateBase} is the sending store's moving average, frozen at dispatch and carried
 * across. A transfer moves material between two of our own stores; it is not a purchase and
 * must not reprice anything.</p>
 */
@Entity
@Table(name = "stock_transfer_items")
public class StockTransferItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "transfer_id", nullable = false, updatable = false)
    private UUID transferId;

    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;

    @Column(name = "unit_id", nullable = false)
    private UUID unitId;

    @Column(name = "quantity", nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(name = "quantity_base", nullable = false, precision = 18, scale = 4)
    private BigDecimal quantityBase;

    @Column(name = "received_qty_base", precision = 18, scale = 4)
    private BigDecimal receivedQtyBase;

    @Column(name = "rate_base", precision = 18, scale = 4)
    private BigDecimal rateBase;

    @Column(name = "shortage_qty_base", nullable = false, precision = 18, scale = 4)
    private BigDecimal shortageQtyBase = BigDecimal.ZERO;

    @Column(name = "remarks", length = 300)
    private String remarks;

    protected StockTransferItem() {
    }

    public StockTransferItem(UUID transferId, UUID materialId, UUID unitId, BigDecimal quantity,
                             BigDecimal factorToBase) {
        this.id = UUID.randomUUID();
        this.transferId = transferId;
        this.materialId = materialId;
        this.unitId = unitId;
        this.quantity = quantity;
        this.quantityBase = quantity.multiply(factorToBase).setScale(4, RoundingMode.HALF_UP);
    }

    /** Freezes the sending store's average as the line leaves. */
    public void dispatchAt(BigDecimal rate) {
        this.rateBase = rate;
    }

    /**
     * @param arrived what actually turned up; null means all of it did, which is the common
     *                case and the one the receiving screen should not make anybody type
     */
    public void receiveQuantity(BigDecimal arrived) {
        BigDecimal received = arrived == null ? quantityBase : arrived;
        this.receivedQtyBase = received;
        BigDecimal shortage = quantityBase.subtract(received);
        this.shortageQtyBase = shortage.signum() < 0 ? BigDecimal.ZERO : shortage;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public UUID getMaterialId() {
        return materialId;
    }

    public UUID getUnitId() {
        return unitId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getQuantityBase() {
        return quantityBase;
    }

    public BigDecimal getReceivedQtyBase() {
        return receivedQtyBase;
    }

    public BigDecimal getRateBase() {
        return rateBase;
    }

    public BigDecimal getShortageQtyBase() {
        return shortageQtyBase;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }
}

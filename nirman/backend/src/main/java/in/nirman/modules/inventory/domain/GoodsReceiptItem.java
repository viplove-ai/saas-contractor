package in.nirman.modules.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * One material on a delivery.
 *
 * <p>Both the entered unit and the base unit are kept. The storekeeper books what the
 * challan says — two tonnes of steel — and the ledger needs kilograms, because the issue
 * that draws it down will be in kilograms. Converting on the way in and keeping both means
 * the document still reads like the paper it came from while the arithmetic stays in one
 * unit.</p>
 *
 * <p>{@code rateBase} is the valuation rate and excludes GST. Tax on a purchase is a
 * liability, not part of what the material cost to put in the store; folding it into the
 * moving average would inflate every issue value by the tax rate.</p>
 */
@Entity
@Table(name = "goods_receipt_items")
public class GoodsReceiptItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "grn_id", nullable = false, updatable = false)
    private UUID grnId;

    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;

    @Column(name = "unit_id", nullable = false)
    private UUID unitId;

    @Column(name = "quantity", nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(name = "quantity_base", nullable = false, precision = 18, scale = 4)
    private BigDecimal quantityBase;

    @Column(name = "rate", nullable = false, precision = 18, scale = 4)
    private BigDecimal rate;

    @Column(name = "rate_base", nullable = false, precision = 18, scale = 4)
    private BigDecimal rateBase;

    @Column(name = "gst_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal gstPercent = BigDecimal.ZERO;

    @Column(name = "gst_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal gstAmount = BigDecimal.ZERO;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "remarks", length = 300)
    private String remarks;

    protected GoodsReceiptItem() {
    }

    /**
     * @param factorToBase base units per entered unit — 1000 for a tonne of a material
     *                     stocked in kilograms, 1 when the entered unit is the base unit
     */
    public GoodsReceiptItem(UUID grnId, UUID materialId, UUID unitId, BigDecimal quantity,
                            BigDecimal rate, BigDecimal gstPercent, BigDecimal factorToBase) {
        this.id = UUID.randomUUID();
        this.grnId = grnId;
        this.materialId = materialId;
        this.unitId = unitId;
        this.quantity = quantity;
        this.rate = rate;
        this.gstPercent = gstPercent == null ? BigDecimal.ZERO : gstPercent;
        this.quantityBase = quantity.multiply(factorToBase).setScale(4, RoundingMode.HALF_UP);
        this.rateBase = rate.divide(factorToBase, 4, RoundingMode.HALF_UP);
        this.amount = quantity.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        this.gstAmount = amount.multiply(this.gstPercent)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    public UUID getId() {
        return id;
    }

    public UUID getGrnId() {
        return grnId;
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

    public BigDecimal getRate() {
        return rate;
    }

    public BigDecimal getRateBase() {
        return rateBase;
    }

    public BigDecimal getGstPercent() {
        return gstPercent;
    }

    public BigDecimal getGstAmount() {
        return gstAmount;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }
}

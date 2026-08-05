package in.nirman.modules.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One material on a count sheet: what the ledger said, and what was on the floor.
 *
 * <p>{@code varianceQtyBase} is a generated column in the database — counted minus system —
 * so it is read here and never written. A variance that could be typed independently of the
 * two numbers it comes from would be the one figure on the sheet nobody could check.</p>
 */
@Entity
@Table(name = "physical_stock_count_items")
public class PhysicalStockCountItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "count_id", nullable = false, updatable = false)
    private UUID countId;

    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;

    /** The balance at the moment the sheet was raised, frozen so the variance is reproducible. */
    @Column(name = "system_qty_base", nullable = false, precision = 18, scale = 4)
    private BigDecimal systemQtyBase;

    @Column(name = "counted_qty_base", nullable = false, precision = 18, scale = 4)
    private BigDecimal countedQtyBase;

    @Column(name = "variance_qty_base", precision = 18, scale = 4, insertable = false, updatable = false)
    private BigDecimal varianceQtyBase;

    @Column(name = "variance_reason", length = 300)
    private String varianceReason;

    protected PhysicalStockCountItem() {
    }

    public PhysicalStockCountItem(UUID countId, UUID materialId, BigDecimal systemQtyBase,
                                  BigDecimal countedQtyBase, String varianceReason) {
        this.id = UUID.randomUUID();
        this.countId = countId;
        this.materialId = materialId;
        this.systemQtyBase = systemQtyBase;
        this.countedQtyBase = countedQtyBase;
        this.varianceReason = varianceReason;
    }

    /** Counted minus system, computed here for the code path that has not reloaded the row. */
    public BigDecimal variance() {
        return countedQtyBase.subtract(systemQtyBase);
    }

    public UUID getId() {
        return id;
    }

    public UUID getCountId() {
        return countId;
    }

    public UUID getMaterialId() {
        return materialId;
    }

    public BigDecimal getSystemQtyBase() {
        return systemQtyBase;
    }

    public BigDecimal getCountedQtyBase() {
        return countedQtyBase;
    }

    public BigDecimal getVarianceQtyBase() {
        return varianceQtyBase == null ? variance() : varianceQtyBase;
    }

    public String getVarianceReason() {
        return varianceReason;
    }
}

package in.nirman.modules.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * One material on an issue slip.
 *
 * <p>{@code issuedRate} and {@code value} are empty until the issue is approved and the
 * stock actually moves, at which point the store's moving average is frozen onto the line.
 * Freezing it is what makes a month's consumption cost stable: a delivery arriving the
 * following week moves the average, and it must not retroactively reprice material that
 * was already in the wall.</p>
 */
@Entity
@Table(name = "material_issue_items")
public class MaterialIssueItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "issue_id", nullable = false, updatable = false)
    private UUID issueId;

    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;

    @Column(name = "unit_id", nullable = false)
    private UUID unitId;

    @Column(name = "quantity", nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(name = "quantity_base", nullable = false, precision = 18, scale = 4)
    private BigDecimal quantityBase;

    @Column(name = "issued_rate", precision = 18, scale = 4)
    private BigDecimal issuedRate;

    @Column(name = "value", precision = 18, scale = 2)
    private BigDecimal value;

    /** Overrides the header's BOQ item when one slip covers two work items. */
    @Column(name = "boq_item_id")
    private UUID boqItemId;

    @Column(name = "remarks", length = 300)
    private String remarks;

    protected MaterialIssueItem() {
    }

    public MaterialIssueItem(UUID issueId, UUID materialId, UUID unitId, BigDecimal quantity,
                             BigDecimal factorToBase, UUID boqItemId) {
        this.id = UUID.randomUUID();
        this.issueId = issueId;
        this.materialId = materialId;
        this.unitId = unitId;
        this.quantity = quantity;
        this.quantityBase = quantity.multiply(factorToBase).setScale(4, RoundingMode.HALF_UP);
        this.boqItemId = boqItemId;
    }

    /** Freezes the store's average onto the line at the moment the stock leaves. */
    public void valueAt(BigDecimal rate) {
        this.issuedRate = rate;
        this.value = quantityBase.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    public UUID getId() {
        return id;
    }

    public UUID getIssueId() {
        return issueId;
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

    public BigDecimal getIssuedRate() {
        return issuedRate;
    }

    public BigDecimal getValue() {
        return value;
    }

    public UUID getBoqItemId() {
        return boqItemId;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }
}

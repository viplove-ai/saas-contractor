package in.nirman.modules.inventory.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * How much material a unit of work consumes — 5.0 bags of cement per cubic metre of RCC.
 *
 * <p>Stored once and reused across every tender, because the coefficient is a property of
 * the work, not of the job. {@code workCategory} matches {@code boq_items.category}, which
 * is what lets a parsed NIT be turned into material quantities without a human mapping
 * every line.</p>
 */
@Entity
@Table(name = "material_consumption_norms")
public class MaterialConsumptionNorm extends BaseEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "work_category", nullable = false, length = 80)
    private String workCategory;

    /** The grade or mix: M25, 1:4:8, Fe-500D. Null when the category alone decides. */
    @Column(name = "work_sub_type", length = 80)
    private String workSubType;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "work_unit_id", nullable = false)
    private UUID workUnitId;

    @Column(name = "qty_per_work_unit", nullable = false, precision = 18, scale = 6)
    private BigDecimal qtyPerWorkUnit;

    @Column(name = "source", nullable = false, length = 20)
    private String source = "INTERNAL";

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "notes")
    private String notes;

    protected MaterialConsumptionNorm() {
    }

    public MaterialConsumptionNorm(UUID orgId, String workCategory, String workSubType,
                                   UUID materialId, UUID workUnitId, BigDecimal qtyPerWorkUnit,
                                   String source) {
        this.orgId = orgId;
        this.workCategory = workCategory;
        this.workSubType = workSubType;
        this.materialId = materialId;
        this.workUnitId = workUnitId;
        this.qtyPerWorkUnit = qtyPerWorkUnit;
        this.source = source == null ? "INTERNAL" : source;
    }

    /** The material quantity a given amount of work implies. */
    public BigDecimal applyTo(BigDecimal workQuantity) {
        return workQuantity.multiply(qtyPerWorkUnit).setScale(4, RoundingMode.HALF_UP);
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getWorkCategory() {
        return workCategory;
    }

    public String getWorkSubType() {
        return workSubType;
    }

    public UUID getMaterialId() {
        return materialId;
    }

    public UUID getWorkUnitId() {
        return workUnitId;
    }

    public BigDecimal getQtyPerWorkUnit() {
        return qtyPerWorkUnit;
    }

    public String getSource() {
        return source;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}

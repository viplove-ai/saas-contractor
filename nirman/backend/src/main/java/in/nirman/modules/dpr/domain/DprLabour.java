package in.nirman.modules.dpr.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A row of the DPR's labour table: how many of one trade, under one contractor, for how long.
 *
 * <p>Written from the muster roll rather than typed — this is the snapshot the DPR freezes, so
 * that the printed report keeps saying "six masons, forty-two hours" after somebody corrects
 * an attendance row next week. It carries head counts and hours and <b>no money</b>: the wage
 * total sits once on the report itself, where it can be labelled provisional or verified,
 * instead of being scattered across six lines that each look final.</p>
 */
@Entity
@Table(name = "dpr_labour")
public class DprLabour {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "dpr_id", nullable = false, updatable = false)
    private UUID dprId;

    @Column(name = "skill_category_id")
    private UUID skillCategoryId;

    @Column(name = "labour_supplier_id")
    private UUID labourSupplierId;

    @Column(name = "head_count", nullable = false)
    private int headCount;

    @Column(name = "regular_hours", nullable = false, precision = 10, scale = 2)
    private BigDecimal regularHours = BigDecimal.ZERO;

    @Column(name = "overtime_hours", nullable = false, precision = 10, scale = 2)
    private BigDecimal overtimeHours = BigDecimal.ZERO;

    /**
     * The row came from a head count at a contractor-run site, not from the muster roll. It
     * prints in the same table and is never added into the same total: there are no hours
     * and no wage behind it.
     */
    @Column(name = "outsourced", nullable = false)
    private boolean outsourced;

    protected DprLabour() {
    }

    public DprLabour(UUID dprId, UUID skillCategoryId, UUID labourSupplierId, int headCount,
                     BigDecimal regularHours, BigDecimal overtimeHours) {
        this(dprId, skillCategoryId, labourSupplierId, headCount, regularHours, overtimeHours,
                false);
    }

    public DprLabour(UUID dprId, UUID skillCategoryId, UUID labourSupplierId, int headCount,
                     BigDecimal regularHours, BigDecimal overtimeHours, boolean outsourced) {
        this.id = UUID.randomUUID();
        this.dprId = dprId;
        this.skillCategoryId = skillCategoryId;
        this.labourSupplierId = labourSupplierId;
        this.headCount = headCount;
        this.regularHours = regularHours == null ? BigDecimal.ZERO : regularHours;
        this.overtimeHours = overtimeHours == null ? BigDecimal.ZERO : overtimeHours;
        this.outsourced = outsourced;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDprId() {
        return dprId;
    }

    public UUID getSkillCategoryId() {
        return skillCategoryId;
    }

    public UUID getLabourSupplierId() {
        return labourSupplierId;
    }

    public int getHeadCount() {
        return headCount;
    }

    public BigDecimal getRegularHours() {
        return regularHours;
    }

    public BigDecimal getOvertimeHours() {
        return overtimeHours;
    }

    public boolean isOutsourced() {
        return outsourced;
    }
}

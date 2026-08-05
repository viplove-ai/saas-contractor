package in.nirman.modules.dpr.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A line of work the site did that day.
 *
 * <p>{@code activity} is free text and always required; {@code boqItemId} and
 * {@code quantity} are optional, and the difference between them is what the DPR is for.
 * "Shuttering removed from first-floor slab" is real work that measures against no contract
 * line. "22 cum of RCC in beams" is a <b>claim against the contract</b>, and when the engineer
 * verifies the report it becomes a dated entry in the measurement book.</p>
 *
 * <p>So a row with a work item and a quantity is money; a row without is a description. Both
 * belong on the report, and only the first one moves anything.</p>
 */
@Entity
@Table(name = "dpr_work_items")
public class DprWorkItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "dpr_id", nullable = false, updatable = false)
    private UUID dprId;

    @Column(name = "boq_item_id")
    private UUID boqItemId;

    @Column(name = "activity", nullable = false)
    private String activity;

    @Column(name = "work_location", length = 150)
    private String workLocation;

    /** In the BOQ line's unit. Null when the row describes work rather than measuring it. */
    @Column(name = "quantity", precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(name = "unit_id")
    private UUID unitId;

    @Column(name = "remarks")
    private String remarks;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected DprWorkItem() {
    }

    public DprWorkItem(UUID dprId, UUID boqItemId, String activity, String workLocation,
                       BigDecimal quantity, UUID unitId, String remarks, int sortOrder) {
        this.id = UUID.randomUUID();
        this.dprId = dprId;
        this.boqItemId = boqItemId;
        this.activity = activity;
        this.workLocation = workLocation;
        this.quantity = quantity;
        this.unitId = unitId;
        this.remarks = remarks;
        this.sortOrder = sortOrder;
    }

    /** True when this row claims measured work against the contract. */
    public boolean isMeasured() {
        return boqItemId != null && quantity != null && quantity.signum() != 0;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDprId() {
        return dprId;
    }

    public UUID getBoqItemId() {
        return boqItemId;
    }

    public String getActivity() {
        return activity;
    }

    public String getWorkLocation() {
        return workLocation;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public UUID getUnitId() {
        return unitId;
    }

    public String getRemarks() {
        return remarks;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}

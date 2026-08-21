package in.nirman.modules.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of a passed bill's Abstract of Cost, as it stood the moment the bill was passed.
 *
 * <p>Written once at freeze and never recomputed. Open the 2nd RA bill next year and it shows
 * what was actually paid — not what today's measurements would now produce — which is the
 * whole reason a snapshot exists rather than a view.</p>
 */
@Entity
@Table(name = "ra_bill_items")
public class RaBillItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "ra_bill_id", nullable = false, updatable = false)
    private UUID raBillId;

    @Column(name = "boq_item_id", nullable = false, updatable = false)
    private UUID boqItemId;

    @Column(name = "item_number", nullable = false, length = 40)
    private String itemNumber;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "unit_id")
    private UUID unitId;

    @Column(name = "contract_quantity", nullable = false, precision = 18, scale = 4)
    private BigDecimal contractQuantity = BigDecimal.ZERO;

    @Column(name = "qty_since_previous", nullable = false, precision = 18, scale = 4)
    private BigDecimal qtySincePrevious = BigDecimal.ZERO;

    @Column(name = "qty_to_date", nullable = false, precision = 18, scale = 4)
    private BigDecimal qtyToDate = BigDecimal.ZERO;

    @Column(name = "rate", nullable = false, precision = 18, scale = 4)
    private BigDecimal rate = BigDecimal.ZERO;

    @Column(name = "amount_to_date", nullable = false, precision = 18, scale = 2)
    private BigDecimal amountToDate = BigDecimal.ZERO;

    @Column(name = "amount_previous", nullable = false, precision = 18, scale = 2)
    private BigDecimal amountPrevious = BigDecimal.ZERO;

    @Column(name = "amount_since", nullable = false, precision = 18, scale = 2)
    private BigDecimal amountSince = BigDecimal.ZERO;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected RaBillItem() {
    }

    public RaBillItem(UUID raBillId, UUID boqItemId, String itemNumber, String description,
                      UUID unitId, BigDecimal contractQuantity, BigDecimal qtySincePrevious,
                      BigDecimal qtyToDate, BigDecimal rate, BigDecimal amountToDate,
                      BigDecimal amountPrevious, BigDecimal amountSince, int sortOrder) {
        this.id = UUID.randomUUID();
        this.raBillId = raBillId;
        this.boqItemId = boqItemId;
        this.itemNumber = itemNumber;
        this.description = description;
        this.unitId = unitId;
        this.contractQuantity = contractQuantity;
        this.qtySincePrevious = qtySincePrevious;
        this.qtyToDate = qtyToDate;
        this.rate = rate;
        this.amountToDate = amountToDate;
        this.amountPrevious = amountPrevious;
        this.amountSince = amountSince;
        this.sortOrder = sortOrder;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRaBillId() {
        return raBillId;
    }

    public UUID getBoqItemId() {
        return boqItemId;
    }

    public String getItemNumber() {
        return itemNumber;
    }

    public String getDescription() {
        return description;
    }

    public UUID getUnitId() {
        return unitId;
    }

    public BigDecimal getContractQuantity() {
        return contractQuantity;
    }

    public BigDecimal getQtySincePrevious() {
        return qtySincePrevious;
    }

    public BigDecimal getQtyToDate() {
        return qtyToDate;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public BigDecimal getAmountToDate() {
        return amountToDate;
    }

    public BigDecimal getAmountPrevious() {
        return amountPrevious;
    }

    public BigDecimal getAmountSince() {
        return amountSince;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}

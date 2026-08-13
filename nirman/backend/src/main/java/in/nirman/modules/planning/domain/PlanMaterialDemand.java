package in.nirman.modules.planning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One material in one month, as two quantities answering two questions.
 *
 * <p>{@code requiredQty} is what the month consumes. {@code procureQty} is that same material
 * moved back by its lead time and buffer — what has to be ordered now so a later month can
 * happen. Conflating them is how a site runs out of cement while the plan says it has plenty.</p>
 */
@Entity
@Table(name = "plan_material_demand")
public class PlanMaterialDemand {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;
    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;
    @Column(name = "year_month", nullable = false, length = 7)
    private String yearMonth;
    @Column(name = "material_code", nullable = false, length = 40)
    private String materialCode;
    @Column(name = "material_name", length = 200)
    private String materialName;
    @Column(name = "unit_code", length = 20)
    private String unitCode;
    @Column(name = "required_qty", nullable = false, precision = 18, scale = 3)
    private BigDecimal requiredQty = BigDecimal.ZERO;
    @Column(name = "procure_qty", nullable = false, precision = 18, scale = 3)
    private BigDecimal procureQty = BigDecimal.ZERO;
    @Column(name = "procure_value", precision = 18, scale = 2)
    private BigDecimal procureValue;
    @Column(name = "order_by_date")
    private LocalDate orderByDate;

    protected PlanMaterialDemand() {
    }

    public PlanMaterialDemand(UUID planId, String yearMonth, String materialCode,
                              String materialName, String unitCode, BigDecimal requiredQty,
                              BigDecimal procureQty, BigDecimal procureValue,
                              LocalDate orderByDate) {
        this.id = UUID.randomUUID();
        this.planId = planId;
        this.yearMonth = yearMonth;
        this.materialCode = materialCode;
        this.materialName = materialName;
        this.unitCode = unitCode;
        this.requiredQty = requiredQty;
        this.procureQty = procureQty;
        this.procureValue = procureValue;
        this.orderByDate = orderByDate;
    }

    public UUID getId() { return id; }
    public UUID getPlanId() { return planId; }
    public String getYearMonth() { return yearMonth; }
    public String getMaterialCode() { return materialCode; }
    public String getMaterialName() { return materialName; }
    public String getUnitCode() { return unitCode; }
    public BigDecimal getRequiredQty() { return requiredQty; }
    public BigDecimal getProcureQty() { return procureQty; }
    public BigDecimal getProcureValue() { return procureValue; }
    public LocalDate getOrderByDate() { return orderByDate; }
}

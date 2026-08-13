package in.nirman.modules.planning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One month of money.
 *
 * <p>{@code netReceived} is what actually arrives, after retention and the statutory deductions
 * and after the payment lag, and it is what funds the next phase. The gap between it and
 * {@code grossBilled} is where optimistic plans die. {@code cumulative} is the running total,
 * and its lowest point is the answer to "how much money do we need to start".</p>
 */
@Entity
@Table(name = "plan_cash_flow")
public class PlanCashFlow {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;
    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;
    @Column(name = "year_month", nullable = false, length = 7)
    private String yearMonth;
    @Column(name = "labour_cost", nullable = false, precision = 18, scale = 2)
    private BigDecimal labourCost = BigDecimal.ZERO;
    @Column(name = "material_cost", nullable = false, precision = 18, scale = 2)
    private BigDecimal materialCost = BigDecimal.ZERO;
    @Column(name = "staff_cost", nullable = false, precision = 18, scale = 2)
    private BigDecimal staffCost = BigDecimal.ZERO;
    @Column(name = "plant_transport", nullable = false, precision = 18, scale = 2)
    private BigDecimal plantTransport = BigDecimal.ZERO;
    @Column(name = "setup_cost", nullable = false, precision = 18, scale = 2)
    private BigDecimal setupCost = BigDecimal.ZERO;
    @Column(name = "overhead_cost", nullable = false, precision = 18, scale = 2)
    private BigDecimal overheadCost = BigDecimal.ZERO;
    @Column(name = "total_outflow", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalOutflow = BigDecimal.ZERO;
    @Column(name = "gross_billed", nullable = false, precision = 18, scale = 2)
    private BigDecimal grossBilled = BigDecimal.ZERO;
    @Column(name = "deductions", nullable = false, precision = 18, scale = 2)
    private BigDecimal deductions = BigDecimal.ZERO;
    @Column(name = "net_received", nullable = false, precision = 18, scale = 2)
    private BigDecimal netReceived = BigDecimal.ZERO;
    @Column(name = "net_movement", nullable = false, precision = 18, scale = 2)
    private BigDecimal netMovement = BigDecimal.ZERO;
    @Column(name = "cumulative", nullable = false, precision = 18, scale = 2)
    private BigDecimal cumulative = BigDecimal.ZERO;

    protected PlanCashFlow() {
    }

    public PlanCashFlow(UUID planId, String yearMonth, BigDecimal labourCost,
                        BigDecimal materialCost, BigDecimal staffCost, BigDecimal plantTransport,
                        BigDecimal setupCost, BigDecimal overheadCost, BigDecimal totalOutflow,
                        BigDecimal grossBilled, BigDecimal deductions, BigDecimal netReceived,
                        BigDecimal netMovement, BigDecimal cumulative) {
        this.id = UUID.randomUUID();
        this.planId = planId;
        this.yearMonth = yearMonth;
        this.labourCost = labourCost;
        this.materialCost = materialCost;
        this.staffCost = staffCost;
        this.plantTransport = plantTransport;
        this.setupCost = setupCost;
        this.overheadCost = overheadCost;
        this.totalOutflow = totalOutflow;
        this.grossBilled = grossBilled;
        this.deductions = deductions;
        this.netReceived = netReceived;
        this.netMovement = netMovement;
        this.cumulative = cumulative;
    }

    public UUID getId() { return id; }
    public UUID getPlanId() { return planId; }
    public String getYearMonth() { return yearMonth; }
    public BigDecimal getLabourCost() { return labourCost; }
    public BigDecimal getMaterialCost() { return materialCost; }
    public BigDecimal getStaffCost() { return staffCost; }
    public BigDecimal getPlantTransport() { return plantTransport; }
    public BigDecimal getSetupCost() { return setupCost; }
    public BigDecimal getOverheadCost() { return overheadCost; }
    public BigDecimal getTotalOutflow() { return totalOutflow; }
    public BigDecimal getGrossBilled() { return grossBilled; }
    public BigDecimal getDeductions() { return deductions; }
    public BigDecimal getNetReceived() { return netReceived; }
    public BigDecimal getNetMovement() { return netMovement; }
    public BigDecimal getCumulative() { return cumulative; }
}

package in.nirman.modules.planning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One window between milestones, as frozen.
 *
 * <p>The description is the tender's own words. Where the milestone is physical those words name
 * the activities the department expects finished, and they are what a submission prints — so
 * they are stored verbatim rather than summarised into a percentage.</p>
 */
@Entity
@Table(name = "plan_phases")
public class PlanPhase {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;
    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;
    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;
    @Column(name = "description")
    private String description;
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;
    @Column(name = "target_percent", precision = 6, scale = 3)
    private BigDecimal targetPercent;
    @Column(name = "planned_value", precision = 18, scale = 2)
    private BigDecimal plannedValue;
    @Column(name = "planned_percent", precision = 6, scale = 3)
    private BigDecimal plannedPercent;
    @Column(name = "withheld_percent", precision = 6, scale = 3)
    private BigDecimal withheldPercent;
    @Column(name = "physical", nullable = false)
    private boolean physical;
    @Column(name = "on_target", nullable = false)
    private boolean onTarget = true;

    protected PlanPhase() {
    }

    public PlanPhase(UUID planId, int sequenceNo, String description, LocalDate startDate,
                     LocalDate endDate, BigDecimal targetPercent, BigDecimal plannedValue,
                     BigDecimal plannedPercent, BigDecimal withheldPercent, boolean physical,
                     boolean onTarget) {
        this.id = UUID.randomUUID();
        this.planId = planId;
        this.sequenceNo = sequenceNo;
        this.description = description;
        this.startDate = startDate;
        this.endDate = endDate;
        this.targetPercent = targetPercent;
        this.plannedValue = plannedValue;
        this.plannedPercent = plannedPercent;
        this.withheldPercent = withheldPercent;
        this.physical = physical;
        this.onTarget = onTarget;
    }

    public UUID getId() { return id; }
    public UUID getPlanId() { return planId; }
    public int getSequenceNo() { return sequenceNo; }
    public String getDescription() { return description; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public BigDecimal getTargetPercent() { return targetPercent; }
    public BigDecimal getPlannedValue() { return plannedValue; }
    public BigDecimal getPlannedPercent() { return plannedPercent; }
    public BigDecimal getWithheldPercent() { return withheldPercent; }
    public boolean isPhysical() { return physical; }
    public boolean isOnTarget() { return onTarget; }
}

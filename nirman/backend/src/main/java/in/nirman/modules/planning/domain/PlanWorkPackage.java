package in.nirman.modules.planning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One trade category on one side of a composite contract, with its dates and its crew. */
@Entity
@Table(name = "plan_work_packages")
public class PlanWorkPackage {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;
    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;
    @Column(name = "work_category", nullable = false, length = 80)
    private String workCategory;
    @Column(name = "work_part", length = 40)
    private String workPart;
    @Column(name = "value", nullable = false, precision = 18, scale = 2)
    private BigDecimal value = BigDecimal.ZERO;
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;
    @Column(name = "gangs", nullable = false)
    private int gangs = 1;
    @Column(name = "line_count", nullable = false)
    private int lineCount;
    /** False where no productivity norm matched: money, but no men or dates to act on. */
    @Column(name = "normed", nullable = false)
    private boolean normed = true;

    protected PlanWorkPackage() {
    }

    public PlanWorkPackage(UUID planId, String workCategory, String workPart, BigDecimal value,
                           LocalDate startDate, LocalDate endDate, int gangs, int lineCount,
                           boolean normed) {
        this.id = UUID.randomUUID();
        this.planId = planId;
        this.workCategory = workCategory;
        this.workPart = workPart;
        this.value = value;
        this.startDate = startDate;
        this.endDate = endDate;
        this.gangs = gangs;
        this.lineCount = lineCount;
        this.normed = normed;
    }

    public UUID getId() { return id; }
    public UUID getPlanId() { return planId; }
    public String getWorkCategory() { return workCategory; }
    public String getWorkPart() { return workPart; }
    public BigDecimal getValue() { return value; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public int getGangs() { return gangs; }
    public int getLineCount() { return lineCount; }
    public boolean isNormed() { return normed; }
}

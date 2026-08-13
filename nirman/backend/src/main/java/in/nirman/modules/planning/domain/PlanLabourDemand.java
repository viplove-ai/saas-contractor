package in.nirman.modules.planning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One trade's demand in one month.
 *
 * <p>{@code headCount} is man-days over the month's working days, which is the figure a
 * supervisor can act on; {@code manDays} is the one that adds up. Both are kept because a plan
 * that stored only the total would be re-divided by hand on every screen that showed it.</p>
 */
@Entity
@Table(name = "plan_labour_demand")
public class PlanLabourDemand {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;
    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;
    @Column(name = "year_month", nullable = false, length = 7)
    private String yearMonth;
    @Column(name = "skill_code", nullable = false, length = 40)
    private String skillCode;
    @Column(name = "skilled", nullable = false)
    private boolean skilled = true;
    @Column(name = "man_days", nullable = false, precision = 18, scale = 3)
    private BigDecimal manDays = BigDecimal.ZERO;
    @Column(name = "head_count", nullable = false, precision = 10, scale = 1)
    private BigDecimal headCount = BigDecimal.ZERO;
    /** Null where the trade is unpriced, which is not the same as free. */
    @Column(name = "cost", precision = 18, scale = 2)
    private BigDecimal cost;

    protected PlanLabourDemand() {
    }

    public PlanLabourDemand(UUID planId, String yearMonth, String skillCode, boolean skilled,
                            BigDecimal manDays, BigDecimal headCount, BigDecimal cost) {
        this.id = UUID.randomUUID();
        this.planId = planId;
        this.yearMonth = yearMonth;
        this.skillCode = skillCode;
        this.skilled = skilled;
        this.manDays = manDays;
        this.headCount = headCount;
        this.cost = cost;
    }

    public UUID getId() { return id; }
    public UUID getPlanId() { return planId; }
    public String getYearMonth() { return yearMonth; }
    public String getSkillCode() { return skillCode; }
    public boolean isSkilled() { return skilled; }
    public BigDecimal getManDays() { return manDays; }
    public BigDecimal getHeadCount() { return headCount; }
    public BigDecimal getCost() { return cost; }
}

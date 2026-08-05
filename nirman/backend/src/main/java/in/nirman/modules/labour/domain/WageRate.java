package in.nirman.modules.labour.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One period of one worker's pay. Rates are never overwritten: a revision closes the open
 * row and opens a new one, so the rate that applied on any past date stays recoverable.
 *
 * <p>That history is what makes last month's labour cost immovable. Attendance additionally
 * snapshots the rate onto the record at verification, so even this table changing shape
 * later cannot rewrite a settled month.</p>
 *
 * <p>{@code normalRate} is per day, hour or month according to the worker's
 * {@link WageType}; {@code overtimeRate} is always per hour.</p>
 */
@Entity
@Table(name = "wage_rates")
public class WageRate extends BaseEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "worker_id", nullable = false, updatable = false)
    private UUID workerId;

    @Column(name = "normal_rate", nullable = false, precision = 18, scale = 4)
    private BigDecimal normalRate;

    @Column(name = "overtime_rate", nullable = false, precision = 18, scale = 4)
    private BigDecimal overtimeRate;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** Null means this is the rate currently in force; the schema allows one open row per worker. */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "remarks")
    private String remarks;

    protected WageRate() {
    }

    public WageRate(UUID orgId, UUID workerId, BigDecimal normalRate, BigDecimal overtimeRate,
                    LocalDate effectiveFrom) {
        this.orgId = orgId;
        this.workerId = workerId;
        this.normalRate = normalRate;
        this.overtimeRate = overtimeRate;
        this.effectiveFrom = effectiveFrom;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getWorkerId() {
        return workerId;
    }

    public BigDecimal getNormalRate() {
        return normalRate;
    }

    public BigDecimal getOvertimeRate() {
        return overtimeRate;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public boolean isOpen() {
        return effectiveTo == null;
    }

    public boolean appliesOn(LocalDate date) {
        return !effectiveFrom.isAfter(date) && (effectiveTo == null || !effectiveTo.isBefore(date));
    }

    /** Closes this period the day before the successor takes effect. */
    public void closeOn(LocalDate lastDay) {
        this.effectiveTo = lastDay;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }
}

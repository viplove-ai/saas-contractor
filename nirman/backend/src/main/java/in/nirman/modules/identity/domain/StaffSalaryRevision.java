package in.nirman.modules.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What a member is paid a month, from a date.
 *
 * <p>Append-only, and no setters at all. A raise in April must not rewrite what March cost —
 * the same rule that makes attendance freeze its wage rate at verification, applied to the
 * salaried half of the payroll. A revision that replaced the last one would quietly restate
 * every month before it.</p>
 *
 * <p>Which figure applies on a date is the newest row whose {@code effectiveFrom} is not
 * after it. Nothing carries an end date: the next revision is the end of the one before,
 * and a stored end is a second version of that fact.</p>
 */
@Entity
@Table(name = "staff_salary_revisions")
@EntityListeners(AuditingEntityListener.class)
public class StaffSalaryRevision {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "monthly_amount", nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal monthlyAmount;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    @Column(name = "reason", nullable = false, length = 300, updatable = false)
    private String reason;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected StaffSalaryRevision() {
    }

    public StaffSalaryRevision(UUID orgId, UUID userId, BigDecimal monthlyAmount,
                               LocalDate effectiveFrom, String reason) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.userId = userId;
        this.monthlyAmount = monthlyAmount;
        this.effectiveFrom = effectiveFrom;
        this.reason = reason;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getUserId() {
        return userId;
    }

    public BigDecimal getMonthlyAmount() {
        return monthlyAmount;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }
}

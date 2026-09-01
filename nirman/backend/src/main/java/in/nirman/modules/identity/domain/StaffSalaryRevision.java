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

    /*
     * ------------------------------------------------------------------ what it is made of
     *
     * A salary in India is not one number, because the law does not treat it as one: the
     * provident fund is computed on basic and dearness allowance, the state insurance on the
     * whole of what is paid, and the Code on Wages then overrules both by counting the excess
     * as wages wherever the allowances have been let run past half the packet. A single gross
     * cannot answer any of those, so the office answered them in a spreadsheet and this table
     * held a figure that agreed with the payslip by luck.
     *
     * The structure lives here rather than in a table of its own because this row is *already*
     * the append-only record of what applies from when. A second effective-dated table beside
     * it would be two answers to one question, disagreeing the first time somebody edited one.
     *
     * Nullable, and rows written before the structure existed are not wrong — they are older.
     * A revision with no basic is still the true answer to what somebody was paid in March.
     * What it cannot do is produce a payslip, and the payroll service says so in a sentence
     * rather than inventing a split nobody decided.
     */

    @Column(name = "basic", precision = 14, scale = 2, updatable = false)
    private BigDecimal basic;

    @Column(name = "dearness_allowance", precision = 14, scale = 2, updatable = false)
    private BigDecimal dearnessAllowance;

    @Column(name = "hra", precision = 14, scale = 2, updatable = false)
    private BigDecimal hra;

    @Column(name = "conveyance", precision = 14, scale = 2, updatable = false)
    private BigDecimal conveyance;

    @Column(name = "other_allowance", precision = 14, scale = 2, updatable = false)
    private BigDecimal otherAllowance;

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

    /**
     * The same revision with its parts named.
     *
     * <p>{@code monthlyAmount} is derived from the components rather than passed alongside
     * them, because a gross typed beside a breakdown is a gross that can disagree with it,
     * and the check constraint under this row would then refuse the save with a message
     * about arithmetic that the person typing cannot act on.</p>
     */
    public StaffSalaryRevision(UUID orgId, UUID userId, BigDecimal basic,
                               BigDecimal dearnessAllowance, BigDecimal hra,
                               BigDecimal conveyance, BigDecimal otherAllowance,
                               LocalDate effectiveFrom, String reason) {
        this(orgId, userId,
                zero(basic).add(zero(dearnessAllowance)).add(zero(hra))
                        .add(zero(conveyance)).add(zero(otherAllowance)),
                effectiveFrom, reason);
        this.basic = zero(basic);
        this.dearnessAllowance = zero(dearnessAllowance);
        this.hra = zero(hra);
        this.conveyance = zero(conveyance);
        this.otherAllowance = zero(otherAllowance);
    }

    /**
     * Whether this row can produce a payslip.
     *
     * <p>The distinction the payroll screen turns on: a member with no revision at all has
     * never had a salary recorded, and one whose newest revision answers false here has a
     * gross from before the structure existed. Two different sentences to two different
     * people, and neither is "0.00".</p>
     */
    public boolean isStructured() {
        return basic != null;
    }

    /**
     * The wage the statutes work on, for the whole month: basic and dearness allowance,
     * lifted to half the packet where the allowances have run past that.
     *
     * <p>The Code on Wages proviso, written as a maximum. It is here rather than only in the
     * payslip because it is a fact about the <em>structure</em> — it is what tells an office
     * writing a new one whether the split it has chosen is doing what it thinks.</p>
     */
    public BigDecimal statutoryWages() {
        if (!isStructured()) {
            return null;
        }
        BigDecimal included = basic.add(zero(dearnessAllowance));
        BigDecimal half = monthlyAmount.multiply(new BigDecimal("0.50"))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        return included.max(half);
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
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

    public BigDecimal getBasic() {
        return basic;
    }

    public BigDecimal getDearnessAllowance() {
        return dearnessAllowance;
    }

    public BigDecimal getHra() {
        return hra;
    }

    public BigDecimal getConveyance() {
        return conveyance;
    }

    public BigDecimal getOtherAllowance() {
        return otherAllowance;
    }
}

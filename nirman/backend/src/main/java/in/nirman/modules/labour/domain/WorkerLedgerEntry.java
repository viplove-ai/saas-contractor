package in.nirman.modules.labour.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One movement in what the firm owes a worker. Append-only, exactly like the stock ledger:
 * nobody types a balance, because a balance is what he earned less what he has drawn.
 *
 * <p>{@code amount} is always positive and {@code direction} carries the sign — +1 increases
 * what we owe him, −1 reduces it. Splitting them that way means a report can sum earnings
 * and drawings separately without re-deriving which is which from a signed number.</p>
 *
 * <p>No update path and no {@code @Version}: a wrong entry is corrected by posting an
 * {@code ADJUSTMENT} against it, which the schema requires a reason for.</p>
 */
@Entity
@Table(name = "worker_ledger_entries")
@EntityListeners(AuditingEntityListener.class)
public class WorkerLedgerEntry {

    public enum EntryType {
        /** Regular wage from a verified attendance row. */
        WAGE_EARNED,
        /** Overtime from the same row, kept separate so overtime cost stays visible. */
        OT_EARNED,
        /** Cash or goods handed over during the period, against wages. */
        ADVANCE,
        /** Settlement of the balance. */
        PAYMENT,
        DEDUCTION,
        ADJUSTMENT,
        OPENING
    }

    /** What produced the entry, so it can be traced back and never posted twice. */
    public enum SourceType { ATTENDANCE, WORKER_ADVANCE, PAYMENT, MANUAL }

    public static final short INCREASE = 1;
    public static final short DECREASE = -1;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private UUID siteId;

    @Column(name = "worker_id", nullable = false, updatable = false)
    private UUID workerId;

    @Column(name = "entry_date", nullable = false, updatable = false)
    private LocalDate entryDate;

    /** YYYY-MM. The settlement period the entry falls into. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "period_year_month", columnDefinition = "char(7)", nullable = false, updatable = false)
    private String periodYearMonth;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20, updatable = false)
    private EntryType entryType;

    @Column(name = "direction", nullable = false, updatable = false)
    private short direction;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "balance_after", precision = 18, scale = 2)
    private BigDecimal balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30, updatable = false)
    private SourceType sourceType;

    @Column(name = "source_id", updatable = false)
    private UUID sourceId;

    @Column(name = "reason", updatable = false)
    private String reason;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected WorkerLedgerEntry() {
    }

    public WorkerLedgerEntry(UUID orgId, UUID projectId, UUID siteId, UUID workerId,
                             LocalDate entryDate, EntryType entryType, short direction,
                             BigDecimal amount, SourceType sourceType, UUID sourceId, String reason) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("ledger amount must be positive; direction carries the sign");
        }
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.projectId = projectId;
        this.siteId = siteId;
        this.workerId = workerId;
        this.entryDate = entryDate;
        this.periodYearMonth = entryDate.toString().substring(0, 7);
        this.entryType = entryType;
        this.direction = direction;
        this.amount = amount;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.reason = reason;
    }

    /** The amount with its sign applied — what a running balance actually adds. */
    public BigDecimal signedAmount() {
        return direction == DECREASE ? amount.negate() : amount;
    }

    public void setBalanceAfter(BigDecimal balanceAfter) {
        this.balanceAfter = balanceAfter;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getSiteId() {
        return siteId;
    }

    public UUID getWorkerId() {
        return workerId;
    }

    public LocalDate getEntryDate() {
        return entryDate;
    }

    public String getPeriodYearMonth() {
        return periodYearMonth;
    }

    public EntryType getEntryType() {
        return entryType;
    }

    public short getDirection() {
        return direction;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

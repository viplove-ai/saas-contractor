package in.nirman.modules.labour.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What a worker is owed right now, kept as a running total so a settlement screen does not
 * have to sum his whole history on every load.
 *
 * <p>A cache, not the truth. {@code worker_ledger_entries} is the truth; this row is written
 * inside the same transaction under a row lock, and if the two ever disagree the ledger
 * wins and the difference is a data-quality alert.</p>
 *
 * <p>The identity that has to hold, and the one the field sheet computes by hand:
 * <b>earned − advance − paid − deduction = net payable</b>.</p>
 */
@Entity
@Table(name = "worker_balances")
public class WorkerBalance {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "worker_id", nullable = false, unique = true, updatable = false)
    private UUID workerId;

    @Column(name = "earned_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal earnedAmount = BigDecimal.ZERO;

    @Column(name = "advance_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal advanceAmount = BigDecimal.ZERO;

    @Column(name = "paid_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(name = "deduction_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal deductionAmount = BigDecimal.ZERO;

    @Column(name = "net_payable", nullable = false, precision = 18, scale = 2)
    private BigDecimal netPayable = BigDecimal.ZERO;

    @Column(name = "last_entry_at")
    private Instant lastEntryAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected WorkerBalance() {
    }

    public WorkerBalance(UUID orgId, UUID workerId) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.workerId = workerId;
    }

    /** Applies one ledger entry to the running totals. Called only from the ledger service. */
    public void apply(WorkerLedgerEntry entry) {
        switch (entry.getEntryType()) {
            case WAGE_EARNED, OT_EARNED -> earnedAmount = earnedAmount.add(entry.getAmount());
            case ADVANCE -> advanceAmount = advanceAmount.add(entry.getAmount());
            case PAYMENT -> paidAmount = paidAmount.add(entry.getAmount());
            case DEDUCTION -> deductionAmount = deductionAmount.add(entry.getAmount());
            // An adjustment can go either way, so it moves earnings by its signed amount.
            case ADJUSTMENT, OPENING -> earnedAmount = earnedAmount.add(entry.signedAmount());
        }
        this.netPayable = earnedAmount.subtract(advanceAmount)
                .subtract(paidAmount)
                .subtract(deductionAmount);
        this.lastEntryAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getWorkerId() {
        return workerId;
    }

    public BigDecimal getEarnedAmount() {
        return earnedAmount;
    }

    public BigDecimal getAdvanceAmount() {
        return advanceAmount;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public BigDecimal getDeductionAmount() {
        return deductionAmount;
    }

    public BigDecimal getNetPayable() {
        return netPayable;
    }

    public Instant getLastEntryAt() {
        return lastEntryAt;
    }

    public Long getVersion() {
        return version;
    }
}

package in.nirman.modules.treasury.domain;

import in.nirman.common.BaseEntity;
import in.nirman.common.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One fixed deposit the company holds, whatever it happens to be pledged against.
 *
 * <p>{@link ProjectSecurity} is the contract's side of the story — what a department is holding
 * and when it comes back. This is the bank's side, and it outlives any one contract: an FDR
 * bought to bid on a tender that was refused is still the company's money and still matures in
 * October. The register exists so that question has an answer without opening every contract in
 * turn.</p>
 *
 * <p><b>Two states only.</b> The company holds it, or the bank has paid it out. Whether it is
 * pledged is not a state here: that is true exactly when a live security points at this row,
 * and a stored copy of it would be the version that goes stale — showing an FDR as committed
 * months after the department returned it. See the V42 header.</p>
 */
@Entity
@Table(name = "bank_deposits")
public class BankDeposit extends BaseEntity {

    /**
     * HELD covers pledged and idle alike, because the difference is the pledge's to state.
     * CLOSED is the end: the bank has paid out and there is nothing left to pledge.
     */
    public enum Status { HELD, CLOSED }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "deposit_number", nullable = false, length = 80)
    private String depositNumber;

    @Column(name = "bank_name", nullable = false, length = 160)
    private String bankName;

    @Column(name = "branch", length = 160)
    private String branch;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "issued_on", nullable = false)
    private LocalDate issuedOn;

    /** Null until somebody reads it off the certificate. A guess would misplace a renewal. */
    @Column(name = "maturity_on")
    private LocalDate maturityOn;

    @Column(name = "interest_rate", precision = 6, scale = 3)
    private BigDecimal interestRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.HELD;

    @Column(name = "closed_on")
    private LocalDate closedOn;

    @Column(name = "closed_reason", length = 500)
    private String closedReason;

    @Column(name = "notes")
    private String notes;

    protected BankDeposit() {
    }

    public BankDeposit(UUID orgId, String depositNumber, String bankName, BigDecimal amount,
                       LocalDate issuedOn) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("a deposit must be for a positive amount");
        }
        this.orgId = orgId;
        this.depositNumber = depositNumber;
        this.bankName = bankName;
        this.amount = amount;
        this.issuedOn = issuedOn;
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * The bank has paid it out.
     *
     * <p>Refused where the certificate is still pledged to a contract, and that check belongs to
     * the service rather than here: this row cannot see the securities pointing at it. Closing
     * one that is pledged would leave a department holding a deposit the register says is
     * gone.</p>
     */
    public void close(LocalDate on, String reason) {
        if (status == Status.CLOSED) {
            throw new BusinessException("deposit.already-closed",
                    "%s was already closed on %s.".formatted(depositNumber, closedOn));
        }
        if (on == null) {
            throw new BusinessException("deposit.close-date",
                    "A closed deposit is closed on a day. Enter the date the bank paid it out.");
        }
        if (on.isBefore(issuedOn)) {
            throw new BusinessException("deposit.close-before-issue",
                    "A deposit cannot be closed before it was issued.");
        }
        this.status = Status.CLOSED;
        this.closedOn = on;
        this.closedReason = reason;
    }

    /** Puts a mistakenly closed deposit back in the register, with its reason cleared. */
    public void reopen() {
        if (status != Status.CLOSED) {
            throw new BusinessException("deposit.not-closed",
                    "%s is not closed.".formatted(depositNumber));
        }
        this.status = Status.HELD;
        this.closedOn = null;
        this.closedReason = null;
    }

    /**
     * Corrects what the certificate says. The amount is amendable because it is transcribed off
     * paper and a transcription is wrong sometimes; what it cannot become is nothing.
     */
    public void amend(String depositNumber, String bankName, String branch, BigDecimal amount,
                      LocalDate issuedOn, LocalDate maturityOn, BigDecimal interestRate,
                      String notes) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("deposit.amount",
                    "A deposit is for a positive amount. Close it instead of zeroing it.");
        }
        if (maturityOn != null && issuedOn != null && maturityOn.isBefore(issuedOn)) {
            throw new BusinessException("deposit.maturity",
                    "A deposit cannot mature before it was issued.");
        }
        this.depositNumber = depositNumber;
        this.bankName = bankName;
        this.branch = branch;
        this.amount = amount;
        this.issuedOn = issuedOn;
        this.maturityOn = maturityOn;
        this.interestRate = interestRate;
        this.notes = notes;
    }

    public boolean isClosed() {
        return status == Status.CLOSED;
    }

    // ------------------------------------------------------------------ accessors

    public UUID getOrgId() {
        return orgId;
    }

    public String getDepositNumber() {
        return depositNumber;
    }

    public String getBankName() {
        return bankName;
    }

    public String getBranch() {
        return branch;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getIssuedOn() {
        return issuedOn;
    }

    public LocalDate getMaturityOn() {
        return maturityOn;
    }

    public BigDecimal getInterestRate() {
        return interestRate;
    }

    public Status getStatus() {
        return status;
    }

    public LocalDate getClosedOn() {
        return closedOn;
    }

    public String getClosedReason() {
        return closedReason;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}

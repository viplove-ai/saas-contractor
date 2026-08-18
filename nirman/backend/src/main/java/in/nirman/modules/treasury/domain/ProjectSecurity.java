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
 * One parcel of the company's money held by a department, and the dates it comes back on.
 *
 * <p>Four kinds, and the difference between them is not cosmetic. Earnest money and the two
 * guarantees are <em>lodged</em> — a fixed deposit bought with the contractor's own cash,
 * before a rupee of the work is billable. A security deposit is <em>withheld</em> — money
 * deducted from bills that never reached him at all. Summing the two without saying which is
 * which reports a bank balance the company never had, which is why {@link #isLodged()} exists
 * and why the dashboard prints both halves.</p>
 */
@Entity
@Table(name = "project_securities")
public class ProjectSecurity extends BaseEntity {

    /** The four things a department holds, in the order they fall due on a contract. */
    public enum Type {
        /** Earnest money — placed to bid, released once the allotment letter is issued. */
        EMD,
        /** 5% of the estimate or the contract, whichever is higher. Never of the bid alone. */
        PERFORMANCE_GUARANTEE,
        /** What a bid below the threshold adds on top. See V33 for the rule. */
        ADDITIONAL_PG,
        /** 2.5% withheld from each running account bill, released after defect liability. */
        SECURITY_DEPOSIT;

        /**
         * Whether this parcel was paid out of the contractor's pocket.
         *
         * <p>False for the retention alone: it was never received, so it cannot be released
         * back into a bank account the way an FDR can, and it can never fund the next
         * tender.</p>
         */
        public boolean isLodgedFromOwnFunds() {
            return this != SECURITY_DEPOSIT;
        }
    }

    /** What the money is sitting in. */
    public enum Instrument { FDR, BANK_GUARANTEE, DD, CASH, BILL_RETENTION }

    /**
     * DUE — known to be required and not yet placed. That is the state worth alarming on: a
     * performance guarantee not lodged is a gate on being paid at all, not a late formality.
     */
    public enum Status { DUE, LODGED, RELEASED, FORFEITED }

    /** How far ahead a maturing deposit is flagged for renewal. See {@link #needsRenewalBy}. */
    private static final int RENEWAL_WARNING_DAYS = 90;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "security_type", nullable = false, length = 24, updatable = false)
    private Type securityType;

    @Enumerated(EnumType.STRING)
    @Column(name = "instrument", nullable = false, length = 20)
    private Instrument instrument;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.DUE;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    /** What is actually with them today. Equals {@link #amount} for a lodged instrument. */
    @Column(name = "held_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal heldAmount = BigDecimal.ZERO;

    @Column(name = "basis", length = 500)
    private String basis;

    @Column(name = "reference_no", length = 80)
    private String referenceNo;

    @Column(name = "bank_name", length = 160)
    private String bankName;

    @Column(name = "branch", length = 160)
    private String branch;

    @Column(name = "lodged_on")
    private LocalDate lodgedOn;

    @Column(name = "maturity_on")
    private LocalDate maturityOn;

    @Column(name = "expected_release_on")
    private LocalDate expectedReleaseOn;

    @Column(name = "released_on")
    private LocalDate releasedOn;

    @Column(name = "release_reference", length = 120)
    private String releaseReference;

    @Column(name = "redeployed_to_project_id")
    private UUID redeployedToProjectId;

    /**
     * The fixed deposit pledged against this security, where one is (V42).
     *
     * <p>Set when the deposit is lodged and never cleared afterwards — not even on release.
     * Clearing it would erase the one thread that ties an FDR's second contract to its first,
     * which is the whole reason the register exists. Whether the certificate is pledged
     * <em>today</em> is read off this row's status, not off the presence of the link.</p>
     */
    @Column(name = "bank_deposit_id")
    private UUID bankDepositId;

    @Column(name = "forfeited_reason", length = 500)
    private String forfeitedReason;

    @Column(name = "notes")
    private String notes;

    protected ProjectSecurity() {
    }

    public ProjectSecurity(UUID orgId, UUID projectId, Type securityType, Instrument instrument,
                           BigDecimal amount) {
        this.orgId = orgId;
        this.projectId = projectId;
        this.securityType = securityType;
        this.instrument = instrument;
        this.amount = amount;
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * The deposit has gone out. Sets the held amount to the whole of it, because that is what
     * buying a fixed deposit does — a retention is the one kind that arrives in instalments,
     * and it arrives through {@link #recordRetained}.
     */
    public void lodge(LocalDate on, String referenceNo, String bankName, String branch,
                      LocalDate maturityOn, UUID bankDepositId) {
        if (status != Status.DUE) {
            throw new BusinessException("security.not-due",
                    "This deposit is already " + status.name().toLowerCase() + ".");
        }
        this.status = Status.LODGED;
        this.lodgedOn = on;
        this.referenceNo = referenceNo;
        this.bankName = bankName;
        this.branch = branch;
        this.maturityOn = maturityOn;
        this.bankDepositId = bankDepositId;
        this.heldAmount = amount;
    }

    /**
     * Another bill has been passed and another slice withheld.
     *
     * <p>Absolute rather than incremental: the caller sends the running total the department
     * has deducted to date, because that is the figure on the bill summary he is reading off,
     * and an increment sent twice by a flaky connection would overstate what is held.</p>
     */
    public void recordRetained(BigDecimal retainedToDate, LocalDate asOf) {
        if (securityType != Type.SECURITY_DEPOSIT) {
            throw new BusinessException("security.not-a-retention",
                    "Only a security deposit accrues from bills. A guarantee is lodged whole.");
        }
        if (status == Status.RELEASED || status == Status.FORFEITED) {
            throw new BusinessException("security.settled",
                    "This deposit is already " + status.name().toLowerCase() + ".");
        }
        if (retainedToDate.compareTo(amount) > 0) {
            throw new BusinessException("security.over-retained",
                    "The department cannot have withheld more than the deposit is. Raise the "
                            + "deposit figure first if the contract value has changed.");
        }
        this.heldAmount = retainedToDate;
        if (retainedToDate.signum() > 0) {
            this.status = Status.LODGED;
            if (this.lodgedOn == null) {
                this.lodgedOn = asOf;
            }
        }
    }

    /** The money has come back. */
    public void release(LocalDate on, String reference) {
        if (status != Status.LODGED) {
            throw new BusinessException("security.not-lodged",
                    "Nothing is held against this deposit, so there is nothing to release.");
        }
        this.status = Status.RELEASED;
        this.releasedOn = on;
        this.releaseReference = reference;
        this.heldAmount = BigDecimal.ZERO;
    }

    /**
     * The released deposit's next home. A separate act from releasing it, and often weeks
     * later: the FDR comes back in March and funds a bid in May.
     */
    public void redeployTo(UUID projectId) {
        if (status != Status.RELEASED) {
            throw new BusinessException("security.not-released",
                    "A deposit cannot fund another tender until it has been released.");
        }
        if (!securityType.isLodgedFromOwnFunds()) {
            throw new BusinessException("security.retention-not-reusable",
                    "A security deposit was withheld from bills, never lodged, so releasing it "
                            + "pays a bill rather than funding a tender.");
        }
        this.redeployedToProjectId = projectId;
    }

    /** The department has kept it. Terminal, and needs a reason for the same reason a void does. */
    public void forfeit(String reason) {
        if (status == Status.RELEASED || status == Status.FORFEITED) {
            throw new BusinessException("security.settled",
                    "This deposit is already " + status.name().toLowerCase() + ".");
        }
        this.status = Status.FORFEITED;
        this.forfeitedReason = reason;
        this.heldAmount = BigDecimal.ZERO;
    }

    // ------------------------------------------------------------------ questions asked of a row

    /** Money the company put up itself and is still out of pocket for. */
    public boolean isLodged() {
        return status == Status.LODGED && securityType.isLodgedFromOwnFunds();
    }

    /** Money the department withheld from bills and is still holding. */
    public boolean isRetained() {
        return status == Status.LODGED && !securityType.isLodgedFromOwnFunds();
    }

    /** Due back on or before the given day, and nobody has released it. */
    public boolean isReleasableBy(LocalDate day) {
        return status == Status.LODGED && expectedReleaseOn != null
                && !expectedReleaseOn.isAfter(day);
    }

    /**
     * The deposit is about to stop existing while the guarantee it stands behind is still live.
     *
     * <p>Not the same alarm as an overdue release, and the more expensive one to miss: an FDR
     * that matures against a live guarantee has to be renewed, and a guarantee the bank has
     * quietly closed is a default the department finds first.</p>
     *
     * <p>It looks ahead a quarter rather than reporting the fact on the day it happens. An
     * alarm that fires the morning a deposit matures is an alarm about something that has
     * already gone wrong — the bank closed the deposit and the renewal is now a fresh
     * application. Ninety days is long enough to walk into a branch, and short enough that a
     * guarantee lodged against a three-year contract does not sit in the office's chase list
     * from the day it is bought.</p>
     */
    public boolean needsRenewalBy(LocalDate day) {
        return status == Status.LODGED && maturityOn != null
                && !maturityOn.isAfter(day.plusDays(RENEWAL_WARNING_DAYS))
                && (expectedReleaseOn == null || expectedReleaseOn.isAfter(maturityOn));
    }

    /** Released, and nothing named as its next home. This is what "free to re-use" means. */
    public boolean isFreeToReuse() {
        return status == Status.RELEASED && redeployedToProjectId == null
                && securityType.isLodgedFromOwnFunds();
    }

    // ------------------------------------------------------------------ accessors

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public Type getSecurityType() {
        return securityType;
    }

    public Instrument getInstrument() {
        return instrument;
    }

    public void setInstrument(Instrument instrument) {
        this.instrument = instrument;
    }

    public Status getStatus() {
        return status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getHeldAmount() {
        return heldAmount;
    }

    public String getBasis() {
        return basis;
    }

    public void setBasis(String basis) {
        this.basis = basis;
    }

    public String getReferenceNo() {
        return referenceNo;
    }

    public void setReferenceNo(String referenceNo) {
        this.referenceNo = referenceNo;
    }

    public String getBankName() {
        return bankName;
    }

    public void setBankName(String bankName) {
        this.bankName = bankName;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public LocalDate getLodgedOn() {
        return lodgedOn;
    }

    public void setLodgedOn(LocalDate lodgedOn) {
        this.lodgedOn = lodgedOn;
    }

    public LocalDate getMaturityOn() {
        return maturityOn;
    }

    public void setMaturityOn(LocalDate maturityOn) {
        this.maturityOn = maturityOn;
    }

    public LocalDate getExpectedReleaseOn() {
        return expectedReleaseOn;
    }

    public void setExpectedReleaseOn(LocalDate expectedReleaseOn) {
        this.expectedReleaseOn = expectedReleaseOn;
    }

    public LocalDate getReleasedOn() {
        return releasedOn;
    }

    public String getReleaseReference() {
        return releaseReference;
    }

    public UUID getBankDepositId() {
        return bankDepositId;
    }

    /** Links a certificate to a deposit already lodged, where the register was filled in after. */
    public void pledge(UUID bankDepositId) {
        this.bankDepositId = bankDepositId;
    }

    public UUID getRedeployedToProjectId() {
        return redeployedToProjectId;
    }

    public String getForfeitedReason() {
        return forfeitedReason;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}

package in.nirman.modules.expense.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A holder accounting for the float he was given: these are the bills, this is the cash back.
 *
 * <p>Nothing moves on the advance until the settlement is approved. Submitting one is a
 * claim — "here is what I spent" — and approving it is the office agreeing. Reducing the
 * float on submission would let a supervisor clear his own balance by asserting it.</p>
 *
 * <p>Like an expense, the status here is a cache written by the approval engine's event.</p>
 */
@Entity
@Table(name = "advance_settlements")
public class AdvanceSettlement extends BaseEntity {

    public enum Status { SUBMITTED, APPROVED, REJECTED }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "advance_id", nullable = false, updatable = false)
    private UUID advanceId;

    @Column(name = "settlement_number", nullable = false, length = 50, updatable = false)
    private String settlementNumber;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    /** The total of the expenses attached. Derived from the lines, never typed. */
    @Column(name = "expenses_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal expensesAmount = BigDecimal.ZERO;

    @Column(name = "returned_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal returnedAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.SUBMITTED;

    @Column(name = "submitted_by")
    private UUID submittedBy;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "remarks")
    private String remarks;

    protected AdvanceSettlement() {
    }

    public AdvanceSettlement(UUID orgId, UUID advanceId, String settlementNumber,
                             LocalDate settlementDate, BigDecimal expensesAmount,
                             BigDecimal returnedAmount, UUID submittedBy) {
        this.orgId = orgId;
        this.advanceId = advanceId;
        this.settlementNumber = settlementNumber;
        this.settlementDate = settlementDate;
        this.expensesAmount = expensesAmount;
        this.returnedAmount = returnedAmount;
        this.submittedBy = submittedBy;
    }

    /**
     * Written by {@code ExpenseApprovalListener} on the engine's event, and by nothing else
     * — the same rule as {@code Expense.applyDecision}, for the same reason.
     */
    public void applyDecision(Status status, Instant at, UUID by, String remarks) {
        this.status = status;
        if (status == Status.APPROVED) {
            this.approvedAt = at;
            this.approvedBy = by;
        } else if (status == Status.REJECTED) {
            this.rejectionReason = remarks;
        }
    }

    /** Float cleared by this settlement: bills produced plus cash handed back. */
    public BigDecimal clearedAmount() {
        return expensesAmount.add(returnedAmount);
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getAdvanceId() {
        return advanceId;
    }

    public String getSettlementNumber() {
        return settlementNumber;
    }

    public LocalDate getSettlementDate() {
        return settlementDate;
    }

    public BigDecimal getExpensesAmount() {
        return expensesAmount;
    }

    public BigDecimal getReturnedAmount() {
        return returnedAmount;
    }

    public Status getStatus() {
        return status;
    }

    public UUID getSubmittedBy() {
        return submittedBy;
    }

    public UUID getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }
}

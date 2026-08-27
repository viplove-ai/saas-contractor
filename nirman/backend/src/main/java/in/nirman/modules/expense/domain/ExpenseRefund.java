package in.nirman.modules.expense.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What became of the refundable part of an expense.
 *
 * <p>Rows rather than a status column on the expense, for the reason {@link Payment} is rows:
 * a deposit comes back in parts often enough that a single date-and-amount would be a lie
 * about a normal case. The electricity board adjusts half of a meter security against the
 * final bill and refunds the rest by cheque two months later, and both halves are real events
 * with their own dates and references.</p>
 *
 * <p>Two outcomes and not three. "Still waiting" is the absence of a row — giving it a
 * spelling of its own would let one deposit be both waiting and settled, and the register
 * would then have to decide which of the two it believed.</p>
 */
@Entity
@Table(name = "expense_refunds")
public class ExpenseRefund extends BaseEntity {

    public enum Outcome {
        /** The money came back — by cheque, by transfer, or adjusted against a later bill. */
        RECEIVED,
        /**
         * It is not coming back, and the row says why.
         *
         * <p>This closes the deposit in the register and books no cost. The loss belongs to
         * the day somebody decided it rather than to the day the connection was taken, so it
         * is booked as its own expense under a loss head at that date — the same shape as a
         * correction after an RA bill is passed. Writing it back into the original expense's
         * period would move a figure the office has already reported.</p>
         */
        WRITTEN_OFF
    }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "expense_id", nullable = false, updatable = false)
    private UUID expenseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20, updatable = false)
    private Outcome outcome;

    @Column(name = "settled_on", nullable = false)
    private LocalDate settledOn;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "payment_mode", length = 20)
    private String paymentMode;

    /** Cheque number, NEFT UTR, or the credit note it was adjusted against. */
    @Column(name = "reference_number", length = 80)
    private String referenceNumber;

    /** Required on a write-off: a deposit that vanishes quietly cannot be asked about. */
    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "remarks")
    private String remarks;

    protected ExpenseRefund() {
    }

    public ExpenseRefund(UUID orgId, UUID expenseId, Outcome outcome, LocalDate settledOn,
                         BigDecimal amount) {
        this.orgId = orgId;
        this.expenseId = expenseId;
        this.outcome = outcome;
        this.settledOn = settledOn;
        this.amount = amount;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getExpenseId() {
        return expenseId;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public LocalDate getSettledOn() {
        return settledOn;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getPaymentMode() {
        return paymentMode;
    }

    public void setPaymentMode(String paymentMode) {
        this.paymentMode = paymentMode;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public void setReferenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }
}

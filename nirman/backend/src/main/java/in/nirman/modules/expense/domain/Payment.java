package in.nirman.modules.expense.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Cash actually handed over, against one expense.
 *
 * <p>Partial payments are the normal case rather than an edge one: a supplier is paid
 * ₹20,000 on account this week and the balance next month, and both are real events with
 * their own dates and reference numbers. Modelling them as rows rather than as a single
 * running figure on the expense is what makes a payable ageing report possible at all.</p>
 */
@Entity
@Table(name = "payments")
public class Payment extends BaseEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "project_id", updatable = false)
    private UUID projectId;

    @Column(name = "site_id", updatable = false)
    private UUID siteId;

    @Column(name = "expense_id", updatable = false)
    private UUID expenseId;

    @Column(name = "vendor_id", updatable = false)
    private UUID vendorId;

    @Column(name = "payment_number", nullable = false, length = 50, updatable = false)
    private String paymentNumber;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "payment_mode", nullable = false, length = 20)
    private String paymentMode;

    /** Cheque number, UPI reference, NEFT UTR — whatever proves it left the account. */
    @Column(name = "reference_number", length = 80)
    private String referenceNumber;

    @Column(name = "bank_account", length = 60)
    private String bankAccount;

    @Column(name = "remarks")
    private String remarks;

    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    @Column(name = "reconciled_by")
    private UUID reconciledBy;

    /**
     * The float this payment came out of, where the holder paid the vendor himself.
     *
     * <p>Null on every payment the office makes, which is almost all of them. When it is set
     * the cash did not leave a bank account at all — it left a supervisor's pocket, and the
     * person the company now owes is him rather than the supplier (V49).</p>
     */
    @Column(name = "site_advance_id", updatable = false)
    private UUID siteAdvanceId;

    protected Payment() {
    }

    public Payment(UUID orgId, UUID projectId, UUID siteId, UUID expenseId, UUID vendorId,
                   String paymentNumber, LocalDate paymentDate, BigDecimal amount,
                   String paymentMode) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("a payment must be for a positive amount");
        }
        this.orgId = orgId;
        this.projectId = projectId;
        this.siteId = siteId;
        this.expenseId = expenseId;
        this.vendorId = vendorId;
        this.paymentNumber = paymentNumber;
        this.paymentDate = paymentDate;
        this.amount = amount;
        this.paymentMode = paymentMode;
    }

    /** Says the cash came out of a float rather than out of the bank. Set once, at creation. */
    public void fundedByFloat(UUID advanceId) {
        this.siteAdvanceId = advanceId;
    }

    public UUID getSiteAdvanceId() {
        return siteAdvanceId;
    }

    public void reconcile(Instant at, UUID by) {
        this.reconciledAt = at;
        this.reconciledBy = by;
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

    public UUID getExpenseId() {
        return expenseId;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public String getPaymentNumber() {
        return paymentNumber;
    }

    public LocalDate getPaymentDate() {
        return paymentDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getPaymentMode() {
        return paymentMode;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public void setReferenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
    }

    public String getBankAccount() {
        return bankAccount;
    }

    public void setBankAccount(String bankAccount) {
        this.bankAccount = bankAccount;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public Instant getReconciledAt() {
        return reconciledAt;
    }

    public UUID getReconciledBy() {
        return reconciledBy;
    }
}

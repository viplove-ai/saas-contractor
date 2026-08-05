package in.nirman.modules.expense.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Money spent at a site.
 *
 * <p>Three amounts live on and around this row and are never merged: {@code totalAmount} is
 * the approved cost, the sum of its {@code payments} is the cash actually handed over, and
 * the difference is what is still payable. A single "amount paid" field would collapse a
 * question the business asks daily — what do we owe, and to whom — into one that cannot be
 * answered (docs/02).</p>
 *
 * <p>The id is client-generated so an expense photographed and typed at site with no signal
 * and synced three times is one expense.</p>
 *
 * <p><b>The status column is a cache.</b> It is written by the expense module's listener on
 * the approval engine's event, never by a business method here — docs/09 open question 2.
 * The chain in {@code approvals} is what actually decides.</p>
 */
@Entity
@Table(name = "expenses")
public class Expense extends BaseEntity {

    public enum Workflow {
        DRAFT,
        SUBMITTED,
        /** Past the first level, waiting on the second. */
        L1_APPROVED,
        APPROVED,
        REJECTED,
        /** Sent back to its author to fix. Editable again. */
        RETURNED,
        VOIDED;

        /** Draft, returned and rejected rows are still the author's to change. */
        public boolean isEditable() {
            return this == DRAFT || this == RETURNED || this == REJECTED;
        }

        public boolean isInFlight() {
            return this == SUBMITTED || this == L1_APPROVED;
        }
    }

    public enum PaymentStatus { UNPAID, PARTIAL, PAID }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private UUID siteId;

    @Column(name = "expense_number", nullable = false, length = 50, updatable = false)
    private String expenseNumber;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(name = "subcategory_id")
    private UUID subcategoryId;

    @Column(name = "vendor_id")
    private UUID vendorId;

    /** The work item the cost is charged to, so labour, material and cash meet on one line. */
    @Column(name = "boq_item_id")
    private UUID boqItemId;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "bill_number", length = 60)
    private String billNumber;

    @Column(name = "bill_date")
    private LocalDate billDate;

    @Column(name = "amount_before_tax", nullable = false, precision = 18, scale = 2)
    private BigDecimal amountBeforeTax = BigDecimal.ZERO;

    @Column(name = "gst_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal gstPercent = BigDecimal.ZERO;

    @Column(name = "gst_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal gstAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "payment_mode", length = 20)
    private String paymentMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;

    @Column(name = "paid_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    /** Why there is no bill. Required above the threshold when a bill number is absent. */
    @Column(name = "no_bill_reason")
    private String noBillReason;

    @Column(name = "goods_receipt_id")
    private UUID goodsReceiptId;

    @Column(name = "site_advance_id")
    private UUID siteAdvanceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "workflow_status", nullable = false, length = 25)
    private Workflow workflowStatus = Workflow.DRAFT;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "submitted_by")
    private UUID submittedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "voided_by")
    private UUID voidedBy;

    @Column(name = "void_reason")
    private String voidReason;

    /** The expense this one was booked despite looking like a copy of. */
    @Column(name = "duplicate_of_id")
    private UUID duplicateOfId;

    @Column(name = "duplicate_override_reason")
    private String duplicateOverrideReason;

    @Column(name = "remarks")
    private String remarks;

    @Column(name = "source", nullable = false, length = 15, updatable = false)
    private String source = "ONLINE";

    protected Expense() {
    }

    public Expense(UUID id, UUID orgId, UUID projectId, UUID siteId, String expenseNumber,
                   LocalDate expenseDate, UUID categoryId, String description) {
        setId(id);
        this.orgId = orgId;
        this.projectId = projectId;
        this.siteId = siteId;
        this.expenseNumber = expenseNumber;
        this.expenseDate = expenseDate;
        this.categoryId = categoryId;
        this.description = description;
    }

    /** Tax is derived from the rate, never typed beside it, so the two cannot disagree. */
    public void priceAt(BigDecimal amountBeforeTax, BigDecimal gstPercent) {
        this.amountBeforeTax = amountBeforeTax;
        this.gstPercent = gstPercent == null ? BigDecimal.ZERO : gstPercent;
        this.gstAmount = amountBeforeTax.multiply(this.gstPercent)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        this.totalAmount = amountBeforeTax.add(this.gstAmount);
    }

    public void submit(Instant at, UUID by) {
        this.workflowStatus = Workflow.SUBMITTED;
        this.submittedAt = at;
        this.submittedBy = by;
        this.rejectionReason = null;
    }

    /**
     * Moves the cached status to what the approval chain decided.
     *
     * <p><b>{@code ExpenseApprovalListener} is the only legitimate caller.</b> This column
     * is a denormalised cache of the {@code approvals} chain, and docs/09 open question 2
     * settled that it is written by the engine's event and never by a business service. A
     * service that reached for this directly would be the second approval system that
     * question ruled against — and the reviewer's cue is that no other class imports it.</p>
     */
    public void applyDecision(Workflow status, Instant at, UUID by, String remarks) {
        this.workflowStatus = status;
        if (status == Workflow.APPROVED) {
            this.approvedAt = at;
            this.approvedBy = by;
            this.rejectionReason = null;
        } else if (status == Workflow.REJECTED || status == Workflow.RETURNED) {
            this.rejectionReason = remarks;
        }
    }

    /** Cash recorded against this expense. Never set directly; the payment service adds to it. */
    public void addPayment(BigDecimal amount) {
        this.paidAmount = paidAmount.add(amount);
        this.paymentStatus = paidAmount.compareTo(totalAmount) >= 0
                ? PaymentStatus.PAID
                : paidAmount.signum() > 0 ? PaymentStatus.PARTIAL : PaymentStatus.UNPAID;
    }

    public BigDecimal payableAmount() {
        return totalAmount.subtract(paidAmount);
    }

    public void voidExpense(Instant at, UUID by, String reason) {
        this.workflowStatus = Workflow.VOIDED;
        this.voidedAt = at;
        this.voidedBy = by;
        this.voidReason = reason;
    }

    public void markDuplicateOf(UUID originalId, String overrideReason) {
        this.duplicateOfId = originalId;
        this.duplicateOverrideReason = overrideReason;
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

    public String getExpenseNumber() {
        return expenseNumber;
    }

    public LocalDate getExpenseDate() {
        return expenseDate;
    }

    public void setExpenseDate(LocalDate expenseDate) {
        this.expenseDate = expenseDate;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    public UUID getSubcategoryId() {
        return subcategoryId;
    }

    public void setSubcategoryId(UUID subcategoryId) {
        this.subcategoryId = subcategoryId;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public void setVendorId(UUID vendorId) {
        this.vendorId = vendorId;
    }

    public UUID getBoqItemId() {
        return boqItemId;
    }

    public void setBoqItemId(UUID boqItemId) {
        this.boqItemId = boqItemId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getBillNumber() {
        return billNumber;
    }

    public void setBillNumber(String billNumber) {
        this.billNumber = billNumber;
    }

    public LocalDate getBillDate() {
        return billDate;
    }

    public void setBillDate(LocalDate billDate) {
        this.billDate = billDate;
    }

    public BigDecimal getAmountBeforeTax() {
        return amountBeforeTax;
    }

    public BigDecimal getGstPercent() {
        return gstPercent;
    }

    public BigDecimal getGstAmount() {
        return gstAmount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getPaymentMode() {
        return paymentMode;
    }

    public void setPaymentMode(String paymentMode) {
        this.paymentMode = paymentMode;
    }

    public PaymentStatus getPaymentStatus() {
        return paymentStatus;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public String getNoBillReason() {
        return noBillReason;
    }

    public void setNoBillReason(String noBillReason) {
        this.noBillReason = noBillReason;
    }

    public UUID getGoodsReceiptId() {
        return goodsReceiptId;
    }

    public void setGoodsReceiptId(UUID goodsReceiptId) {
        this.goodsReceiptId = goodsReceiptId;
    }

    public UUID getSiteAdvanceId() {
        return siteAdvanceId;
    }

    public void setSiteAdvanceId(UUID siteAdvanceId) {
        this.siteAdvanceId = siteAdvanceId;
    }

    public Workflow getWorkflowStatus() {
        return workflowStatus;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public UUID getApprovedBy() {
        return approvedBy;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Instant getVoidedAt() {
        return voidedAt;
    }

    public String getVoidReason() {
        return voidReason;
    }

    public UUID getDuplicateOfId() {
        return duplicateOfId;
    }

    public String getDuplicateOverrideReason() {
        return duplicateOverrideReason;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}

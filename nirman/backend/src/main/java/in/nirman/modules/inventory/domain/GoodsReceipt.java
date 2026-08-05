package in.nirman.modules.inventory.domain;

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
 * A delivery arriving at a store: the challan, the invoice and what came off the lorry.
 *
 * <p>The id is client-generated, so the storekeeper who books a delivery with no signal and
 * syncs it three times books one delivery. The GRN number is not: it is the label people
 * write on paper and say on the phone, and two disconnected devices would both reach for
 * {@code GRN-2025-0001}. The server assigns it on arrival (docs/09, question 1).</p>
 *
 * <p><b>Stock does not move when this is created.</b> It moves when the receipt is
 * verified. A delivery note typed in at the gate is a claim about what arrived; the
 * engineer checking it against the challan is what turns the claim into stock. Until then
 * the store's balance says what it can actually issue.</p>
 */
@Entity
@Table(name = "goods_receipts")
public class GoodsReceipt extends BaseEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private UUID siteId;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "grn_number", nullable = false, length = 50, updatable = false)
    private String grnNumber;

    @Column(name = "vendor_id")
    private UUID vendorId;

    @Column(name = "po_id")
    private UUID poId;

    @Column(name = "receipt_date", nullable = false)
    private LocalDate receiptDate;

    @Column(name = "invoice_number", length = 60)
    private String invoiceNumber;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "challan_number", length = 60)
    private String challanNumber;

    @Column(name = "vehicle_number", length = 25)
    private String vehicleNumber;

    @Column(name = "sub_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal subTotal = BigDecimal.ZERO;

    @Column(name = "gst_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal gstAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /** Set in Phase 5, when the purchase is booked as an expense. Never the consumption cost. */
    @Column(name = "expense_id")
    private UUID expenseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "workflow_status", nullable = false, length = 20)
    private DocumentWorkflow workflowStatus = DocumentWorkflow.DRAFT;

    @Column(name = "received_by")
    private UUID receivedBy;

    @Column(name = "verified_by")
    private UUID verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "remarks")
    private String remarks;

    @Column(name = "source", nullable = false, length = 15, updatable = false)
    private String source = "ONLINE";

    protected GoodsReceipt() {
    }

    public GoodsReceipt(UUID id, UUID orgId, UUID projectId, UUID siteId, UUID storeId,
                        String grnNumber, LocalDate receiptDate) {
        setId(id);
        this.orgId = orgId;
        this.projectId = projectId;
        this.siteId = siteId;
        this.storeId = storeId;
        this.grnNumber = grnNumber;
        this.receiptDate = receiptDate;
    }

    /** Totals are derived from the lines, never typed, so the header cannot disagree with them. */
    public void applyTotals(BigDecimal subTotal, BigDecimal gstAmount) {
        this.subTotal = subTotal;
        this.gstAmount = gstAmount;
        this.totalAmount = subTotal.add(gstAmount);
    }

    public void submit() {
        this.workflowStatus = DocumentWorkflow.SUBMITTED;
    }

    public void verify(Instant at, UUID by) {
        this.workflowStatus = DocumentWorkflow.VERIFIED;
        this.verifiedAt = at;
        this.verifiedBy = by;
        this.rejectionReason = null;
    }

    public void reject(UUID by, String reason) {
        this.workflowStatus = DocumentWorkflow.REJECTED;
        this.verifiedBy = by;
        this.rejectionReason = reason;
    }

    public void cancel(String reason) {
        this.workflowStatus = DocumentWorkflow.CANCELLED;
        this.rejectionReason = reason;
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

    public UUID getStoreId() {
        return storeId;
    }

    public String getGrnNumber() {
        return grnNumber;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public void setVendorId(UUID vendorId) {
        this.vendorId = vendorId;
    }

    public UUID getPoId() {
        return poId;
    }

    public void setPoId(UUID poId) {
        this.poId = poId;
    }

    public LocalDate getReceiptDate() {
        return receiptDate;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public void setInvoiceNumber(String invoiceNumber) {
        this.invoiceNumber = invoiceNumber;
    }

    public LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    public void setInvoiceDate(LocalDate invoiceDate) {
        this.invoiceDate = invoiceDate;
    }

    public String getChallanNumber() {
        return challanNumber;
    }

    public void setChallanNumber(String challanNumber) {
        this.challanNumber = challanNumber;
    }

    public String getVehicleNumber() {
        return vehicleNumber;
    }

    public void setVehicleNumber(String vehicleNumber) {
        this.vehicleNumber = vehicleNumber;
    }

    public BigDecimal getSubTotal() {
        return subTotal;
    }

    public BigDecimal getGstAmount() {
        return gstAmount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public UUID getExpenseId() {
        return expenseId;
    }

    public DocumentWorkflow getWorkflowStatus() {
        return workflowStatus;
    }

    public UUID getReceivedBy() {
        return receivedBy;
    }

    public void setReceivedBy(UUID receivedBy) {
        this.receivedBy = receivedBy;
    }

    public UUID getVerifiedBy() {
        return verifiedBy;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
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

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}

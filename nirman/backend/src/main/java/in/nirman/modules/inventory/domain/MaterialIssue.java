package in.nirman.modules.inventory.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Material going out of the store to the work face.
 *
 * <p>This is the document the whole consumption half of the system rests on, and the one
 * the field has never filled in: the Kausani register records thirty-two deliveries and not
 * a single issue (docs/09). Receipts get recorded because a vendor wants paying; nobody
 * chases a store-keeper for an issue slip. So the screen has to be worth ten seconds, and
 * everything optional here is optional on purpose.</p>
 *
 * <p>What is <b>not</b> optional is a destination for the cost: either a BOQ item, so the
 * material lands against the work it was consumed by, or a written purpose. Material that
 * left the store and can be charged to nothing is how "cost incurred against work item X"
 * stops being answerable.</p>
 */
@Entity
@Table(name = "material_issues")
public class MaterialIssue extends BaseEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private UUID siteId;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "issue_number", nullable = false, length = 50, updatable = false)
    private String issueNumber;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "issued_to_name", length = 150)
    private String issuedToName;

    @Column(name = "issued_to_supplier_id")
    private UUID issuedToSupplierId;

    /** The work item the material is consumed by. Header-level default for every line. */
    @Column(name = "boq_item_id")
    private UUID boqItemId;

    @Column(name = "work_location", length = 150)
    private String workLocation;

    @Column(name = "purpose", length = 300)
    private String purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "workflow_status", nullable = false, length = 20)
    private DocumentWorkflow workflowStatus = DocumentWorkflow.DRAFT;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "source", nullable = false, length = 15, updatable = false)
    private String source = "ONLINE";

    protected MaterialIssue() {
    }

    public MaterialIssue(UUID id, UUID orgId, UUID projectId, UUID siteId, UUID storeId,
                         String issueNumber, LocalDate issueDate) {
        setId(id);
        this.orgId = orgId;
        this.projectId = projectId;
        this.siteId = siteId;
        this.storeId = storeId;
        this.issueNumber = issueNumber;
        this.issueDate = issueDate;
    }

    public void submit() {
        this.workflowStatus = DocumentWorkflow.SUBMITTED;
    }

    public void approve(Instant at, UUID by) {
        this.workflowStatus = DocumentWorkflow.APPROVED;
        this.approvedAt = at;
        this.approvedBy = by;
        this.rejectionReason = null;
    }

    public void reject(UUID by, String reason) {
        this.workflowStatus = DocumentWorkflow.REJECTED;
        this.approvedBy = by;
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

    public String getIssueNumber() {
        return issueNumber;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public String getIssuedToName() {
        return issuedToName;
    }

    public void setIssuedToName(String issuedToName) {
        this.issuedToName = issuedToName;
    }

    public UUID getIssuedToSupplierId() {
        return issuedToSupplierId;
    }

    public void setIssuedToSupplierId(UUID issuedToSupplierId) {
        this.issuedToSupplierId = issuedToSupplierId;
    }

    public UUID getBoqItemId() {
        return boqItemId;
    }

    public void setBoqItemId(UUID boqItemId) {
        this.boqItemId = boqItemId;
    }

    public String getWorkLocation() {
        return workLocation;
    }

    public void setWorkLocation(String workLocation) {
        this.workLocation = workLocation;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public DocumentWorkflow getWorkflowStatus() {
        return workflowStatus;
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

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}

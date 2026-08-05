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
 * A physical count of what is actually in a store, against what the ledger says should be.
 *
 * <p>The count does not correct the balance directly. Approving it posts an
 * {@code ADJUSTMENT} to the ledger for every line that differs, and the balance moves
 * because the ledger moved — the same path every other document takes. That is the point of
 * docs/03 rule 3: there is no way to type a balance, not even here, where typing one would
 * be most tempting.</p>
 *
 * <p>Approval sits behind {@code inventory:adjust}, which only the administrator holds. A
 * count is where stock losses get written off, so the person who counts and the person who
 * accepts the count are deliberately not the same person.</p>
 */
@Entity
@Table(name = "physical_stock_counts")
public class PhysicalStockCount extends BaseEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "count_number", nullable = false, length = 50, updatable = false)
    private String countNumber;

    @Column(name = "count_date", nullable = false)
    private LocalDate countDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DocumentWorkflow status = DocumentWorkflow.DRAFT;

    @Column(name = "counted_by")
    private UUID countedBy;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "remarks")
    private String remarks;

    protected PhysicalStockCount() {
    }

    public PhysicalStockCount(UUID orgId, UUID storeId, String countNumber, LocalDate countDate,
                              UUID countedBy) {
        this.orgId = orgId;
        this.storeId = storeId;
        this.countNumber = countNumber;
        this.countDate = countDate;
        this.countedBy = countedBy;
        this.status = DocumentWorkflow.SUBMITTED;
    }

    public void approve(Instant at, UUID by) {
        this.status = DocumentWorkflow.APPROVED;
        this.approvedAt = at;
        this.approvedBy = by;
    }

    public void reject(UUID by, String reason) {
        this.status = DocumentWorkflow.REJECTED;
        this.approvedBy = by;
        this.remarks = reason;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getCountNumber() {
        return countNumber;
    }

    public LocalDate getCountDate() {
        return countDate;
    }

    public DocumentWorkflow getStatus() {
        return status;
    }

    public UUID getCountedBy() {
        return countedBy;
    }

    public UUID getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }
}

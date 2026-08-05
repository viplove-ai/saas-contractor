package in.nirman.modules.project.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A dated claim of work done against one BOQ line — the measurement book, one row at a time.
 *
 * <p>Append-only, like the stock ledger and the wage ledger, and for the same reason: this is
 * what the running bill is built from. {@code boq_items.completed_quantity} is a cache of the
 * sum of these rows, so a disputed percentage is settled by reading the entries that produced
 * it rather than by arguing about the total.</p>
 *
 * <p>A correction is a negative entry, never an edit. If 40 cum was claimed and only 35 was
 * measured, the fix is an entry of −5 with a reason on it, and both stay on the record —
 * exactly the discipline an attendance correction follows.</p>
 *
 * <p>{@code dprId} is set when the claim arrived through a verified daily progress report,
 * which is the normal path: entering the contract and claiming work against it are different
 * acts, and the second one is what an engineer signs. It is null for a measurement recorded
 * directly against the line.</p>
 */
@Entity
@Table(name = "boq_progress_entries")
@EntityListeners(AuditingEntityListener.class)
public class BoqProgressEntry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "boq_item_id", nullable = false, updatable = false)
    private UUID boqItemId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private UUID siteId;

    @Column(name = "entry_date", nullable = false, updatable = false)
    private LocalDate entryDate;

    /** Signed. Negative is a correction of an earlier over-measurement; zero is refused. */
    @Column(name = "quantity", nullable = false, precision = 18, scale = 4, updatable = false)
    private BigDecimal quantity;

    @Column(name = "dpr_id", updatable = false)
    private UUID dprId;

    @Column(name = "remarks", updatable = false)
    private String remarks;

    @Column(name = "recorded_by", updatable = false)
    private UUID recordedBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected BoqProgressEntry() {
    }

    public BoqProgressEntry(UUID orgId, UUID boqItemId, UUID siteId, LocalDate entryDate,
                            BigDecimal quantity, UUID dprId, String remarks, UUID recordedBy) {
        if (quantity == null || quantity.signum() == 0) {
            throw new IllegalArgumentException(
                    "a progress entry of zero says nothing; the constraint ck_boq_entry_qty agrees");
        }
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.boqItemId = boqItemId;
        this.siteId = siteId;
        this.entryDate = entryDate;
        this.quantity = quantity;
        this.dprId = dprId;
        this.remarks = remarks;
        this.recordedBy = recordedBy;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getBoqItemId() {
        return boqItemId;
    }

    public UUID getSiteId() {
        return siteId;
    }

    public LocalDate getEntryDate() {
        return entryDate;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public UUID getDprId() {
        return dprId;
    }

    public String getRemarks() {
        return remarks;
    }

    public UUID getRecordedBy() {
        return recordedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {
        return version;
    }
}

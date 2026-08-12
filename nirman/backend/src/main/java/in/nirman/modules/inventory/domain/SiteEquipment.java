package in.nirman.modules.inventory.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A machine standing at a site.
 *
 * <p>Deliberately not stock. Stock is consumed — it arrives, it is issued to a work item, and
 * the ledger says what is left — while a mixer is <em>held</em>: the same one is at the site
 * in March and in June. Putting plant through {@code stock_transactions} would report a mixer
 * as used up by the raft slab and leave the store believing there is nothing to pour with.</p>
 *
 * <p>The entry and the acceptance are two acts by two people. Anybody at the site may say a
 * machine is here, because a supervisor who cannot enter the mixer he is looking at will not
 * enter it at all; only the office accepts it onto the register, because an entry nobody
 * checked is a claim, and a hired breaker that went back on Tuesday must not sit there as an
 * asset for a year.</p>
 */
@Entity
@Table(name = "site_equipment")
public class SiteEquipment extends BaseEntity {

    /** Whose machine it is — and therefore whether it is an asset or a running cost. */
    public enum Ownership {
        OWNED,
        HIRED
    }

    /** A machine on site and broken is not capacity, which is the whole reason to record it. */
    public enum Condition {
        WORKING,
        IDLE,
        UNDER_REPAIR
    }

    public enum Status {
        PENDING,
        ACCEPTED,
        REJECTED
    }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private UUID siteId;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    /** The number painted on it, or its registration. Unique per organisation when present. */
    @Column(name = "asset_code", length = 60)
    private String assetCode;

    @Column(name = "quantity", nullable = false)
    private int quantity = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "ownership", nullable = false, length = 20)
    private Ownership ownership = Ownership.OWNED;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition", nullable = false, length = 20)
    private Condition condition = Condition.WORKING;

    /** Who it is hired from. A vendor, because the man who sends a JCB is a supplier (V23). */
    @Column(name = "supplier_id")
    private UUID supplierId;

    @Column(name = "remarks")
    private String remarks;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decision_remarks", length = 500)
    private String decisionRemarks;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private UUID deletedBy;

    protected SiteEquipment() {
    }

    public SiteEquipment(UUID id, UUID orgId, UUID siteId, UUID storeId, String name) {
        // The id comes from the caller for the reason every other document's does: a phone
        // that re-sends the same entry three times must not put three mixers on the register.
        setId(id);
        this.orgId = orgId;
        this.siteId = siteId;
        this.storeId = storeId;
        this.name = name;
    }

    /**
     * The office agreeing that the machine is there. Also the path an administrator's own
     * entry takes on the way in — asking him to approve himself is a ceremony with no second
     * pair of eyes in it.
     */
    public void accept(Instant at, UUID by, String remarks) {
        this.status = Status.ACCEPTED;
        this.decidedAt = at;
        this.decidedBy = by;
        this.decisionRemarks = remarks;
    }

    /**
     * Not here, or not this. The row stays on the register rather than vanishing: somebody
     * entered it in good faith and is owed the answer, and the reason is the answer.
     */
    public void reject(Instant at, UUID by, String remarks) {
        this.status = Status.REJECTED;
        this.decidedAt = at;
        this.decidedBy = by;
        this.decisionRemarks = remarks;
    }

    public void delete(Instant at, UUID by) {
        this.deletedAt = at;
        this.deletedBy = by;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getSiteId() {
        return siteId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public void setStoreId(UUID storeId) {
        this.storeId = storeId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAssetCode() {
        return assetCode;
    }

    public void setAssetCode(String assetCode) {
        this.assetCode = assetCode;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public Ownership getOwnership() {
        return ownership;
    }

    public void setOwnership(Ownership ownership) {
        this.ownership = ownership;
    }

    public Condition getCondition() {
        return condition;
    }

    public void setCondition(Condition condition) {
        this.condition = condition;
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public void setSupplierId(UUID supplierId) {
        this.supplierId = supplierId;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public String getDecisionRemarks() {
        return decisionRemarks;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public UUID getDeletedBy() {
        return deletedBy;
    }
}

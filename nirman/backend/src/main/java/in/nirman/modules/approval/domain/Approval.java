package in.nirman.modules.approval.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One person's decision on one record at one level.
 *
 * <p>A chain of these is the whole approval history: raised at level 1, decided, raised at
 * level 2, decided. Rows are never deleted and a decided row is never re-decided, so
 * "who cleared this and when" is answerable months later — which is the only question
 * anybody actually asks of an approval system.</p>
 *
 * <p>{@code previousStatus} and {@code nextStatus} record what the business record moved
 * from and to. They are what let the engine stay ignorant of expenses, settlements and
 * corrections while still describing what its decision did to one.</p>
 */
@Entity
@Table(name = "approvals")
public class Approval {

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED,
        /** Sent back to the author to fix, rather than refused outright. */
        RETURNED,
        /** The record was voided or withdrawn before this level got to it. */
        CANCELLED,
        /** No rule at this level applied to this amount. */
        SKIPPED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "entity_type", nullable = false, length = 40, updatable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;

    /** Which site's queue this lands in. Null for a record that belongs to no one site. */
    @Column(name = "site_id", updatable = false)
    private UUID siteId;

    @Column(name = "level", nullable = false, updatable = false)
    private int level;

    /**
     * What the record was worth when the chain was raised, frozen here.
     *
     * <p>Deciding level 1 has to know it, because whether a level 2 exists at all is a
     * function of the amount. Frozen rather than re-read so a threshold raised next week
     * cannot retroactively excuse a record already sitting in somebody's queue — the same
     * rule that freezes a wage rate at verification.</p>
     */
    @Column(name = "entity_amount", precision = 18, scale = 2, updatable = false)
    private BigDecimal entityAmount;

    @Column(name = "assigned_role", nullable = false, length = 40, updatable = false)
    private String assignedRole;

    @Column(name = "assigned_user_id")
    private UUID assignedUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "action_by")
    private UUID actionBy;

    @Column(name = "action_at")
    private Instant actionAt;

    @Column(name = "remarks")
    private String remarks;

    @Column(name = "previous_status", length = 30)
    private String previousStatus;

    @Column(name = "next_status", length = 30)
    private String nextStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Approval() {
    }

    public Approval(UUID orgId, String entityType, UUID entityId, UUID siteId, int level,
                    String assignedRole, BigDecimal entityAmount, String previousStatus) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.entityType = entityType;
        this.entityId = entityId;
        this.siteId = siteId;
        this.level = level;
        this.assignedRole = assignedRole;
        this.entityAmount = entityAmount;
        this.previousStatus = previousStatus;
    }

    public void decide(Status outcome, UUID by, Instant at, String remarks, String nextStatus) {
        this.status = outcome;
        this.actionBy = by;
        this.actionAt = at;
        this.remarks = remarks;
        this.nextStatus = nextStatus;
        this.updatedAt = at;
    }

    /** The record went away before this level saw it. Not a decision, and not a rejection. */
    public void cancel(Instant at, String reason) {
        this.status = Status.CANCELLED;
        this.actionAt = at;
        this.remarks = reason;
        this.updatedAt = at;
    }

    public boolean isPending() {
        return status == Status.PENDING;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getEntityType() {
        return entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public UUID getSiteId() {
        return siteId;
    }

    public int getLevel() {
        return level;
    }

    public BigDecimal getEntityAmount() {
        return entityAmount;
    }

    public String getAssignedRole() {
        return assignedRole;
    }

    public UUID getAssignedUserId() {
        return assignedUserId;
    }

    public Status getStatus() {
        return status;
    }

    public UUID getActionBy() {
        return actionBy;
    }

    public Instant getActionAt() {
        return actionAt;
    }

    public String getRemarks() {
        return remarks;
    }

    public String getPreviousStatus() {
        return previousStatus;
    }

    public String getNextStatus() {
        return nextStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {
        return version;
    }
}

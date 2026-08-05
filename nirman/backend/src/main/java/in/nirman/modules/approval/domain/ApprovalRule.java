package in.nirman.modules.approval.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Who has to agree to what, and above which amount.
 *
 * <p>A rule is a row rather than a constant, because the threshold is a commercial decision
 * that changes: a firm that lets a site engineer clear ₹25,000 this year will not next year,
 * and that is a settings change, not a deployment.</p>
 *
 * <p>{@code minAmount} and {@code maxAmount} are both optional and both inclusive at the
 * bottom, exclusive at the top. A level with no bounds applies to every amount, which is
 * what makes "the engineer sees everything, the administrator sees the big ones" expressible
 * as two rows.</p>
 */
@Entity
@Table(name = "approval_rules")
public class ApprovalRule {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    /** EXPENSE | ADVANCE_SETTLEMENT | ATTENDANCE_CORRECTION | STOCK_ADJUSTMENT | DPR */
    @Column(name = "entity_type", nullable = false, length = 40, updatable = false)
    private String entityType;

    /** 1 is the first person to agree. Levels run in order and a gap simply never fires. */
    @Column(name = "level", nullable = false)
    private int level;

    @Column(name = "role_code", nullable = false, length = 40)
    private String roleCode;

    @Column(name = "min_amount", precision = 18, scale = 2)
    private BigDecimal minAmount;

    @Column(name = "max_amount", precision = 18, scale = 2)
    private BigDecimal maxAmount;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ApprovalRule() {
    }

    public ApprovalRule(UUID orgId, String entityType, int level, String roleCode,
                        BigDecimal minAmount, BigDecimal maxAmount) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.entityType = entityType;
        this.level = level;
        this.roleCode = roleCode;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
    }

    /**
     * Whether this level has to look at a record of the given size.
     *
     * <p>A null amount means the record has no money on it — an attendance correction, say —
     * and every level applies to it. Bounding an approval by an amount that does not exist
     * would skip the approval entirely, which is the wrong way for a mistake to fail.</p>
     */
    public boolean appliesTo(BigDecimal amount) {
        if (!active) {
            return false;
        }
        if (amount == null) {
            return true;
        }
        if (minAmount != null && amount.compareTo(minAmount) < 0) {
            return false;
        }
        return maxAmount == null || amount.compareTo(maxAmount) < 0;
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

    public int getLevel() {
        return level;
    }

    public String getRoleCode() {
        return roleCode;
    }

    public BigDecimal getMinAmount() {
        return minAmount;
    }

    public BigDecimal getMaxAmount() {
        return maxAmount;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
        this.updatedAt = Instant.now();
    }

    public Long getVersion() {
        return version;
    }
}

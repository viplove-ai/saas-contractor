package in.nirman.modules.expense.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-organisation thresholds for the expense flow.
 *
 * <p>{@code billRequiredAbove} is a rule about evidence, not about approval:
 * above it an expense needs a bill number or a written explanation of why there is none.
 * The field data is the reason it is a threshold rather than a blanket rule — a great many
 * small site purchases genuinely have no bill, and demanding a reason on every ₹200 of
 * cartage produces a column of the word "cash" that nobody reads (docs/09).</p>
 *
 * <p>{@code adminApprovalAbove} is kept for backward compatibility and is <b>not</b> read by
 * the approval flow. Routing lives in {@code approval_rules}, which docs/09 open question 2
 * settled as authoritative; two homes for the same threshold is exactly the drift that
 * question was about.</p>
 */
@Entity
@Table(name = "expense_settings")
public class ExpenseSettings {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "bill_required_above", nullable = false, precision = 18, scale = 2)
    private BigDecimal billRequiredAbove = BigDecimal.valueOf(5000);

    @Column(name = "admin_approval_above", nullable = false, precision = 18, scale = 2)
    private BigDecimal adminApprovalAbove = BigDecimal.valueOf(25000);

    @Column(name = "duplicate_check_enabled", nullable = false)
    private boolean duplicateCheckEnabled = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ExpenseSettings() {
    }

    public ExpenseSettings(UUID orgId) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public BigDecimal getBillRequiredAbove() {
        return billRequiredAbove;
    }

    public void setBillRequiredAbove(BigDecimal billRequiredAbove) {
        this.billRequiredAbove = billRequiredAbove;
        this.updatedAt = Instant.now();
    }

    public BigDecimal getAdminApprovalAbove() {
        return adminApprovalAbove;
    }

    public boolean isDuplicateCheckEnabled() {
        return duplicateCheckEnabled;
    }

    public void setDuplicateCheckEnabled(boolean duplicateCheckEnabled) {
        this.duplicateCheckEnabled = duplicateCheckEnabled;
        this.updatedAt = Instant.now();
    }

    public Long getVersion() {
        return version;
    }
}

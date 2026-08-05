package in.nirman.modules.labour.domain;

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
 * Per-organisation labour policy.
 *
 * <p>{@code overtimeReasonRequiredAboveHours} exists because the obvious rule — demand a
 * reason for every overtime hour — collapses in the field. On a site running a seven-hour
 * shift nearly every worker books overtime nearly every day; one logged 102 overtime hours
 * in a single February. A mandatory per-record reason there produces two hundred copies of
 * the word "OT" and stops meaning anything. A threshold keeps the prompt rare enough that
 * an answer to it is worth reading.</p>
 */
@Entity
@Table(name = "labour_settings")
public class LabourSettings {

    public enum SettlementPeriod { WEEKLY, FORTNIGHTLY, MONTHLY }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, unique = true, updatable = false)
    private UUID orgId;

    @Column(name = "overtime_reason_required_above_hours", nullable = false, precision = 4, scale = 2)
    private BigDecimal overtimeReasonRequiredAboveHours = new BigDecimal("4.00");

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_period", nullable = false, length = 12)
    private SettlementPeriod settlementPeriod = SettlementPeriod.MONTHLY;

    @Column(name = "advance_auto_recover", nullable = false)
    private boolean advanceAutoRecover = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected LabourSettings() {
    }

    public LabourSettings(UUID orgId) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public BigDecimal getOvertimeReasonRequiredAboveHours() {
        return overtimeReasonRequiredAboveHours;
    }

    public SettlementPeriod getSettlementPeriod() {
        return settlementPeriod;
    }

    public boolean isAdvanceAutoRecover() {
        return advanceAutoRecover;
    }
}

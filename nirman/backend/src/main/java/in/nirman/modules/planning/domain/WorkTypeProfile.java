package in.nirman.modules.planning.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A kind of work, and the planning defaults that go with it.
 *
 * <p>A school, a road, an internal water supply scheme and an E&amp;M package are four different
 * plans. One sequencing rule flatters all four and fits none, so the kind of work is chosen
 * first and selects everything after it.</p>
 *
 * <p>The profile is guessed from the BOQ's category mix and the work name, then shown to the
 * user to accept or change — never applied silently. A plan built on a wrong guess about what
 * kind of job this is would be wrong in a way no individual number reveals.</p>
 */
@Entity
@Table(name = "work_type_profiles")
public class WorkTypeProfile extends BaseEntity {

    /** What a phase is cut by on this kind of work. */
    public enum PhaseBasis { FLOOR, CHAINAGE, BLOCK, PARALLEL, SEASON }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description")
    private String description;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "phase_basis", nullable = false, length = 20)
    private PhaseBasis phaseBasis = PhaseBasis.FLOOR;

    @Column(name = "monsoon_sensitive", nullable = false)
    private boolean monsoonSensitive;

    @Column(name = "default_overhead_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal defaultOverheadPercent = new BigDecimal("8.00");

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected WorkTypeProfile() {
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public PhaseBasis getPhaseBasis() {
        return phaseBasis;
    }

    public boolean isMonsoonSensitive() {
        return monsoonSensitive;
    }

    public BigDecimal getDefaultOverheadPercent() {
        return defaultOverheadPercent;
    }

    public boolean isActive() {
        return active;
    }
}

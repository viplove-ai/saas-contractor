package in.nirman.modules.planning.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What has to finish before this work starts, how far the two may overlap, and how many gangs
 * the working front will hold.
 *
 * <p>Plaster does not precede masonry, and painting does not precede plaster. Some overlap is
 * normal and some is impossible, and the difference is per trade: electrical conduiting runs
 * inside the slab it is cast into, where plaster cannot begin until the wall exists.</p>
 *
 * <p>{@link #getMaxConcurrentGangs()} is the constraint a naive planner forgets, and the reason
 * it is stored rather than computed. Dividing the work by the time available always yields a
 * head count, and on a compressed programme it yields one no site can physically hold — four
 * hundred masons on a two-hundred-square-metre slab. <b>A plan must be limited by the working
 * front, not only by arithmetic.</b> When the cap binds and the work still does not fit the time
 * allowed, that is not an error to swallow: it is the finding, and pre-award it is the most
 * valuable sentence the platform can produce.</p>
 *
 * <p>A row with a null {@link #getWorkTypeProfileId()} holds whatever the kind of work; a
 * profile-specific row overrides it.</p>
 */
@Entity
@Table(name = "work_sequence_norms")
public class WorkSequenceNorm extends BaseEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    /** Null = the default ordering, for any kind of work. */
    @Column(name = "work_type_profile_id")
    private UUID workTypeProfileId;

    @Column(name = "work_category", nullable = false, length = 80)
    private String workCategory;

    @Column(name = "sequence_rank", nullable = false)
    private int sequenceRank;

    /** How far into its predecessor this work may start, as a share of that predecessor. */
    @Column(name = "max_overlap_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal maxOverlapPercent = BigDecimal.ZERO;

    @Column(name = "max_concurrent_gangs", nullable = false)
    private int maxConcurrentGangs = 4;

    /** Whether rain stops this work rather than merely slowing it. */
    @Column(name = "monsoon_sensitive", nullable = false)
    private boolean monsoonSensitive;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "notes")
    private String notes;

    protected WorkSequenceNorm() {
    }

    public void reviseTo(Integer sequenceRank, BigDecimal maxOverlapPercent,
                         Integer maxConcurrentGangs, Boolean monsoonSensitive, Boolean active) {
        if (sequenceRank != null) {
            this.sequenceRank = sequenceRank;
        }
        if (maxOverlapPercent != null) {
            this.maxOverlapPercent = maxOverlapPercent;
        }
        if (maxConcurrentGangs != null) {
            this.maxConcurrentGangs = maxConcurrentGangs;
        }
        if (monsoonSensitive != null) {
            this.monsoonSensitive = monsoonSensitive;
        }
        if (active != null) {
            this.active = active;
        }
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getWorkTypeProfileId() {
        return workTypeProfileId;
    }

    public String getWorkCategory() {
        return workCategory;
    }

    public int getSequenceRank() {
        return sequenceRank;
    }

    public BigDecimal getMaxOverlapPercent() {
        return maxOverlapPercent;
    }

    public int getMaxConcurrentGangs() {
        return maxConcurrentGangs;
    }

    public boolean isMonsoonSensitive() {
        return monsoonSensitive;
    }

    public boolean isActive() {
        return active;
    }

    public String getNotes() {
        return notes;
    }
}

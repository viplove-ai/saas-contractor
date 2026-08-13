package in.nirman.modules.planning.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Man-days of one trade per unit of work in a category.
 *
 * <p>Two masons and three helpers per ten cubic metres of brickwork a day is the shape of it.
 * This is the link that turns a quantity into a duration and a head count, and the whole of
 * §6.2 and §6.4 of the planning design rests on it.</p>
 *
 * <p>Keyed exactly as {@code material_consumption_norms} is — a work category plus an optional
 * sub-type — so one classification of a BOQ line serves both, and the classifier the NIT import
 * already runs is what lets a norm find its work without anybody mapping rows by hand.</p>
 *
 * <p>{@link #getSource()} matters more than it looks. A norm shipped in the starter catalogue, a
 * norm an administrator typed, and a norm derived from this organisation's own completed work
 * are three different degrees of confidence, and a plan that could not tell them apart could not
 * say which of its numbers were guesses.</p>
 */
@Entity
@Table(name = "labour_productivity_norms")
public class LabourProductivityNorm extends BaseEntity {

    /** Where the figure came from, and so how much it should be trusted. */
    public static final String SOURCE_CPWD_AOR = "CPWD_AOR";
    public static final String SOURCE_INTERNAL = "INTERNAL";
    public static final String SOURCE_PROJECT = "PROJECT";

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "work_category", nullable = false, length = 80)
    private String workCategory;

    /** A grade or an operation within the category: {@code M25}, {@code Formwork}, {@code Painting}. */
    @Column(name = "work_sub_type", length = 80)
    private String workSubType;

    @Column(name = "skill_category_id", nullable = false)
    private UUID skillCategoryId;

    @Column(name = "work_unit_id", nullable = false)
    private UUID workUnitId;

    @Column(name = "man_days_per_work_unit", nullable = false, precision = 18, scale = 6)
    private BigDecimal manDaysPerWorkUnit;

    @Column(name = "source", nullable = false, length = 20)
    private String source = SOURCE_INTERNAL;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "notes")
    private String notes;

    protected LabourProductivityNorm() {
    }

    /**
     * Corrects the figure.
     *
     * <p>A hand-corrected norm is marked {@code INTERNAL} whatever it was before, because it is
     * now this organisation's number rather than the catalogue's, and a plan that went on citing
     * the Analysis of Rates for a figure somebody overwrote would be citing the wrong authority.
     * The change is caught by the audit log like every other write.</p>
     */
    public void reviseTo(BigDecimal manDaysPerWorkUnit, Boolean active, String notes) {
        if (manDaysPerWorkUnit != null) {
            this.manDaysPerWorkUnit = manDaysPerWorkUnit;
            this.source = SOURCE_INTERNAL;
        }
        if (active != null) {
            this.active = active;
        }
        if (notes != null) {
            this.notes = notes;
        }
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getWorkCategory() {
        return workCategory;
    }

    public String getWorkSubType() {
        return workSubType;
    }

    public UUID getSkillCategoryId() {
        return skillCategoryId;
    }

    public UUID getWorkUnitId() {
        return workUnitId;
    }

    public BigDecimal getManDaysPerWorkUnit() {
        return manDaysPerWorkUnit;
    }

    public String getSource() {
        return source;
    }

    public boolean isActive() {
        return active;
    }

    public String getNotes() {
        return notes;
    }
}

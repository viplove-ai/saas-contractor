package in.nirman.modules.inventory.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * How much of a material a piece of work is expected to take.
 *
 * <p>A NIT gives work quantities, not material quantities. A BOQ line reads <i>RCC M25 in
 * columns, 5.94 cum</i>; it does not read <i>30 bags of cement</i>. This is where the
 * bridge is recorded — either typed from a take-off, or derived from a
 * {@code material_consumption_norms} coefficient.</p>
 *
 * <h2>Three levels, not two</h2>
 * <p>{@code TENDER_BOQ} answers "did we quote enough". {@code EXECUTION_TAKEOFF} and
 * {@code BBS} answer "did the tender under-quantify" — they come from working drawings and
 * bar bending schedules, not from the NIT. Collapsing them into one "estimated" column
 * hides the gap between what was bid and what the drawings actually demand, which is the
 * most commercially useful number in the set (docs/09 section B).</p>
 *
 * <p>A revision supersedes rather than overwrites, so last month's variance cannot silently
 * rewrite itself — the same rule that freezes a wage at verification.</p>
 */
@Entity
@Table(name = "material_estimates")
public class MaterialEstimate extends BaseEntity {

    public enum Level {
        /** From the NIT. What we priced. */
        TENDER_BOQ,
        /** From working drawings. What the job actually needs. */
        EXECUTION_TAKEOFF,
        /** From a bar bending schedule. The steel case, and the only one usually complete. */
        BBS,
        REVISED
    }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /** Null means the estimate spans the whole project rather than one site. */
    @Column(name = "site_id", updatable = false)
    private UUID siteId;

    /** Null means project-wide. Otherwise this is the scope the variance may be computed over. */
    @Column(name = "boq_item_id", updatable = false)
    private UUID boqItemId;

    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;

    @Enumerated(EnumType.STRING)
    @Column(name = "estimate_level", nullable = false, length = 25, updatable = false)
    private Level estimateLevel;

    @Column(name = "estimated_qty_base", nullable = false, precision = 18, scale = 4)
    private BigDecimal estimatedQtyBase;

    /** The "add 3%" the field sheet applies for breakage and spillage. */
    @Column(name = "wastage_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal wastagePercent = BigDecimal.ZERO;

    /** Generated in the database from the two columns above; read-only here. */
    @Column(name = "qty_with_wastage", precision = 18, scale = 4,
            insertable = false, updatable = false)
    private BigDecimal qtyWithWastage;

    @Column(name = "revision", nullable = false)
    private int revision = 1;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom = LocalDate.now();

    @Column(name = "superseded_at")
    private Instant supersededAt;

    /** NIT number, drawing number, BBS sheet — where the figure came from. */
    @Column(name = "source_ref", length = 120)
    private String sourceRef;

    @Column(name = "derived_from_norm", nullable = false)
    private boolean derivedFromNorm;

    @Column(name = "notes")
    private String notes;

    protected MaterialEstimate() {
    }

    public MaterialEstimate(UUID orgId, UUID projectId, UUID siteId, UUID boqItemId,
                            UUID materialId, Level level, BigDecimal estimatedQtyBase,
                            BigDecimal wastagePercent, LocalDate effectiveFrom) {
        this.orgId = orgId;
        this.projectId = projectId;
        this.siteId = siteId;
        this.boqItemId = boqItemId;
        this.materialId = materialId;
        this.estimateLevel = level;
        this.estimatedQtyBase = estimatedQtyBase;
        this.wastagePercent = wastagePercent == null ? BigDecimal.ZERO : wastagePercent;
        this.effectiveFrom = effectiveFrom == null ? LocalDate.now() : effectiveFrom;
    }

    /**
     * Closes this revision so a new one can take its place. The row stays readable — a
     * variance quoted last month has to remain reproducible.
     */
    public void supersede(Instant at) {
        this.supersededAt = at;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    /**
     * The figure a variance is measured against: the estimate plus the wastage allowance
     * the estimator already expected to lose.
     *
     * <p>Falls back to computing it when the generated column has not been read back from
     * the database yet, which is the case in the same transaction that inserted the row.</p>
     */
    public BigDecimal comparableQuantity() {
        if (qtyWithWastage != null) {
            return qtyWithWastage;
        }
        return estimatedQtyBase
                .multiply(BigDecimal.ONE.add(
                        wastagePercent.divide(BigDecimal.valueOf(100), 6, java.math.RoundingMode.HALF_UP)))
                .setScale(4, java.math.RoundingMode.HALF_UP);
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getSiteId() {
        return siteId;
    }

    public UUID getBoqItemId() {
        return boqItemId;
    }

    public UUID getMaterialId() {
        return materialId;
    }

    public Level getEstimateLevel() {
        return estimateLevel;
    }

    public BigDecimal getEstimatedQtyBase() {
        return estimatedQtyBase;
    }

    public BigDecimal getWastagePercent() {
        return wastagePercent;
    }

    public BigDecimal getQtyWithWastage() {
        return comparableQuantity();
    }

    public int getRevision() {
        return revision;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public Instant getSupersededAt() {
        return supersededAt;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public boolean isDerivedFromNorm() {
        return derivedFromNorm;
    }

    public void setDerivedFromNorm(boolean derivedFromNorm) {
        this.derivedFromNorm = derivedFromNorm;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}

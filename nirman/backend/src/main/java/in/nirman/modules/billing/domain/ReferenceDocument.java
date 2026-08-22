package in.nirman.modules.billing.domain;

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
 * One edition of a document a bill is prepared against — a schedule of rates, a cost index
 * circular, a specification.
 *
 * <p>These are older than the bill, outlive the project, and are revised on somebody else's
 * timetable. What matters is not that the organisation has the latest one but that it can say
 * <b>which edition a given tender was priced under</b>, and produce it. A tender let in 2025
 * stays a DSR 2023 tender after DSR 2026 is published; that link is stored on
 * {@code agreement_documents} rather than looked up, because a bill that repriced itself when
 * the shelf changed would invent money in one direction or the other.</p>
 *
 * <p>{@link #supersede} marks an edition replaced and points the replacement back at it. It
 * moves nothing that already cites the old one — superseding says what to use next, not what
 * should have been used before.</p>
 */
@Entity
@Table(name = "reference_documents")
public class ReferenceDocument extends BaseEntity {

    /**
     * What authority the document carries, which decides what it may be attached to. DSR and
     * DAR price work; COST_INDEX moves a station's percentage; the rest are read by people and
     * price nothing.
     */
    public enum Kind { DSR, DAR, COST_INDEX, SPECIFICATION, CIRCULAR, OTHER }

    public enum Status { CURRENT, SUPERSEDED, WITHDRAWN }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 30)
    private Kind kind;

    @Column(name = "code", nullable = false, length = 60, updatable = false)
    private String code;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    /** The year the document is <i>of</i>, which is not always the year it was published. */
    @Column(name = "edition_year")
    private Integer editionYear;

    /** Cost index only: the station it applies to. */
    @Column(name = "station", length = 120)
    private String station;

    /**
     * Cost index only: the percentage it sets. A schedule of rates has no single number and
     * must never be given one — {@code ck_refdoc_index_percent} says the same in the schema.
     */
    @Column(name = "index_percent", precision = 9, scale = 4)
    private BigDecimal indexPercent;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    /**
     * The file itself. Null while an edition is registered but not yet found — a real state:
     * the office knows the tender cites DSR 2023 before it has a copy in hand.
     */
    @Column(name = "attachment_id")
    private UUID attachmentId;

    @Column(name = "supersedes_id")
    private UUID supersedesId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.CURRENT;

    @Column(name = "notes")
    private String notes;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected ReferenceDocument() {
    }

    public ReferenceDocument(UUID orgId, Kind kind, String code, String title) {
        this.orgId = orgId;
        this.kind = kind;
        this.code = code;
        this.title = title;
    }

    /**
     * Marks this edition replaced. Deliberately does not touch any tender already citing it:
     * superseding says what to use next, not what should have been used before.
     */
    public void supersede() {
        this.status = Status.SUPERSEDED;
    }

    public void withdraw() {
        this.status = Status.WITHDRAWN;
    }

    /** True when a bill may still be priced under it for a tender let now. */
    public boolean isCurrent() {
        return status == Status.CURRENT && deletedAt == null;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Integer getEditionYear() {
        return editionYear;
    }

    public void setEditionYear(Integer editionYear) {
        this.editionYear = editionYear;
    }

    public String getStation() {
        return station;
    }

    public void setStation(String station) {
        this.station = station;
    }

    public BigDecimal getIndexPercent() {
        return indexPercent;
    }

    public void setIndexPercent(BigDecimal indexPercent) {
        this.indexPercent = indexPercent;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(LocalDate effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public UUID getAttachmentId() {
        return attachmentId;
    }

    public void setAttachmentId(UUID attachmentId) {
        this.attachmentId = attachmentId;
    }

    public UUID getSupersedesId() {
        return supersedesId;
    }

    public void setSupersedesId(UUID supersedesId) {
        this.supersedesId = supersedesId;
    }

    public Status getStatus() {
        return status;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void markDeleted(Instant when) {
        this.deletedAt = when;
    }
}

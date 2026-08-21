package in.nirman.modules.billing.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A published schedule of rates — DSR 2023, DAR 2023 Vol. II, NDSR 2021.
 *
 * <p>CPWD publishes these free, and only as PDF: there is no official machine-readable form
 * and every "DSR in Excel" in circulation is somebody's re-keying. A government bill cannot
 * be priced off a stranger's retyped spreadsheet, so a schedule arrives by import and review
 * rather than by upload, and {@code sourceAttachmentId} keeps the original PDF beside the
 * rows so a disputed rate can be opened at its own page.</p>
 *
 * <p>Nothing prices a bill off a schedule in {@code DRAFT}. A rate is the multiplier on every
 * quantity in the document; one that nobody has eyeballed must not reach it.</p>
 */
@Entity
@Table(name = "dsr_schedules")
public class DsrSchedule extends BaseEntity {

    public enum Status { DRAFT, PUBLISHED, SUPERSEDED }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "code", nullable = false, length = 40, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** The year the rates are priced at, which is not always the year of publication. */
    @Column(name = "rate_year")
    private Integer rateYear;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "source_attachment_id")
    private UUID sourceAttachmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.DRAFT;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected DsrSchedule() {
    }

    public DsrSchedule(UUID orgId, String code, String name) {
        this.orgId = orgId;
        this.code = code;
        this.name = name;
    }

    public void publish() {
        this.status = Status.PUBLISHED;
    }

    public void supersede() {
        this.status = Status.SUPERSEDED;
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

    public void setName(String name) {
        this.name = name;
    }

    public Integer getRateYear() {
        return rateYear;
    }

    public void setRateYear(Integer rateYear) {
        this.rateYear = rateYear;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public UUID getSourceAttachmentId() {
        return sourceAttachmentId;
    }

    public void setSourceAttachmentId(UUID sourceAttachmentId) {
        this.sourceAttachmentId = sourceAttachmentId;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void markDeleted(Instant when) {
        this.deletedAt = when;
    }
}

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
 * One running account bill in a project's series — 1st RA, 2nd RA, Final.
 *
 * <p>A bill sweeps every signed measurement sheet measured on or before its cutoff date that
 * no earlier bill has claimed. "Since previous bill" is therefore its own sheets and "up to
 * date" is those plus every earlier bill's, both derived on read. Nobody ever again types a
 * hundred and fifteen previous-bill figures off the last bill's printout.</p>
 *
 * <p><b>Passing freezes it.</b> {@code ra_bill_items} is written once, at that moment, from
 * what the sheets then said, and is read for ever after instead of being recomputed — because
 * what was paid is a fact about the past and must not move when a later correction is
 * recorded. The daily progress report freezes its figures at handover for the same reason.</p>
 *
 * <p><b>A passed bill is never edited.</b> Sent back by the department before it is passed, it
 * re-opens through the same chain and counts the revision, the way an expense does. Once
 * passed, a correction is a fresh sheet with negative rows in the next bill.</p>
 */
@Entity
@Table(name = "ra_bills")
public class RaBill extends BaseEntity {

    public enum Status { DRAFT, SUBMITTED, CHECKED, PASSED }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "serial_no", nullable = false, updatable = false)
    private int serialNo;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "cutoff_date", nullable = false)
    private LocalDate cutoffDate;

    @Column(name = "previous_bill_id")
    private UUID previousBillId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.DRAFT;

    @Column(name = "frozen_at")
    private Instant frozenAt;

    @Column(name = "frozen_by")
    private UUID frozenBy;

    @Column(name = "gross_work_done", precision = 18, scale = 2)
    private BigDecimal grossWorkDone;

    @Column(name = "revision", nullable = false)
    private int revision;

    @Column(name = "remarks")
    private String remarks;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected RaBill() {
    }

    public RaBill(UUID orgId, UUID projectId, int serialNo, String title, LocalDate cutoffDate,
                  UUID previousBillId) {
        this.orgId = orgId;
        this.projectId = projectId;
        this.serialNo = serialNo;
        this.title = title;
        this.cutoffDate = cutoffDate;
        this.previousBillId = previousBillId;
    }

    /** The ordinal English name the department will print — "3rd RA Bill". */
    public static String defaultTitle(int serialNo) {
        int lastTwo = serialNo % 100;
        String suffix = (lastTwo >= 11 && lastTwo <= 13) ? "th" : switch (serialNo % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return serialNo + suffix + " RA Bill";
    }

    /** True while the bill's contents can still change — which is also while it may be discarded. */
    public boolean isOpen() {
        return status != Status.PASSED;
    }

    public void submit() {
        this.status = Status.SUBMITTED;
    }

    public void check() {
        this.status = Status.CHECKED;
    }

    public void pass(Instant when, UUID who, BigDecimal grossWorkDone) {
        this.status = Status.PASSED;
        this.frozenAt = when;
        this.frozenBy = who;
        this.grossWorkDone = grossWorkDone;
    }

    /** Sent back before it was passed: same number, approvals cancelled, revision counted. */
    public void reopen() {
        this.status = Status.DRAFT;
        this.revision = this.revision + 1;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public int getSerialNo() {
        return serialNo;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public LocalDate getCutoffDate() {
        return cutoffDate;
    }

    public void setCutoffDate(LocalDate cutoffDate) {
        this.cutoffDate = cutoffDate;
    }

    public UUID getPreviousBillId() {
        return previousBillId;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getFrozenAt() {
        return frozenAt;
    }

    public UUID getFrozenBy() {
        return frozenBy;
    }

    public BigDecimal getGrossWorkDone() {
        return grossWorkDone;
    }

    public int getRevision() {
        return revision;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void markDeleted(Instant when) {
        this.deletedAt = when;
    }
}

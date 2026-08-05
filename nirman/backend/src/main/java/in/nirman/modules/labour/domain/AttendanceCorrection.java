package in.nirman.modules.labour.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * One field of one attendance row, changed after it was verified.
 *
 * <p>A verified row has already paid the worker, so it is never edited quietly. Every field
 * that moves leaves a row here saying what it was, what it became, who changed it and why —
 * the paper trail an engineer needs when the muster roll and the field sheet disagree six
 * weeks later.</p>
 *
 * <p>The table carries an approval workflow it does not yet use. Corrections apply
 * immediately and are written {@code APPROVED} by the person who made them, because the
 * permission is the gate: only Admin and Engineer hold {@code attendance:correct}. When the
 * generic approval engine lands in Phase 5 this becomes {@code PENDING} on arrival without
 * a schema change, which is why the columns are here already.</p>
 *
 * <p>Deliberately not a {@code BaseEntity}: this table records who requested and when in its
 * own columns and has no created/updated audit pair to map.</p>
 */
@Entity
@Table(name = "attendance_corrections")
public class AttendanceCorrection {

    public enum ApprovalStatus { PENDING, APPROVED, REJECTED }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "attendance_id", nullable = false, updatable = false)
    private UUID attendanceId;

    @Column(name = "field_name", nullable = false, length = 40)
    private String fieldName;

    @Column(name = "previous_value", length = 200)
    private String previousValue;

    @Column(name = "new_value", length = 200)
    private String newValue;

    @Column(name = "correction_reason", nullable = false)
    private String correctionReason;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 20)
    private ApprovalStatus approvalStatus = ApprovalStatus.PENDING;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approval_remarks")
    private String approvalRemarks;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected AttendanceCorrection() {
    }

    /** An applied correction: raised and approved in one move by whoever holds the permission. */
    public static AttendanceCorrection applied(UUID orgId, UUID attendanceId, String fieldName,
                                               String previousValue, String newValue,
                                               String reason, UUID by, Instant at) {
        AttendanceCorrection correction = new AttendanceCorrection();
        correction.id = UUID.randomUUID();
        correction.orgId = orgId;
        correction.attendanceId = attendanceId;
        correction.fieldName = fieldName;
        correction.previousValue = previousValue;
        correction.newValue = newValue;
        correction.correctionReason = reason;
        correction.requestedBy = by;
        correction.requestedAt = at;
        correction.approvalStatus = ApprovalStatus.APPROVED;
        correction.approvedBy = by;
        correction.approvedAt = at;
        return correction;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAttendanceId() {
        return attendanceId;
    }

    public String getFieldName() {
        return fieldName;
    }

    public String getPreviousValue() {
        return previousValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public String getCorrectionReason() {
        return correctionReason;
    }

    public ApprovalStatus getApprovalStatus() {
        return approvalStatus;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }
}

package in.nirman.modules.labour.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * One worker, one site, one day.
 *
 * <p>The id is client-generated so a phone with no signal can create the row, re-send it
 * three times over a flaky connection, and still produce exactly one record.</p>
 *
 * <p>The wage snapshot — {@code wageRateId} and the two applied rates — is frozen at
 * verification and never recomputed afterwards. That is what makes a wage revision unable
 * to reprice a settled month: the rate that was agreed lives on the row itself, not only in
 * the rate table it came from.</p>
 */
@Entity
@Table(name = "attendance_records")
public class AttendanceRecord extends BaseEntity {

    /** Where the row came from. OFFLINE_SYNC rows are the ones worth auditing hardest. */
    public enum Source { ONLINE, OFFLINE_SYNC, IMPORT }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private UUID siteId;

    @Column(name = "worker_id", nullable = false, updatable = false)
    private UUID workerId;

    @Column(name = "attendance_date", nullable = false, updatable = false)
    private LocalDate attendanceDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private AttendanceStatus status;

    @Column(name = "check_in_time")
    private LocalTime checkInTime;

    @Column(name = "check_out_time")
    private LocalTime checkOutTime;

    @Column(name = "break_minutes", nullable = false)
    private int breakMinutes;

    /**
     * What the supervisor typed, as opposed to {@link #workedHours}, which is what the
     * calculator made of it. Null on rows whose hours came from the clock or from the
     * status alone, and the calculation must stay able to tell the difference — it re-runs
     * at verification, potentially months later.
     */
    @Column(name = "entered_hours", precision = 6, scale = 2)
    private BigDecimal enteredHours;

    @Column(name = "worked_hours", nullable = false, precision = 6, scale = 2)
    private BigDecimal workedHours = BigDecimal.ZERO;

    @Column(name = "regular_hours", nullable = false, precision = 6, scale = 2)
    private BigDecimal regularHours = BigDecimal.ZERO;

    @Column(name = "overtime_hours", nullable = false, precision = 6, scale = 2)
    private BigDecimal overtimeHours = BigDecimal.ZERO;

    @Column(name = "boq_item_id")
    private UUID boqItemId;

    @Column(name = "work_location", length = 150)
    private String workLocation;

    @Column(name = "overtime_reason", length = 300)
    private String overtimeReason;

    @Column(name = "remarks")
    private String remarks;

    @Column(name = "wage_rate_id")
    private UUID wageRateId;

    @Column(name = "applied_normal_rate", precision = 18, scale = 4)
    private BigDecimal appliedNormalRate;

    @Column(name = "applied_ot_rate", precision = 18, scale = 4)
    private BigDecimal appliedOtRate;

    @Column(name = "computed_wage_amount", precision = 18, scale = 2)
    private BigDecimal computedWageAmount;

    @Column(name = "computed_ot_amount", precision = 18, scale = 2)
    private BigDecimal computedOtAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "workflow_status", nullable = false, length = 20)
    private WorkflowStatus workflowStatus = WorkflowStatus.DRAFT;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "submitted_by")
    private UUID submittedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "verified_by")
    private UUID verifiedBy;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 15)
    private Source source = Source.ONLINE;

    protected AttendanceRecord() {
    }

    public AttendanceRecord(UUID id, UUID orgId, UUID projectId, UUID siteId, UUID workerId,
                            LocalDate attendanceDate, AttendanceStatus status) {
        setId(id);
        this.orgId = orgId;
        this.projectId = projectId;
        this.siteId = siteId;
        this.workerId = workerId;
        this.attendanceDate = attendanceDate;
        this.status = status;
    }

    // ------------------------------------------------------------------ the day's facts

    public void recordDay(AttendanceStatus status, LocalTime checkIn, LocalTime checkOut,
                          int breakMinutes, BigDecimal enteredHours, String overtimeReason,
                          UUID boqItemId, String workLocation, String remarks) {
        this.status = status;
        this.checkInTime = checkIn;
        this.checkOutTime = checkOut;
        this.breakMinutes = breakMinutes;
        // An unpaid day has no hours to assert, and leaving a stale figure on the row would
        // survive a switch from present to absent and quietly keep paying for it.
        this.enteredHours = status.isPaid() ? enteredHours : null;
        this.overtimeReason = overtimeReason;
        this.boqItemId = boqItemId;
        this.workLocation = workLocation;
        this.remarks = remarks;
    }

    /** Hours are facts about the clock, so they are stored whenever the row is saved. */
    public void applyHours(AttendanceCalculator.Result result) {
        this.workedHours = result.workedHours();
        this.regularHours = result.regularHours();
        this.overtimeHours = result.overtimeHours();
        this.computedWageAmount = result.wageAmount();
        this.computedOtAmount = result.overtimeAmount();
    }

    /**
     * Pins the money to this row for good. Called once, at verification, from the rate that
     * was in force on the attendance date.
     */
    public void freezeWage(UUID wageRateId, BigDecimal normalRate, BigDecimal otRate,
                           AttendanceCalculator.Result result) {
        this.wageRateId = wageRateId;
        this.appliedNormalRate = normalRate;
        this.appliedOtRate = otRate;
        applyHours(result);
    }

    // ------------------------------------------------------------------ workflow

    public void submit(Instant at, UUID by) {
        this.workflowStatus = WorkflowStatus.SUBMITTED;
        this.submittedAt = at;
        this.submittedBy = by;
        this.rejectionReason = null;
    }

    public void verify(Instant at, UUID by) {
        this.workflowStatus = WorkflowStatus.VERIFIED;
        this.verifiedAt = at;
        this.verifiedBy = by;
        this.rejectionReason = null;
    }

    public void reject(Instant at, UUID by, String reason) {
        this.workflowStatus = WorkflowStatus.REJECTED;
        this.verifiedAt = at;
        this.verifiedBy = by;
        this.rejectionReason = reason;
    }

    public void lock(Instant at) {
        this.workflowStatus = WorkflowStatus.LOCKED;
        this.lockedAt = at;
    }

    public void cancel() {
        this.workflowStatus = WorkflowStatus.CANCELLED;
    }

    // ------------------------------------------------------------------ accessors

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getSiteId() {
        return siteId;
    }

    public UUID getWorkerId() {
        return workerId;
    }

    public LocalDate getAttendanceDate() {
        return attendanceDate;
    }

    public AttendanceStatus getStatus() {
        return status;
    }

    public LocalTime getCheckInTime() {
        return checkInTime;
    }

    public LocalTime getCheckOutTime() {
        return checkOutTime;
    }

    public int getBreakMinutes() {
        return breakMinutes;
    }

    public BigDecimal getEnteredHours() {
        return enteredHours;
    }

    public BigDecimal getWorkedHours() {
        return workedHours;
    }

    public BigDecimal getRegularHours() {
        return regularHours;
    }

    public BigDecimal getOvertimeHours() {
        return overtimeHours;
    }

    public UUID getBoqItemId() {
        return boqItemId;
    }

    public String getWorkLocation() {
        return workLocation;
    }

    public String getOvertimeReason() {
        return overtimeReason;
    }

    public String getRemarks() {
        return remarks;
    }

    public UUID getWageRateId() {
        return wageRateId;
    }

    public BigDecimal getAppliedNormalRate() {
        return appliedNormalRate;
    }

    public BigDecimal getAppliedOtRate() {
        return appliedOtRate;
    }

    public BigDecimal getComputedWageAmount() {
        return computedWageAmount;
    }

    public BigDecimal getComputedOtAmount() {
        return computedOtAmount;
    }

    public BigDecimal getTotalAmount() {
        return nullToZero(computedWageAmount).add(nullToZero(computedOtAmount));
    }

    public WorkflowStatus getWorkflowStatus() {
        return workflowStatus;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public UUID getSubmittedBy() {
        return submittedBy;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public UUID getVerifiedBy() {
        return verifiedBy;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Source getSource() {
        return source;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}

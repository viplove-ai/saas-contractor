package in.nirman.modules.dpr.domain;

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
 * One site, one day: what was built, who was there, what it consumed, and what got in the way.
 *
 * <h2>The rolled-up figures, and why they are stored at all</h2>
 *
 * <p>A DPR duplicates numbers that already exist in labour, inventory and expense, which is
 * normally the wrong thing to do. It earns it here because a DPR is a <b>document</b>: it gets
 * printed, signed, and sent to the department, and the PDF issued on the 3rd must still say
 * what it said on the 3rd. Derive those figures on every read and a late attendance correction
 * silently rewrites a report somebody already acted on.</p>
 *
 * <p>So the snapshot is refreshed from the underlying records on every save <i>while the
 * report is a draft</i> — a draft that has gone stale is just wrong — and <b>frozen at
 * submission</b>. After that nothing recomputes it, and a correction to the underlying records
 * shows up as a difference between the report and today's figures rather than by changing the
 * report. That difference is visible information, not a bug.</p>
 *
 * <p>The id is client-generated so a report typed at site with no signal and synced three
 * times is one report.</p>
 */
@Entity
@Table(name = "daily_progress_reports")
public class DailyProgressReport extends BaseEntity {

    /**
     * Verification, not approval. A DPR takes one signature from the site engineer, so it
     * stays a per-module state machine rather than going through the generic approval engine
     * — the engine exists for the multi-level, amount-routed chains, and docs/09 open question
     * 2 is explicit that a single-level decision with no threshold is not drifting from it.
     */
    public enum Workflow {
        DRAFT,
        SUBMITTED,
        VERIFIED,
        /** Sent back by the engineer. Editable again, and re-submittable. */
        REJECTED;

        /** Draft and rejected reports are still the preparer's to change. */
        public boolean isEditable() {
            return this == DRAFT || this == REJECTED;
        }
    }

    /** What the day was like. Weather is the usual documented cause of a lost working hour. */
    public enum Weather { CLEAR, CLOUDY, RAIN, HEAVY_RAIN, EXTREME_HEAT }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private UUID siteId;

    @Column(name = "report_date", nullable = false, updatable = false)
    private LocalDate reportDate;

    @Column(name = "dpr_number", nullable = false, length = 50, updatable = false)
    private String dprNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "weather", length = 30)
    private Weather weather;

    @Column(name = "temperature_c", precision = 4, scale = 1)
    private BigDecimal temperatureC;

    /** Hours the site could not work, whatever the cause. Feeds the delay analysis. */
    @Column(name = "working_hours_lost", nullable = false, precision = 4, scale = 1)
    private BigDecimal workingHoursLost = BigDecimal.ZERO;

    // ---------------------------------------------------------------- frozen snapshot

    @Column(name = "labour_present_count")
    private Integer labourPresentCount;

    @Column(name = "labour_regular_hours", precision = 10, scale = 2)
    private BigDecimal labourRegularHours;

    @Column(name = "labour_overtime_hours", precision = 10, scale = 2)
    private BigDecimal labourOvertimeHours;

    /**
     * Contractor's men counted at the gate. Frozen beside the present count and never added
     * into it: these men have no hours and no wage behind them, so a reader who folded the
     * two together would divide the day's wage bill by the wrong number of people.
     */
    @Column(name = "outsourced_head_count", nullable = false)
    private int outsourcedHeadCount;

    /** Provisional until every underlying row is verified; the report says which. */
    @Column(name = "labour_cost", precision = 18, scale = 2)
    private BigDecimal labourCost;

    @Column(name = "material_received_value", precision = 18, scale = 2)
    private BigDecimal materialReceivedValue;

    @Column(name = "material_consumed_value", precision = 18, scale = 2)
    private BigDecimal materialConsumedValue;

    /**
     * <b>Cost incurred, not total booked.</b> Material purchases and wage disbursements are
     * excluded because they are costed elsewhere — the first becomes inventory and is costed
     * at issue, the second settles a wage already costed through attendance (docs/09). Booking
     * the total here would let a reader add it to the two figures above and overstate the day.
     */
    @Column(name = "expense_amount", precision = 18, scale = 2)
    private BigDecimal expenseAmount;

    // ---------------------------------------------------------------- the narrative

    @Column(name = "work_summary")
    private String workSummary;

    @Column(name = "delays")
    private String delays;

    @Column(name = "safety_observations")
    private String safetyObservations;

    @Column(name = "quality_observations")
    private String qualityObservations;

    @Column(name = "instructions_received")
    private String instructionsReceived;

    /** What the site needs the office to decide. The field most often the point of the report. */
    @Column(name = "management_attention")
    private String managementAttention;

    @Column(name = "next_day_plan")
    private String nextDayPlan;

    @Enumerated(EnumType.STRING)
    @Column(name = "workflow_status", nullable = false, length = 20)
    private Workflow workflowStatus = Workflow.DRAFT;

    @Column(name = "prepared_by")
    private UUID preparedBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "verified_by")
    private UUID verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "source", nullable = false, length = 15, updatable = false)
    private String source = "ONLINE";

    protected DailyProgressReport() {
    }

    public DailyProgressReport(UUID id, UUID orgId, UUID projectId, UUID siteId,
                               LocalDate reportDate, String dprNumber, UUID preparedBy) {
        setId(id);
        this.orgId = orgId;
        this.projectId = projectId;
        this.siteId = siteId;
        this.reportDate = reportDate;
        this.dprNumber = dprNumber;
        this.preparedBy = preparedBy;
    }

    /** The conditions the day was worked in, and what they cost in hours. */
    public void recordConditions(Weather weather, BigDecimal temperatureC,
                                 BigDecimal workingHoursLost) {
        this.weather = weather;
        this.temperatureC = temperatureC;
        this.workingHoursLost = workingHoursLost == null ? BigDecimal.ZERO : workingHoursLost;
    }

    public void recordNarrative(String workSummary, String delays, String safetyObservations,
                               String qualityObservations, String instructionsReceived,
                               String managementAttention, String nextDayPlan) {
        this.workSummary = workSummary;
        this.delays = delays;
        this.safetyObservations = safetyObservations;
        this.qualityObservations = qualityObservations;
        this.instructionsReceived = instructionsReceived;
        this.managementAttention = managementAttention;
        this.nextDayPlan = nextDayPlan;
    }

    /**
     * Writes the roll-up the other three modules produced.
     *
     * <p>Called on every save while the report is a draft, and once more at submission. Never
     * afterwards: a verified report is a document, and a document that changes is not one.</p>
     */
    public void applySnapshot(Integer presentCount, int outsourcedHeadCount,
                             BigDecimal regularHours,
                             BigDecimal overtimeHours, BigDecimal labourCost,
                             BigDecimal materialReceivedValue, BigDecimal materialConsumedValue,
                             BigDecimal costIncurred) {
        this.labourPresentCount = presentCount;
        this.outsourcedHeadCount = outsourcedHeadCount;
        this.labourRegularHours = regularHours;
        this.labourOvertimeHours = overtimeHours;
        this.labourCost = labourCost;
        this.materialReceivedValue = materialReceivedValue;
        this.materialConsumedValue = materialConsumedValue;
        this.expenseAmount = costIncurred;
    }

    public void submit(Instant at, UUID by) {
        this.workflowStatus = Workflow.SUBMITTED;
        this.submittedAt = at;
        this.preparedBy = by == null ? this.preparedBy : by;
        this.rejectionReason = null;
    }

    public void verify(Instant at, UUID by) {
        this.workflowStatus = Workflow.VERIFIED;
        this.verifiedAt = at;
        this.verifiedBy = by;
        this.rejectionReason = null;
    }

    public void reject(Instant at, UUID by, String reason) {
        this.workflowStatus = Workflow.REJECTED;
        this.verifiedAt = at;
        this.verifiedBy = by;
        this.rejectionReason = reason;
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

    public LocalDate getReportDate() {
        return reportDate;
    }

    public String getDprNumber() {
        return dprNumber;
    }

    public Weather getWeather() {
        return weather;
    }

    public BigDecimal getTemperatureC() {
        return temperatureC;
    }

    public BigDecimal getWorkingHoursLost() {
        return workingHoursLost;
    }

    public Integer getLabourPresentCount() {
        return labourPresentCount;
    }

    public int getOutsourcedHeadCount() {
        return outsourcedHeadCount;
    }

    public BigDecimal getLabourRegularHours() {
        return labourRegularHours;
    }

    public BigDecimal getLabourOvertimeHours() {
        return labourOvertimeHours;
    }

    public BigDecimal getLabourCost() {
        return labourCost;
    }

    public BigDecimal getMaterialReceivedValue() {
        return materialReceivedValue;
    }

    public BigDecimal getMaterialConsumedValue() {
        return materialConsumedValue;
    }

    public BigDecimal getExpenseAmount() {
        return expenseAmount;
    }

    public String getWorkSummary() {
        return workSummary;
    }

    public String getDelays() {
        return delays;
    }

    public String getSafetyObservations() {
        return safetyObservations;
    }

    public String getQualityObservations() {
        return qualityObservations;
    }

    public String getInstructionsReceived() {
        return instructionsReceived;
    }

    public String getManagementAttention() {
        return managementAttention;
    }

    public String getNextDayPlan() {
        return nextDayPlan;
    }

    public Workflow getWorkflowStatus() {
        return workflowStatus;
    }

    public UUID getPreparedBy() {
        return preparedBy;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public UUID getVerifiedBy() {
        return verifiedBy;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}

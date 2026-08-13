package in.nirman.modules.planning.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A construction programme, frozen.
 *
 * <p>The third kind of number this system holds. A recorded fact is append-only; a derived figure
 * is computed per call and never stored; a <b>baseline</b> is computed once and then stops
 * changing, so that reality can be measured against it. A plan that recomputed itself would show
 * every project on target forever, because the target would follow the work.</p>
 *
 * <p>{@link #getProjectId()} is nullable and that is the design: a pre-award plan belongs to no
 * project, because deciding whether to bid and at what percentage happens before there is
 * anything to attach to. Winning attaches it, and nothing is recomputed on the way — what was
 * decided is what is kept.</p>
 *
 * <p>A draft may be replaced freely. {@link #baseline} is the irreversible act, and it is the
 * one that writes the planned dates and budgets forward onto {@code boq_items} — columns V1
 * provisioned for a planner and nothing has written since.</p>
 */
@Entity
@Table(name = "execution_plans")
public class ExecutionPlan extends BaseEntity {

    public static final String SOURCE_PROJECT = "PROJECT";
    public static final String SOURCE_NIT_UPLOAD = "NIT_UPLOAD";

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;
    @Column(name = "project_id")
    private UUID projectId;
    @Column(name = "nit_document_id")
    private UUID nitDocumentId;
    @Column(name = "work_type_profile_id")
    private UUID workTypeProfileId;
    @Column(name = "name", nullable = false, length = 200)
    private String name;
    @Column(name = "source", nullable = false, length = 20)
    private String source;
    @Column(name = "scenario", nullable = false, length = 60)
    private String scenario = "BASE";
    @Column(name = "commencement_date", nullable = false)
    private LocalDate commencementDate;
    @Column(name = "allowed_days", nullable = false)
    private int allowedDays;
    @Column(name = "quoted_percent", nullable = false, precision = 7, scale = 3)
    private BigDecimal quotedPercent = BigDecimal.ZERO;
    @Column(name = "contract_value", precision = 18, scale = 2)
    private BigDecimal contractValue;
    @Column(name = "payment_lag_days", nullable = false)
    private int paymentLagDays = 45;
    @Column(name = "peak_funding_required", precision = 18, scale = 2)
    private BigDecimal peakFundingRequired;
    @Column(name = "peak_month", length = 7)
    private String peakMonth;
    @Column(name = "money_before_day_one", precision = 18, scale = 2)
    private BigDecimal moneyBeforeDayOne;
    @Column(name = "break_even_month", length = 7)
    private String breakEvenMonth;
    @Column(name = "total_retention_held", precision = 18, scale = 2)
    private BigDecimal totalRetentionHeld;
    @Column(name = "retention_released_on")
    private LocalDate retentionReleasedOn;
    @Column(name = "total_outflow", precision = 18, scale = 2)
    private BigDecimal totalOutflow;
    @Column(name = "total_net_receipts", precision = 18, scale = 2)
    private BigDecimal totalNetReceipts;
    @Column(name = "baselined_at")
    private Instant baselinedAt;
    @Column(name = "baselined_by")
    private UUID baselinedBy;
    @Column(name = "revision", nullable = false)
    private int revision = 1;
    @Column(name = "superseded_at")
    private Instant supersededAt;
    @Column(name = "engine_version", nullable = false, length = 20)
    private String engineVersion;
    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected ExecutionPlan() {
    }

    public ExecutionPlan(UUID orgId, UUID projectId, String name, String source,
                         String scenario, LocalDate commencementDate, int allowedDays,
                         BigDecimal quotedPercent, BigDecimal contractValue, int paymentLagDays,
                         String engineVersion) {
        this.orgId = orgId;
        this.projectId = projectId;
        this.name = name;
        this.source = source;
        this.scenario = scenario == null || scenario.isBlank() ? "BASE" : scenario;
        this.commencementDate = commencementDate;
        this.allowedDays = allowedDays;
        this.quotedPercent = quotedPercent == null ? BigDecimal.ZERO : quotedPercent;
        this.contractValue = contractValue;
        this.paymentLagDays = paymentLagDays;
        this.engineVersion = engineVersion;
    }

    public void recordOutcome(BigDecimal peakFundingRequired, String peakMonth,
                              BigDecimal moneyBeforeDayOne, String breakEvenMonth,
                              BigDecimal totalRetentionHeld, LocalDate retentionReleasedOn,
                              BigDecimal totalOutflow, BigDecimal totalNetReceipts) {
        this.peakFundingRequired = peakFundingRequired;
        this.peakMonth = peakMonth;
        this.moneyBeforeDayOne = moneyBeforeDayOne;
        this.breakEvenMonth = breakEvenMonth;
        this.totalRetentionHeld = totalRetentionHeld;
        this.retentionReleasedOn = retentionReleasedOn;
        this.totalOutflow = totalOutflow;
        this.totalNetReceipts = totalNetReceipts;
    }

    public void describe(UUID nitDocumentId, UUID workTypeProfileId) {
        this.nitDocumentId = nitDocumentId;
        this.workTypeProfileId = workTypeProfileId;
    }

    /** Freezes the plan. From here it is superseded rather than edited. */
    public void baseline(UUID by) {
        this.baselinedAt = Instant.now();
        this.baselinedBy = by;
    }

    /** A later revision replaces this one; the old figures stay readable. */
    public void supersede() {
        this.supersededAt = Instant.now();
    }

    public void attachTo(UUID projectId) {
        this.projectId = projectId;
    }

    public void nextRevisionAfter(int previous) {
        this.revision = previous + 1;
    }

    public boolean isBaselined() {
        return baselinedAt != null;
    }

    public UUID getOrgId() { return orgId; }
    public UUID getProjectId() { return projectId; }
    public UUID getNitDocumentId() { return nitDocumentId; }
    public UUID getWorkTypeProfileId() { return workTypeProfileId; }
    public String getName() { return name; }
    public String getSource() { return source; }
    public String getScenario() { return scenario; }
    public LocalDate getCommencementDate() { return commencementDate; }
    public int getAllowedDays() { return allowedDays; }
    public BigDecimal getQuotedPercent() { return quotedPercent; }
    public BigDecimal getContractValue() { return contractValue; }
    public int getPaymentLagDays() { return paymentLagDays; }
    public BigDecimal getPeakFundingRequired() { return peakFundingRequired; }
    public String getPeakMonth() { return peakMonth; }
    public BigDecimal getMoneyBeforeDayOne() { return moneyBeforeDayOne; }
    public String getBreakEvenMonth() { return breakEvenMonth; }
    public BigDecimal getTotalRetentionHeld() { return totalRetentionHeld; }
    public LocalDate getRetentionReleasedOn() { return retentionReleasedOn; }
    public BigDecimal getTotalOutflow() { return totalOutflow; }
    public BigDecimal getTotalNetReceipts() { return totalNetReceipts; }
    public Instant getBaselinedAt() { return baselinedAt; }
    public UUID getBaselinedBy() { return baselinedBy; }
    public int getRevision() { return revision; }
    public Instant getSupersededAt() { return supersededAt; }
    public String getEngineVersion() { return engineVersion; }
    public Instant getDeletedAt() { return deletedAt; }
}

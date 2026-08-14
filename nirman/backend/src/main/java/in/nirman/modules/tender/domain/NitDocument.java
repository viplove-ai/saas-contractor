package in.nirman.modules.tender.domain;

import in.nirman.common.BaseEntity;
import in.nirman.modules.tender.parser.AllowedTime;
import in.nirman.modules.tender.parser.NitExtraction;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * The Notice Inviting Tender a project was won under, as read from the PDF.
 *
 * <p>The priced lines live in {@code boq_items}, because those are what work is charged
 * against. What is kept here is everything else the notice said — the earnest money, the
 * deadlines, the eligibility rule, the rate schedule the prices were built on. None of it is
 * needed to run the project day to day, which is precisely why it goes missing otherwise.</p>
 */
@Entity
@Table(name = "nit_documents")
public class NitDocument extends BaseEntity {

    /**
     * A tender's dates are printed as local wall-clock time and mean nothing anywhere else:
     * "up to 15:30 on 01.07.2026" is half past three in Delhi.
     */
    public static final ZoneId TENDER_ZONE = ZoneId.of("Asia/Kolkata");

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /** The stored PDF. Null once the file is gone; the reading of it survives. */
    @Column(name = "attachment_id")
    private UUID attachmentId;

    @Column(name = "file_name", nullable = false, length = 200)
    private String fileName;

    @Column(name = "page_count", nullable = false)
    private int pageCount;

    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Column(name = "parser_version", nullable = false, length = 20)
    private String parserVersion = NitExtraction.PARSER_VERSION;

    @Column(name = "nit_no", length = 120)
    private String nitNo;

    @Column(name = "work_name")
    private String workName;

    @Column(name = "estimated_cost", precision = 18, scale = 2)
    private BigDecimal estimatedCost;

    @Column(name = "civil_estimated_cost", precision = 18, scale = 2)
    private BigDecimal civilEstimatedCost;

    @Column(name = "electrical_estimated_cost", precision = 18, scale = 2)
    private BigDecimal electricalEstimatedCost;

    @Column(name = "emd_amount", precision = 18, scale = 2)
    private BigDecimal emdAmount;

    @Column(name = "completion_period", length = 80)
    private String completionPeriod;

    @Column(name = "submission_closing")
    private Instant submissionClosing;

    @Column(name = "bid_opening")
    private Instant bidOpening;

    @Column(name = "division", length = 120)
    private String division;

    @Column(name = "location", length = 300)
    private String location;

    @Column(name = "bid_type", length = 40)
    private String bidType;

    @Column(name = "contractor_eligibility")
    private String contractorEligibility;

    @Column(name = "similar_work_criteria")
    private String similarWorkCriteria;

    @Column(name = "performance_guarantee_percent", precision = 6, scale = 3)
    private BigDecimal performanceGuaranteePercent;

    @Column(name = "security_deposit_percent", precision = 6, scale = 3)
    private BigDecimal securityDepositPercent;

    @Column(name = "civil_dsr_year")
    private Integer civilDsrYear;

    @Column(name = "civil_cost_index_percent", precision = 7, scale = 3)
    private BigDecimal civilCostIndexPercent;

    @Column(name = "electrical_dsr_year")
    private Integer electricalDsrYear;

    @Column(name = "electrical_cost_index_percent", precision = 7, scale = 3)
    private BigDecimal electricalCostIndexPercent;

    /**
     * The time allowed, as a number rather than as the words {@link #completionPeriod} keeps.
     * The unit is stored rather than folded into days because a CPWD month is a calendar month,
     * and the drift matters on a date that decides whether a milestone was met.
     */
    @Column(name = "completion_value")
    private Integer completionValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "completion_unit", length = 10)
    private AllowedTime.Unit completionUnit;

    @Column(name = "start_reckoning_days")
    private Integer startReckoningDays;

    /** Null where the notice defers the answer. A gate on being paid at all, not a deduction. */
    @Column(name = "clause_7a_applicable")
    private Boolean clause7aApplicable;

    /**
     * The additional performance guarantee clause. Null where the notice is silent, which is
     * nine of the ten read so far — and applying one anyway would ask a contractor for lakhs of
     * bank guarantee that nobody demanded.
     */
    @Column(name = "apg_threshold_percent", precision = 6, scale = 3)
    private BigDecimal apgThresholdPercent;

    @Column(name = "apg_method", length = 20)
    private String apgMethod;

    @Column(name = "apg_percent", precision = 6, scale = 3)
    private BigDecimal apgPercent;

    @Column(name = "boq_total", precision = 18, scale = 2)
    private BigDecimal boqTotal;

    @Column(name = "extracted_item_count", nullable = false)
    private int extractedItemCount;

    @Convert(converter = WarningListConverter.class)
    @Column(name = "warnings")
    private List<String> warnings = List.of();

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected NitDocument() {
    }

    public NitDocument(UUID orgId, UUID projectId, String fileName, int pageCount) {
        this.orgId = orgId;
        this.projectId = projectId;
        this.fileName = fileName;
        this.pageCount = pageCount;
    }

    /**
     * Copies a reading of the notice onto the record.
     *
     * <p>Takes the fields the user confirmed rather than the raw extraction, because by the
     * time this is called the preview has been reviewed and corrected. What is stored is what
     * the person signing off said the tender says.</p>
     */
    public void applyFields(String nitNo, String workName, BigDecimal estimatedCost,
                            BigDecimal civilEstimatedCost, BigDecimal electricalEstimatedCost,
                            BigDecimal emdAmount, String completionPeriod,
                            LocalDateTime submissionClosing, LocalDateTime bidOpening,
                            String division, String location, String bidType,
                            String contractorEligibility, String similarWorkCriteria,
                            BigDecimal performanceGuaranteePercent,
                            BigDecimal securityDepositPercent,
                            Integer civilDsrYear, BigDecimal civilCostIndexPercent,
                            Integer electricalDsrYear, BigDecimal electricalCostIndexPercent) {
        this.nitNo = nitNo;
        this.workName = workName;
        this.estimatedCost = estimatedCost;
        this.civilEstimatedCost = civilEstimatedCost;
        this.electricalEstimatedCost = electricalEstimatedCost;
        this.emdAmount = emdAmount;
        this.completionPeriod = completionPeriod;
        this.submissionClosing = toInstant(submissionClosing);
        this.bidOpening = toInstant(bidOpening);
        this.division = division;
        this.location = location;
        this.bidType = bidType;
        this.contractorEligibility = contractorEligibility;
        this.similarWorkCriteria = similarWorkCriteria;
        this.performanceGuaranteePercent = performanceGuaranteePercent;
        this.securityDepositPercent = securityDepositPercent;
        this.civilDsrYear = civilDsrYear;
        this.civilCostIndexPercent = civilCostIndexPercent;
        this.electricalDsrYear = electricalDsrYear;
        this.electricalCostIndexPercent = electricalCostIndexPercent;
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(TENDER_ZONE).toInstant();
    }

    public LocalDateTime getSubmissionClosingLocal() {
        return submissionClosing == null
                ? null : LocalDateTime.ofInstant(submissionClosing, TENDER_ZONE);
    }

    public LocalDateTime getBidOpeningLocal() {
        return bidOpening == null ? null : LocalDateTime.ofInstant(bidOpening, TENDER_ZONE);
    }

    public void recordSchedule(BigDecimal boqTotal, int extractedItemCount, List<String> warnings) {
        this.boqTotal = boqTotal;
        this.extractedItemCount = extractedItemCount;
        this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * The contractual terms, which unlike {@link #applyFields} are taken from the reader rather
     * than from the user.
     *
     * <p>These are not fields anybody retypes: the milestone table is copied out of Schedule F
     * whole, and a person correcting it row by row would be transcribing the contract rather
     * than reviewing an extraction. Where the reader found nothing, nothing is stored, and the
     * warning list says so.</p>
     */
    public void applyScheduleTerms(AllowedTime completionTime, Integer startReckoningDays,
                                   Boolean clause7aApplicable) {
        this.completionValue = completionTime == null ? null : completionTime.value();
        this.completionUnit = completionTime == null ? null : completionTime.unit();
        this.startReckoningDays = startReckoningDays;
        this.clause7aApplicable = clause7aApplicable;
    }

    /** @return the time allowed, or null where the notice did not say in a form we could read */
    public AllowedTime getCompletionTime() {
        return completionValue == null || completionUnit == null
                ? null : new AllowedTime(completionValue, completionUnit);
    }

    public Integer getStartReckoningDays() {
        return startReckoningDays;
    }

    public Boolean getClause7aApplicable() {
        return clause7aApplicable;
    }

    public void applyGuaranteeTerms(BigDecimal apgThresholdPercent, String apgMethod,
                                    BigDecimal apgPercent) {
        this.apgThresholdPercent = apgThresholdPercent;
        this.apgMethod = apgMethod;
        this.apgPercent = apgPercent;
    }

    public BigDecimal getApgThresholdPercent() {
        return apgThresholdPercent;
    }

    public String getApgMethod() {
        return apgMethod;
    }

    public BigDecimal getApgPercent() {
        return apgPercent;
    }

    public void attachTo(UUID attachmentId, String checksumSha256) {
        this.attachmentId = attachmentId;
        this.checksumSha256 = checksumSha256;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getAttachmentId() {
        return attachmentId;
    }

    public String getFileName() {
        return fileName;
    }

    public int getPageCount() {
        return pageCount;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public String getParserVersion() {
        return parserVersion;
    }

    public String getNitNo() {
        return nitNo;
    }

    public String getWorkName() {
        return workName;
    }

    public BigDecimal getEstimatedCost() {
        return estimatedCost;
    }

    public BigDecimal getCivilEstimatedCost() {
        return civilEstimatedCost;
    }

    public BigDecimal getElectricalEstimatedCost() {
        return electricalEstimatedCost;
    }

    public BigDecimal getEmdAmount() {
        return emdAmount;
    }

    public String getCompletionPeriod() {
        return completionPeriod;
    }

    public String getDivision() {
        return division;
    }

    public String getLocation() {
        return location;
    }

    public String getBidType() {
        return bidType;
    }

    public String getContractorEligibility() {
        return contractorEligibility;
    }

    public String getSimilarWorkCriteria() {
        return similarWorkCriteria;
    }

    public BigDecimal getPerformanceGuaranteePercent() {
        return performanceGuaranteePercent;
    }

    public BigDecimal getSecurityDepositPercent() {
        return securityDepositPercent;
    }

    public Integer getCivilDsrYear() {
        return civilDsrYear;
    }

    public BigDecimal getCivilCostIndexPercent() {
        return civilCostIndexPercent;
    }

    public Integer getElectricalDsrYear() {
        return electricalDsrYear;
    }

    public BigDecimal getElectricalCostIndexPercent() {
        return electricalCostIndexPercent;
    }

    public BigDecimal getBoqTotal() {
        return boqTotal;
    }

    public int getExtractedItemCount() {
        return extractedItemCount;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}

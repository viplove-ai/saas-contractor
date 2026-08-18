package in.nirman.modules.project.domain;

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

/** A contract won from a client department. Sites execute it; the BOQ prices it. */
@Entity
@Table(name = "projects")
public class Project extends BaseEntity {

    public enum Status { PLANNED, ACTIVE, ON_HOLD, COMPLETED, CLOSED }

    /**
     * Which release rule the performance guarantee follows — a year after a construction
     * contract completes, six months after the department's letter on a maintenance one.
     * Null means nobody has said, and the treasury register then proposes no release date
     * rather than guessing at one.
     */
    public enum WorkNature { CONSTRUCTION, MAINTENANCE }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "code", nullable = false, length = 40, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "client_department", length = 200)
    private String clientDepartment;

    @Column(name = "agreement_no", length = 80)
    private String agreementNo;

    @Column(name = "nit_number", length = 80)
    private String nitNumber;

    @Column(name = "tender_reference", length = 120)
    private String tenderReference;

    @Column(name = "contract_value", precision = 18, scale = 2)
    private BigDecimal contractValue;

    /**
     * The bid: a percentage above (+) or below (-) the estimated cost.
     *
     * <p>Every rupee a plan predicts moves with this, and it is the one figure no reading of the
     * notice can supply — when the tender is read the quote has not been decided. Null on a
     * project created before the column existed; a zero would be a claim that the work was bid
     * at par, which is a different statement from not knowing.</p>
     */
    @Column(name = "quoted_percent", precision = 7, scale = 3)
    private BigDecimal quotedPercent;

    /**
     * The estimated cost put to tender. Not the same figure as {@link #contractValue}, and the
     * difference is the whole of the guarantee arithmetic: a performance guarantee is five per
     * cent of this <em>or</em> of the contract, whichever is higher, so bidding thirty per cent
     * below leaves the guarantee standing on the full estimate.
     */
    @Column(name = "estimated_cost", precision = 18, scale = 2)
    private BigDecimal estimatedCost;

    @Column(name = "budget_amount", precision = 18, scale = 2)
    private BigDecimal budgetAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_nature", length = 20)
    private WorkNature workNature;

    /** The allotment letter is due within ten days of this, and the earnest money with it. */
    @Column(name = "bid_opening_date")
    private LocalDate bidOpeningDate;

    /** The letter that turns a bid into a contract: it frees the EMD and starts the PG clock. */
    @Column(name = "allotment_letter_date")
    private LocalDate allotmentLetterDate;

    /**
     * The department's completion letter, which is not the day the work stopped. Kept apart
     * from {@link #actualCompletionDate} on purpose — the contractor knows when he finished,
     * and only the department's letter starts the clock a guarantee is released against.
     */
    @Column(name = "completion_certificate_date")
    private LocalDate completionCertificateDate;

    /** How long the retention is held after completion. Varies with the item and the work. */
    @Column(name = "defect_liability_months")
    private Integer defectLiabilityMonths;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "expected_completion_date")
    private LocalDate expectedCompletionDate;

    @Column(name = "actual_completion_date")
    private LocalDate actualCompletionDate;

    @Column(name = "project_manager_id")
    private UUID projectManagerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Column(name = "description")
    private String description;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private UUID deletedBy;

    @Column(name = "deleted_reason", length = 500)
    private String deletedReason;

    protected Project() {
    }

    public Project(UUID orgId, String code, String name) {
        this.orgId = orgId;
        this.code = code;
        this.name = name;
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

    public String getClientDepartment() {
        return clientDepartment;
    }

    public void setClientDepartment(String clientDepartment) {
        this.clientDepartment = clientDepartment;
    }

    public String getAgreementNo() {
        return agreementNo;
    }

    public void setAgreementNo(String agreementNo) {
        this.agreementNo = agreementNo;
    }

    public String getNitNumber() {
        return nitNumber;
    }

    public void setNitNumber(String nitNumber) {
        this.nitNumber = nitNumber;
    }

    public String getTenderReference() {
        return tenderReference;
    }

    public void setTenderReference(String tenderReference) {
        this.tenderReference = tenderReference;
    }

    public BigDecimal getContractValue() {
        return contractValue;
    }

    public void setContractValue(BigDecimal contractValue) {
        this.contractValue = contractValue;
    }

    public BigDecimal getQuotedPercent() {
        return quotedPercent;
    }

    public void setQuotedPercent(BigDecimal quotedPercent) {
        this.quotedPercent = quotedPercent;
    }

    public BigDecimal getEstimatedCost() {
        return estimatedCost;
    }

    public void setEstimatedCost(BigDecimal estimatedCost) {
        this.estimatedCost = estimatedCost;
    }

    public WorkNature getWorkNature() {
        return workNature;
    }

    public void setWorkNature(WorkNature workNature) {
        this.workNature = workNature;
    }

    public LocalDate getBidOpeningDate() {
        return bidOpeningDate;
    }

    public void setBidOpeningDate(LocalDate bidOpeningDate) {
        this.bidOpeningDate = bidOpeningDate;
    }

    public LocalDate getAllotmentLetterDate() {
        return allotmentLetterDate;
    }

    public void setAllotmentLetterDate(LocalDate allotmentLetterDate) {
        this.allotmentLetterDate = allotmentLetterDate;
    }

    public LocalDate getCompletionCertificateDate() {
        return completionCertificateDate;
    }

    public void setCompletionCertificateDate(LocalDate completionCertificateDate) {
        this.completionCertificateDate = completionCertificateDate;
    }

    public Integer getDefectLiabilityMonths() {
        return defectLiabilityMonths;
    }

    public void setDefectLiabilityMonths(Integer defectLiabilityMonths) {
        this.defectLiabilityMonths = defectLiabilityMonths;
    }

    /**
     * The day a guarantee's release clock starts: the department's completion letter where one
     * has arrived, and otherwise the day work actually finished. Null while the work runs.
     */
    public LocalDate guaranteeClockStart() {
        return completionCertificateDate != null ? completionCertificateDate : actualCompletionDate;
    }

    public BigDecimal getBudgetAmount() {
        return budgetAmount;
    }

    public void setBudgetAmount(BigDecimal budgetAmount) {
        this.budgetAmount = budgetAmount;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getExpectedCompletionDate() {
        return expectedCompletionDate;
    }

    public void setExpectedCompletionDate(LocalDate expectedCompletionDate) {
        this.expectedCompletionDate = expectedCompletionDate;
    }

    public LocalDate getActualCompletionDate() {
        return actualCompletionDate;
    }

    public void setActualCompletionDate(LocalDate actualCompletionDate) {
        this.actualCompletionDate = actualCompletionDate;
    }

    public UUID getProjectManagerId() {
        return projectManagerId;
    }

    public void setProjectManagerId(UUID projectManagerId) {
        this.projectManagerId = projectManagerId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public UUID getDeletedBy() {
        return deletedBy;
    }

    public String getDeletedReason() {
        return deletedReason;
    }

    /**
     * Takes the project off the books. Not a lifecycle step — {@link Status#CLOSED} is how a
     * contract ends, and a closed project stays on every list. This is for the one that
     * should never have been created.
     */
    public void delete(Instant at, UUID by, String reason) {
        this.deletedAt = at;
        this.deletedBy = by;
        this.deletedReason = reason;
    }

    /** Puts it back, and forgets the reason: it no longer describes anything true. */
    public void restore() {
        this.deletedAt = null;
        this.deletedBy = null;
        this.deletedReason = null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}

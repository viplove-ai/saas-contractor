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

    @Column(name = "budget_amount", precision = 18, scale = 2)
    private BigDecimal budgetAmount;

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

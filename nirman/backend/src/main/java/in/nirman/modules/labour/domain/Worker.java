package in.nirman.modules.labour.domain;

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
 * A labourer on site. Deliberately not a {@code User}: workers have no login, and the
 * distinction matters downstream — {@code worker_advances} pays a worker, while
 * {@code site_advances} issues petty cash to a user who can sign in.
 *
 * <p>Only {@code aadhaar_last4} is kept, never the full number. Four digits are enough to
 * tell two men with the same name apart on a muster roll, which is the only reason the
 * field needs to exist.</p>
 */
@Entity
@Table(name = "workers")
public class Worker extends BaseEntity {

    public enum EmploymentType { PERMANENT, CONTRACT, CASUAL }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "worker_code", nullable = false, length = 40, updatable = false)
    private String workerCode;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "mobile", length = 20)
    private String mobile;

    @Column(name = "photo_attachment_id")
    private UUID photoAttachmentId;

    @Column(name = "skill_category_id")
    private UUID skillCategoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type", nullable = false, length = 20)
    private EmploymentType employmentType = EmploymentType.CONTRACT;

    @Column(name = "labour_supplier_id")
    private UUID labourSupplierId;

    @Enumerated(EnumType.STRING)
    @Column(name = "wage_type", nullable = false, length = 10)
    private WageType wageType = WageType.DAILY;

    @Column(name = "joining_date")
    private LocalDate joiningDate;

    @Column(name = "exit_date")
    private LocalDate exitDate;

    @Column(name = "aadhaar_last4", columnDefinition = "char(4)")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    private String aadhaarLast4;

    @Column(name = "bank_account_no", length = 30)
    private String bankAccountNo;

    @Column(name = "bank_ifsc", length = 15)
    private String bankIfsc;

    @Column(name = "bank_name", length = 120)
    private String bankName;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private UUID deletedBy;

    @Column(name = "deleted_reason", length = 500)
    private String deletedReason;

    protected Worker() {
    }

    public Worker(UUID orgId, String workerCode, String fullName, WageType wageType) {
        this.orgId = orgId;
        this.workerCode = workerCode;
        this.fullName = fullName;
        this.wageType = wageType;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getWorkerCode() {
        return workerCode;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getMobile() {
        return mobile;
    }

    public void setMobile(String mobile) {
        this.mobile = mobile;
    }

    public UUID getPhotoAttachmentId() {
        return photoAttachmentId;
    }

    public void setPhotoAttachmentId(UUID photoAttachmentId) {
        this.photoAttachmentId = photoAttachmentId;
    }

    public UUID getSkillCategoryId() {
        return skillCategoryId;
    }

    public void setSkillCategoryId(UUID skillCategoryId) {
        this.skillCategoryId = skillCategoryId;
    }

    public EmploymentType getEmploymentType() {
        return employmentType;
    }

    public void setEmploymentType(EmploymentType employmentType) {
        this.employmentType = employmentType;
    }

    public UUID getLabourSupplierId() {
        return labourSupplierId;
    }

    public void setLabourSupplierId(UUID labourSupplierId) {
        this.labourSupplierId = labourSupplierId;
    }

    public WageType getWageType() {
        return wageType;
    }

    public void setWageType(WageType wageType) {
        this.wageType = wageType;
    }

    public LocalDate getJoiningDate() {
        return joiningDate;
    }

    public void setJoiningDate(LocalDate joiningDate) {
        this.joiningDate = joiningDate;
    }

    public LocalDate getExitDate() {
        return exitDate;
    }

    public void setExitDate(LocalDate exitDate) {
        this.exitDate = exitDate;
    }

    public String getAadhaarLast4() {
        return aadhaarLast4;
    }

    public void setAadhaarLast4(String aadhaarLast4) {
        this.aadhaarLast4 = aadhaarLast4;
    }

    public String getBankAccountNo() {
        return bankAccountNo;
    }

    public void setBankAccountNo(String bankAccountNo) {
        this.bankAccountNo = bankAccountNo;
    }

    public String getBankIfsc() {
        return bankIfsc;
    }

    public void setBankIfsc(String bankIfsc) {
        this.bankIfsc = bankIfsc;
    }

    public String getBankName() {
        return bankName;
    }

    public void setBankName(String bankName) {
        this.bankName = bankName;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
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

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * Takes the man off every list, keeping the row and why it went.
     *
     * <p>Not the same act as {@link #setActive(boolean)}, and the difference is the point: a
     * worker marked inactive left the job, and his months still carry wages and count towards
     * what the site cost. A deleted worker never worked here at all — he is the duplicate, the
     * name typed into the wrong site, the row that should not exist. Only such a row is
     * allowed here; {@code WorkerService} refuses one carrying any history.</p>
     */
    public void delete(Instant at, UUID by, String reason) {
        this.deletedAt = at;
        this.deletedBy = by;
        this.deletedReason = reason;
        // He is nobody's man now: leaving him active would keep him in every count that asks
        // for live workers without filtering the deletion out.
        this.active = false;
    }
}

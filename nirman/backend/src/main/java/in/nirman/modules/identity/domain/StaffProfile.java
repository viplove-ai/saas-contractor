package in.nirman.modules.identity.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What an employer holds about somebody on the payroll.
 *
 * <p>Deliberately not columns on {@link User}. A user row is a login: it is loaded on every
 * sign-in and every refresh, with its roles eager, and it has no business carrying a home
 * address and a bank account down that path. These fields are read on one screen by one
 * role, and keeping them apart is what lets the permission be separate too.</p>
 *
 * <p>One row per member, edited in place. An address that changes is a correction, not
 * history — unlike pay, which is {@link StaffSalaryRevision} precisely because it is.</p>
 */
@Entity
@Table(name = "staff_profiles")
public class StaffProfile extends BaseEntity {

    /**
     * How somebody is engaged. PROBATION is the one that ends: it is a state a member
     * leaves, by confirmation, and the other two are arrangements they stay in.
     */
    public enum EmploymentType { PERMANENT, CONTRACTUAL, PROBATION }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "alternate_mobile", length = 20)
    private String alternateMobile;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    /**
     * The last four digits only, never the whole number — the same rule
     * {@code workers.aadhaar_last4} follows. Four digits tell two people of the same name
     * apart on a payroll, and that is the only reason the field exists.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "aadhaar_last4", columnDefinition = "char(4)")
    private String aadhaarLast4;

    @Column(name = "pan", length = 10)
    private String pan;

    @Column(name = "current_address")
    private String currentAddress;

    @Column(name = "permanent_address")
    private String permanentAddress;

    @Column(name = "emergency_contact_name", length = 150)
    private String emergencyContactName;

    @Column(name = "emergency_contact_mobile", length = 20)
    private String emergencyContactMobile;

    @Column(name = "emergency_contact_relation", length = 60)
    private String emergencyContactRelation;

    @Column(name = "bank_account_name", length = 150)
    private String bankAccountName;

    @Column(name = "bank_account_no", length = 30)
    private String bankAccountNo;

    @Column(name = "bank_ifsc", length = 15)
    private String bankIfsc;

    @Column(name = "bank_name", length = 100)
    private String bankName;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type", nullable = false, length = 20)
    private EmploymentType employmentType = EmploymentType.PERMANENT;

    @Column(name = "joined_on")
    private LocalDate joinedOn;

    @Column(name = "probation_days")
    private Integer probationDays;

    @Column(name = "probation_monthly_salary", precision = 14, scale = 2)
    private BigDecimal probationMonthlySalary;

    @Column(name = "confirmed_monthly_salary", precision = 14, scale = 2)
    private BigDecimal confirmedMonthlySalary;

    @Column(name = "confirmed_on")
    private LocalDate confirmedOn;

    @Column(name = "contract_ends_on")
    private LocalDate contractEndsOn;

    @Column(name = "exit_date")
    private LocalDate exitDate;

    @Column(name = "exit_reason", length = 500)
    private String exitReason;

    @Column(name = "notes")
    private String notes;

    protected StaffProfile() {
    }

    public StaffProfile(UUID orgId, UUID userId) {
        this.orgId = orgId;
        this.userId = userId;
    }

    /**
     * When the agreed probation runs out, or null when there is no probation to run out.
     *
     * <p>Derived rather than stored. A stored end date is a second version of the same fact,
     * and the one that stops matching the day somebody corrects a joining date.</p>
     */
    public LocalDate probationEndsOn() {
        if (employmentType != EmploymentType.PROBATION || joinedOn == null
                || probationDays == null) {
            return null;
        }
        return joinedOn.plusDays(probationDays);
    }

    /**
     * Whether the probation has run past its agreed end without anybody confirming them.
     *
     * <p>Worth a tile of its own on the dashboard: a probation nobody closed is somebody
     * working on probation terms months after they were meant to stop, which is a pay
     * dispute waiting to happen.</p>
     */
    public boolean probationOverdueOn(LocalDate today) {
        LocalDate ends = probationEndsOn();
        return ends != null && confirmedOn == null && exitDate == null && ends.isBefore(today);
    }

    /** The figure that was agreed for where they are now, which is not what they are paid. */
    public BigDecimal agreedMonthlySalary() {
        return employmentType == EmploymentType.PROBATION
                ? probationMonthlySalary
                : confirmedMonthlySalary;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getAlternateMobile() {
        return alternateMobile;
    }

    public void setAlternateMobile(String alternateMobile) {
        this.alternateMobile = alternateMobile;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public String getAadhaarLast4() {
        return aadhaarLast4;
    }

    public void setAadhaarLast4(String aadhaarLast4) {
        this.aadhaarLast4 = aadhaarLast4;
    }

    public String getPan() {
        return pan;
    }

    public void setPan(String pan) {
        this.pan = pan;
    }

    public String getCurrentAddress() {
        return currentAddress;
    }

    public void setCurrentAddress(String currentAddress) {
        this.currentAddress = currentAddress;
    }

    public String getPermanentAddress() {
        return permanentAddress;
    }

    public void setPermanentAddress(String permanentAddress) {
        this.permanentAddress = permanentAddress;
    }

    public String getEmergencyContactName() {
        return emergencyContactName;
    }

    public void setEmergencyContactName(String emergencyContactName) {
        this.emergencyContactName = emergencyContactName;
    }

    public String getEmergencyContactMobile() {
        return emergencyContactMobile;
    }

    public void setEmergencyContactMobile(String emergencyContactMobile) {
        this.emergencyContactMobile = emergencyContactMobile;
    }

    public String getEmergencyContactRelation() {
        return emergencyContactRelation;
    }

    public void setEmergencyContactRelation(String emergencyContactRelation) {
        this.emergencyContactRelation = emergencyContactRelation;
    }

    public String getBankAccountName() {
        return bankAccountName;
    }

    public void setBankAccountName(String bankAccountName) {
        this.bankAccountName = bankAccountName;
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

    public EmploymentType getEmploymentType() {
        return employmentType;
    }

    public void setEmploymentType(EmploymentType employmentType) {
        this.employmentType = employmentType;
    }

    public LocalDate getJoinedOn() {
        return joinedOn;
    }

    public void setJoinedOn(LocalDate joinedOn) {
        this.joinedOn = joinedOn;
    }

    public Integer getProbationDays() {
        return probationDays;
    }

    public void setProbationDays(Integer probationDays) {
        this.probationDays = probationDays;
    }

    public BigDecimal getProbationMonthlySalary() {
        return probationMonthlySalary;
    }

    public void setProbationMonthlySalary(BigDecimal probationMonthlySalary) {
        this.probationMonthlySalary = probationMonthlySalary;
    }

    public BigDecimal getConfirmedMonthlySalary() {
        return confirmedMonthlySalary;
    }

    public void setConfirmedMonthlySalary(BigDecimal confirmedMonthlySalary) {
        this.confirmedMonthlySalary = confirmedMonthlySalary;
    }

    public LocalDate getConfirmedOn() {
        return confirmedOn;
    }

    public void setConfirmedOn(LocalDate confirmedOn) {
        this.confirmedOn = confirmedOn;
    }

    public LocalDate getContractEndsOn() {
        return contractEndsOn;
    }

    public void setContractEndsOn(LocalDate contractEndsOn) {
        this.contractEndsOn = contractEndsOn;
    }

    public LocalDate getExitDate() {
        return exitDate;
    }

    public void setExitDate(LocalDate exitDate) {
        this.exitDate = exitDate;
    }

    public String getExitReason() {
        return exitReason;
    }

    public void setExitReason(String exitReason) {
        this.exitReason = exitReason;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}

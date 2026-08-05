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

/**
 * One physical work location inside a project. Every transaction in the system carries a
 * site id; {@code standardShiftHours} is per-site because the field data proved a 7-hour
 * shift exists (docs/09), and hard-coding 8 would corrupt every wage calculation there.
 */
@Entity
@Table(name = "sites")
public class Site extends BaseEntity {

    public enum Status { PLANNED, ACTIVE, SUSPENDED, CLOSED }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "code", nullable = false, length = 40, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "address")
    private String address;

    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "site_engineer_id")
    private UUID siteEngineerId;

    @Column(name = "supervisor_id")
    private UUID supervisorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "standard_shift_hours", nullable = false, precision = 4, scale = 2)
    private BigDecimal standardShiftHours = new BigDecimal("8.00");

    @Column(name = "monthly_wage_days", nullable = false)
    private int monthlyWageDays = 26;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Site() {
    }

    public Site(UUID orgId, UUID projectId, String code, String name) {
        this.orgId = orgId;
        this.projectId = projectId;
        this.code = code;
        this.name = name;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getProjectId() {
        return projectId;
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

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public void setLatitude(BigDecimal latitude) {
        this.latitude = latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public void setLongitude(BigDecimal longitude) {
        this.longitude = longitude;
    }

    public UUID getSiteEngineerId() {
        return siteEngineerId;
    }

    public void setSiteEngineerId(UUID siteEngineerId) {
        this.siteEngineerId = siteEngineerId;
    }

    public UUID getSupervisorId() {
        return supervisorId;
    }

    public void setSupervisorId(UUID supervisorId) {
        this.supervisorId = supervisorId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public BigDecimal getStandardShiftHours() {
        return standardShiftHours;
    }

    public void setStandardShiftHours(BigDecimal standardShiftHours) {
        this.standardShiftHours = standardShiftHours;
    }

    public int getMonthlyWageDays() {
        return monthlyWageDays;
    }

    public void setMonthlyWageDays(int monthlyWageDays) {
        this.monthlyWageDays = monthlyWageDays;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}

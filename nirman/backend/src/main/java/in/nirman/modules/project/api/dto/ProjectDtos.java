package in.nirman.modules.project.api.dto;

import in.nirman.modules.project.domain.Project;
import in.nirman.modules.project.domain.Site;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Request and response shapes for projects, sites and stores. */
public final class ProjectDtos {

    private ProjectDtos() {
    }

    // ------------------------------------------------------------------ projects

    public record ProjectResponse(
            UUID id,
            String code,
            String name,
            String clientDepartment,
            String agreementNo,
            String nitNumber,
            String tenderReference,
            BigDecimal contractValue,
            BigDecimal budgetAmount,
            LocalDate startDate,
            LocalDate expectedCompletionDate,
            LocalDate actualCompletionDate,
            UUID projectManagerId,
            Project.Status status,
            String description,
            Long version) {
    }

    public record CreateProjectRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9._-]+") String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 200) String clientDepartment,
            @Size(max = 80) String agreementNo,
            @Size(max = 80) String nitNumber,
            @Size(max = 120) String tenderReference,
            @PositiveOrZero @Digits(integer = 16, fraction = 2) BigDecimal contractValue,
            @PositiveOrZero @Digits(integer = 16, fraction = 2) BigDecimal budgetAmount,
            LocalDate startDate,
            LocalDate expectedCompletionDate,
            UUID projectManagerId,
            String description) {
    }

    public record UpdateProjectRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 200) String clientDepartment,
            @Size(max = 80) String agreementNo,
            @Size(max = 80) String nitNumber,
            @Size(max = 120) String tenderReference,
            @PositiveOrZero @Digits(integer = 16, fraction = 2) BigDecimal contractValue,
            @PositiveOrZero @Digits(integer = 16, fraction = 2) BigDecimal budgetAmount,
            LocalDate startDate,
            LocalDate expectedCompletionDate,
            LocalDate actualCompletionDate,
            UUID projectManagerId,
            Project.Status status,
            String description,
            @NotNull Long version) {
    }

    public record ProjectSummaryResponse(
            UUID id,
            String code,
            String name,
            Project.Status status,
            BigDecimal contractValue,
            BigDecimal budgetAmount,
            LocalDate startDate,
            LocalDate expectedCompletionDate,
            long siteCount,
            long activeStoreCount) {
    }

    // ------------------------------------------------------------------ sites

    public record SiteResponse(
            UUID id,
            UUID projectId,
            String code,
            String name,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            UUID siteEngineerId,
            UUID supervisorId,
            Site.Status status,
            LocalDate startDate,
            BigDecimal standardShiftHours,
            int monthlyWageDays,
            Long version) {
    }

    /**
     * A site as a destination rather than as a place you work: the least that lets someone
     * name it in a transfer. No address, no staffing, no shift — nothing about how the site
     * is run, which stays behind the assignment fence.
     */
    public record SiteDirectoryEntry(
            UUID id,
            UUID projectId,
            String code,
            String name,
            Site.Status status) {
    }

    public record CreateSiteRequest(
            @NotNull UUID projectId,
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9._-]+") String code,
            @NotBlank @Size(max = 200) String name,
            String address,
            @Digits(integer = 3, fraction = 6) BigDecimal latitude,
            @Digits(integer = 3, fraction = 6) BigDecimal longitude,
            UUID siteEngineerId,
            UUID supervisorId,
            LocalDate startDate,
            @NotNull @Digits(integer = 2, fraction = 2)
            @jakarta.validation.constraints.DecimalMin(value = "0.5")
            @jakarta.validation.constraints.DecimalMax(value = "24.0") BigDecimal standardShiftHours,
            @Min(1) @Max(31) int monthlyWageDays) {
    }

    public record UpdateSiteRequest(
            @NotBlank @Size(max = 200) String name,
            String address,
            @Digits(integer = 3, fraction = 6) BigDecimal latitude,
            @Digits(integer = 3, fraction = 6) BigDecimal longitude,
            UUID siteEngineerId,
            UUID supervisorId,
            Site.Status status,
            LocalDate startDate,
            @NotNull @Digits(integer = 2, fraction = 2)
            @jakarta.validation.constraints.DecimalMin(value = "0.5")
            @jakarta.validation.constraints.DecimalMax(value = "24.0") BigDecimal standardShiftHours,
            @Min(1) @Max(31) int monthlyWageDays,
            @NotNull Long version) {
    }

    // ------------------------------------------------------------------ stores

    public record StoreResponse(
            UUID id,
            UUID siteId,
            String code,
            String name,
            String location,
            boolean defaultStore,
            boolean active,
            Long version) {
    }

    public record CreateStoreRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9._-]+") String code,
            @NotBlank @Size(max = 150) String name,
            @Size(max = 200) String location,
            boolean defaultStore) {
    }
}

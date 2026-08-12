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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
            Instant deletedAt,
            UUID deletedBy,
            String deletedReason,
            Long version) {
    }

    /**
     * Why a project or a site is being taken off the books.
     *
     * <p>The reason is required for the same reason it is required to void an approved
     * expense: six months on, a row that vanished without an explanation is
     * indistinguishable from data loss, and the person asking what happened is never the
     * person who did it.</p>
     */
    public record DeleteRequest(
            @NotBlank @Size(max = 500) String reason) {
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
            /** Everyone named on the site as engineer, and everyone named as supervisor. */
            List<UUID> siteEngineerIds,
            List<UUID> supervisorIds,
            Site.Status status,
            LocalDate startDate,
            BigDecimal standardShiftHours,
            int monthlyWageDays,
            /** Work here is let to labour contractors, so the day is head counts per trade. */
            boolean usesOutsourcedLabour,
            Instant deletedAt,
            UUID deletedBy,
            String deletedReason,
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
            /**
             * The whole list each time, not a delta: the screen's view of who runs the site
             * wins outright, and a null is read as "nobody", which is what an empty register
             * means. Naming somebody here is what grants them the site.
             */
            List<UUID> siteEngineerIds,
            List<UUID> supervisorIds,
            LocalDate startDate,
            @NotNull @Digits(integer = 2, fraction = 2)
            @jakarta.validation.constraints.DecimalMin(value = "0.5")
            @jakarta.validation.constraints.DecimalMax(value = "24.0") BigDecimal standardShiftHours,
            @Min(1) @Max(31) int monthlyWageDays,
            boolean usesOutsourcedLabour) {
    }

    public record UpdateSiteRequest(
            @NotBlank @Size(max = 200) String name,
            String address,
            @Digits(integer = 3, fraction = 6) BigDecimal latitude,
            @Digits(integer = 3, fraction = 6) BigDecimal longitude,
            /** @see CreateSiteRequest#siteEngineerIds — sent whole, and a null clears it. */
            List<UUID> siteEngineerIds,
            List<UUID> supervisorIds,
            Site.Status status,
            LocalDate startDate,
            @NotNull @Digits(integer = 2, fraction = 2)
            @jakarta.validation.constraints.DecimalMin(value = "0.5")
            @jakarta.validation.constraints.DecimalMax(value = "24.0") BigDecimal standardShiftHours,
            @Min(1) @Max(31) int monthlyWageDays,
            boolean usesOutsourcedLabour,
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
            @NotBlank @Size(max = 60) @Pattern(regexp = "[A-Za-z0-9._-]+") String code,
            @NotBlank @Size(max = 210) String name,
            @Size(max = 200) String location,
            boolean defaultStore) {
    }

    /**
     * A store on the Stores screen, with the site it belongs to spelled out.
     *
     * <p>The site's code and name ride along because the screen lists every store the caller
     * can reach, across sites — resolving each one against the sites register client-side
     * would leave a store whose site the caller can open but has not loaded showing a dash.</p>
     */
    public record StoreDirectoryEntry(
            UUID id,
            UUID siteId,
            String siteCode,
            String siteName,
            String code,
            String name,
            String location,
            boolean defaultStore,
            boolean active,
            Long version) {
    }

    /**
     * The code is editable, unlike a site's. A default store was named by the machine after
     * its site, and an organisation that keeps a cement lockup and a steel yard has to be
     * able to say so.
     */
    public record UpdateStoreRequest(
            @NotBlank @Size(max = 60) @Pattern(regexp = "[A-Za-z0-9._-]+") String code,
            @NotBlank @Size(max = 210) String name,
            @Size(max = 200) String location,
            boolean defaultStore,
            boolean active,
            @NotNull Long version) {
    }
}

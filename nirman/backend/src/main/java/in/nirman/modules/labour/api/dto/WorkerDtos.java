package in.nirman.modules.labour.api.dto;

import in.nirman.modules.labour.domain.WageType;
import in.nirman.modules.labour.domain.Worker;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Request and response shapes for the worker master and its wage and posting history. */
public final class WorkerDtos {

    private WorkerDtos() {
    }

    public record WorkerResponse(
            UUID id,
            String workerCode,
            String fullName,
            String mobile,
            UUID photoAttachmentId,
            UUID skillCategoryId,
            Worker.EmploymentType employmentType,
            UUID labourContractorId,
            WageType wageType,
            LocalDate joiningDate,
            LocalDate exitDate,
            String aadhaarLast4,
            String bankAccountNo,
            String bankIfsc,
            String bankName,
            boolean active,
            /** The rate in force today, or null if none has been set yet. */
            WageRateResponse currentWageRate,
            /** Where he is posted today, or null if unallocated. */
            UUID currentSiteId,
            Long version) {
    }

    public record CreateWorkerRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9._-]+") String workerCode,
            @NotBlank @Size(max = 150) String fullName,
            @Size(max = 20) String mobile,
            UUID skillCategoryId,
            @NotNull Worker.EmploymentType employmentType,
            UUID labourContractorId,
            @NotNull WageType wageType,
            LocalDate joiningDate,
            @Pattern(regexp = "\\d{4}", message = "exactly four digits, never the full number")
            String aadhaarLast4,
            @Size(max = 30) String bankAccountNo,
            @Size(max = 15) String bankIfsc,
            @Size(max = 120) String bankName,
            /** Optional: posts him to a site from this date in the same call. */
            UUID siteId,
            /** Optional: opens his first wage rate in the same call. */
            @DecimalMin("0") @Digits(integer = 14, fraction = 4) BigDecimal normalRate,
            @DecimalMin("0") @Digits(integer = 14, fraction = 4) BigDecimal overtimeRate) {
    }

    public record UpdateWorkerRequest(
            @NotBlank @Size(max = 150) String fullName,
            @Size(max = 20) String mobile,
            UUID skillCategoryId,
            @NotNull Worker.EmploymentType employmentType,
            UUID labourContractorId,
            @NotNull WageType wageType,
            LocalDate joiningDate,
            LocalDate exitDate,
            @Pattern(regexp = "\\d{4}") String aadhaarLast4,
            @Size(max = 30) String bankAccountNo,
            @Size(max = 15) String bankIfsc,
            @Size(max = 120) String bankName,
            boolean active,
            @NotNull Long version) {
    }

    // ------------------------------------------------------------------ wage rates

    public record WageRateResponse(
            UUID id,
            UUID workerId,
            BigDecimal normalRate,
            BigDecimal overtimeRate,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String remarks) {
    }

    /**
     * A revision, not an edit: posting this closes the open rate the day before
     * {@code effectiveFrom} and opens a new one. Past attendance keeps the rate it was
     * verified against.
     */
    public record ReviseWageRequest(
            @NotNull @DecimalMin("0") @Digits(integer = 14, fraction = 4) BigDecimal normalRate,
            @NotNull @DecimalMin("0") @Digits(integer = 14, fraction = 4) BigDecimal overtimeRate,
            @NotNull LocalDate effectiveFrom,
            @Size(max = 500) String remarks) {
    }

    // ------------------------------------------------------------------ allocations

    public record AllocationResponse(
            UUID id,
            UUID workerId,
            UUID siteId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
    }

    /** Moving a worker closes his open posting the day before and opens one at the new site. */
    public record AllocateRequest(
            @NotNull UUID siteId,
            @NotNull LocalDate effectiveFrom) {
    }
}

package in.nirman.modules.identity.api.dto;

import in.nirman.modules.identity.domain.StaffDocument;
import in.nirman.modules.identity.domain.StaffProfile;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for staff records and their pay. */
public final class StaffDtos {

    private StaffDtos() {
    }

    /**
     * A member's whole record, with the login's own fields alongside so the screen does not
     * have to hold two responses together to draw one person.
     *
     * @param probationEndsOn  derived from the joining date and the agreed length, never
     *                         stored — see {@link StaffProfile#probationEndsOn()}
     * @param probationOverdue the agreed probation has run out and nobody has confirmed
     *                         them, which is a pay dispute forming
     * @param currentSalary    what the salary history says applies today, which is not
     *                         necessarily what was agreed
     */
    public record StaffProfileResponse(
            UUID userId,
            String username,
            String fullName,
            String mobile,
            String email,
            boolean active,
            List<String> roles,

            String alternateMobile,
            LocalDate dateOfBirth,
            String aadhaarLast4,
            String pan,
            String currentAddress,
            String permanentAddress,
            String emergencyContactName,
            String emergencyContactMobile,
            String emergencyContactRelation,
            String bankAccountName,
            String bankAccountNo,
            String bankIfsc,
            String bankName,

            StaffProfile.EmploymentType employmentType,
            LocalDate joinedOn,
            Integer probationDays,
            BigDecimal probationMonthlySalary,
            BigDecimal confirmedMonthlySalary,
            LocalDate confirmedOn,
            LocalDate contractEndsOn,
            LocalDate probationEndsOn,
            boolean probationOverdue,
            BigDecimal currentSalary,

            LocalDate exitDate,
            String exitReason,
            String notes,
            /** Null until somebody has filled a record in; the rest reads as blank until then. */
            Long version) {
    }

    /**
     * Saves the record whole. One PUT rather than a field-by-field patch, because the screen
     * is one form and a half-saved person is worse than an unsaved one.
     *
     * <p>{@code version} is null the first time: until an administrator opens the screen
     * there is no row, and demanding a version for a record that does not exist would make
     * filling the first one in impossible.</p>
     */
    public record SaveStaffProfileRequest(
            @Size(max = 20) @Pattern(regexp = "[0-9+\\-\\s]*",
                    message = "Digits, spaces, + and - only") String alternateMobile,
            LocalDate dateOfBirth,
            /**
             * Four digits, never the whole number. The screen asks for four and the server
             * takes four; a full Aadhaar sent here is refused rather than truncated, because
             * silently storing part of what somebody typed is worse than saying no.
             */
            @Pattern(regexp = "[0-9]{4}|", message = "The last four digits only")
            String aadhaarLast4,
            @Pattern(regexp = "[A-Z]{5}[0-9]{4}[A-Z]|",
                    message = "A PAN is five letters, four digits and a letter") String pan,
            String currentAddress,
            String permanentAddress,
            @Size(max = 150) String emergencyContactName,
            @Size(max = 20) String emergencyContactMobile,
            @Size(max = 60) String emergencyContactRelation,
            @Size(max = 150) String bankAccountName,
            @Size(max = 30) String bankAccountNo,
            @Pattern(regexp = "[A-Z]{4}0[A-Z0-9]{6}|",
                    message = "An IFSC is four letters, a zero and six characters") String bankIfsc,
            @Size(max = 100) String bankName,

            @NotNull StaffProfile.EmploymentType employmentType,
            LocalDate joinedOn,
            @Min(1) @Max(730) Integer probationDays,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal probationMonthlySalary,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal confirmedMonthlySalary,
            LocalDate confirmedOn,
            LocalDate contractEndsOn,
            LocalDate exitDate,
            @Size(max = 500) String exitReason,
            String notes,
            Long version) {
    }

    /**
     * A change of pay, from a date.
     *
     * <p>The reason is required. Six months on, a figure that moved for no recorded reason is
     * a figure somebody has to go and ask about, and the person asking is never the person
     * who changed it.</p>
     */
    public record RecordSalaryRequest(
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal monthlyAmount,
            @NotNull LocalDate effectiveFrom,
            @NotBlank @Size(max = 300) String reason) {
    }

    public record SalaryRevisionResponse(
            UUID id,
            BigDecimal monthlyAmount,
            LocalDate effectiveFrom,
            String reason) {
    }

    /**
     * The staff at a glance: who is on what footing, what the payroll comes to, and what is
     * about to need attention.
     *
     * @param monthlyPayroll the sum of what each active member is paid today. Derived from
     *                       the salary history on every call, never stored — a cached
     *                       payroll total is a second version of the truth, and the one
     *                       nobody notices has gone stale.
     * @param missingBankDetails members who cannot actually be paid, which is the figure the
     *                       payroll total quietly depends on
     */
    public record StaffDashboardResponse(
            int totalActive,
            int permanent,
            int contractual,
            int onProbation,
            int probationOverdue,
            int probationEndingSoon,
            int contractsEndingSoon,
            int missingBankDetails,
            int noRecordYet,
            BigDecimal monthlyPayroll,
            List<StaffAlert> attentionNeeded) {
    }

    /** One person the dashboard is pointing at, and what about them. */
    public record StaffAlert(
            UUID userId,
            String fullName,
            String reason,
            LocalDate on) {
    }

    // ------------------------------------------------------------------ the papers

    /**
     * One paper on a member's record.
     *
     * @param image whether the browser can draw it. A scan is a JPEG and a signed
     *              appointment letter is usually a PDF, and the screen shows the first as a
     *              thumbnail and offers the second as something to open — a file name is not
     *              evidence, and a broken image icon is worse than a button.
     * @param fileName what it was called on the device it came off. Kept because it is
     *              occasionally the only thing that distinguishes two scans of one kind,
     *              and never shown in place of what the document is.
     */
    public record StaffDocumentResponse(
            UUID id,
            UUID userId,
            UUID attachmentId,
            StaffDocument.Type docType,
            String note,
            String fileName,
            String contentType,
            boolean image,
            Instant uploadedAt,
            UUID uploadedBy) {
    }

    /**
     * Putting a paper on the record.
     *
     * <p>The file is uploaded first and named here: it is an attachment id, what the paper
     * is, and a note if the type does not say enough. Nothing about the member is written —
     * a scan of an Aadhaar card does not fill in the last four digits, because reading a
     * number off a photograph is the office's act and not the upload's.</p>
     */
    public record AddStaffDocumentRequest(
            @NotNull UUID attachmentId,
            @NotNull StaffDocument.Type docType,
            @Size(max = 200) String note) {
    }
}

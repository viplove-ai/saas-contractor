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

            /** The short number the office writes on a payslip and a bank advice. */
            String employeeNumber,
            String designation,
            String uan,
            String esicNumber,
            boolean pfApplicable,
            boolean esiApplicable,
            boolean pfOnFullWages,
            Integer noticePeriodDays,

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

            @Size(max = 20) String employeeNumber,
            @Size(max = 100) String designation,
            @Pattern(regexp = "[0-9]{12}|", message = "A UAN is twelve digits") String uan,
            @Size(max = 17) String esicNumber,
            /**
             * Whether the two statutes reach this member. Stored decisions rather than tests
             * run every month — insurance coverage runs for a whole contribution period and
             * does not lapse mid-period because a raise crossed the ceiling.
             */
            boolean pfApplicable,
            boolean esiApplicable,
            /** Contribute to the fund on the whole wage rather than on the statutory ceiling. */
            boolean pfOnFullWages,
            @Min(0) @Max(365) Integer noticePeriodDays,

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
            /**
             * The five components, and no gross.
             *
             * <p>The gross is derived from them rather than sent alongside, because a total
             * typed beside a breakdown is a total that can disagree with it — and the check
             * constraint under the row would then refuse the save with a complaint about
             * arithmetic that the person typing cannot act on.</p>
             *
             * <p>The basic is required and the rest are not. A salary of basic alone is a
             * real and common arrangement; a salary with no basic at all is not an
             * arrangement, it is a figure somebody has declined to break down, and it cannot
             * produce a payslip because the provident fund has nothing to stand on.</p>
             */
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal basic,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal dearnessAllowance,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal hra,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal conveyance,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal otherAllowance,
            /**
             * What the State charges on a salary of this size. Typed rather than looked up:
             * the slabs are twenty different state schedules amended by notification, and one
             * hardcoded wrong would come out of somebody's pay every month with perfect
             * confidence.
             */
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal professionalTax,
            @NotNull LocalDate effectiveFrom,
            @NotBlank @Size(max = 300) String reason) {
    }

    /**
     * One revision, with its parts.
     *
     * @param structured    false for a row written before the structure existed — a true
     *                      record of what somebody was paid, and one no payslip can be drawn
     *                      from. The screen says which of the two it is rather than showing
     *                      zeroes
     * @param statutoryWages what the Code on Wages makes of this structure: basic and
     *                      dearness allowance, raised to half the packet where the allowances
     *                      exceed that. Shown because it is what tells an office writing a
     *                      structure whether the split it chose is doing what it thinks
     */
    public record SalaryRevisionResponse(
            UUID id,
            BigDecimal monthlyAmount,
            boolean structured,
            BigDecimal basic,
            BigDecimal dearnessAllowance,
            BigDecimal hra,
            BigDecimal conveyance,
            BigDecimal otherAllowance,
            BigDecimal professionalTax,
            BigDecimal statutoryWages,
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

    // ------------------------------------------------------------------ the offer

    /**
     * What the offer letter needs that the record does not already hold.
     *
     * <p>Deliberately short. The designation, the joining date, the probation, the notice
     * period and the whole salary structure are read off {@code staff_profiles} and the
     * salary revisions — asking for them again here would be a second place to state the same
     * terms, and the letter and the payroll would then disagree about the man they both
     * describe within a year. What is left is what belongs to the letter alone: where he is
     * posted, whom he reports to, by when he must answer, and who is signing.</p>
     *
     * @param joiningOn  overrides the record's joining date, for the ordinary case of an
     *                   offer written before the date is entered anywhere
     * @param reference  the office's own filing reference. Absent, one is built from the
     *                   organisation code, the year and the employee number — offered rather
     *                   than imposed, because a firm that already numbers its correspondence
     *                   has a scheme this one would only fight with
     */
    public record OfferLetterRequest(
            LocalDate joiningOn,
            LocalDate letterDate,
            @Size(max = 60) String reference,
            @Size(max = 200) String placeOfPosting,
            @Size(max = 150) String reportingTo,
            LocalDate respondBy,
            @Size(max = 150) String signatoryName,
            @Size(max = 150) String signatoryDesignation) {
    }
}

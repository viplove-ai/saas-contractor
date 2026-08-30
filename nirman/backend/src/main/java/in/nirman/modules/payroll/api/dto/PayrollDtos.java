package in.nirman.modules.payroll.api.dto;

import in.nirman.modules.payroll.domain.PayrollRun;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for the month's payroll and the payslips it produces. */
public final class PayrollDtos {

    private PayrollDtos() {
    }

    /**
     * Opening a month.
     *
     * @param periodMonth any day in the month; the server keeps the first of it. Asking for
     *                    "the month" and storing a day is how two runs come to disagree about
     *                    whether July ended on the 30th
     * @param payableDays the denominator every slip in the run prorates against. Null takes
     *                    the calendar's own count for that month, which is the answer an
     *                    office that has not thought about it means
     */
    public record OpenRunRequest(
            @NotNull LocalDate periodMonth,
            @Min(1) @Max(31) Integer payableDays,
            @Size(max = 500) String notes) {
    }

    /** Correcting the month itself. Changing the payable days redraws every slip under it. */
    public record UpdateRunRequest(
            @NotNull @Min(1) @Max(31) Integer payableDays,
            @Size(max = 500) String notes,
            @NotNull Long version) {
    }

    /**
     * The figures on one slip that nobody can compute — the days he was there, the hours he
     * worked beyond the shift, and the three deductions that come from outside this system.
     *
     * @param overtimeAmount normally left out, and then the hours are priced at twice the
     *                       ordinary rate as the Code on Wages requires. Sent only by an
     *                       office that has agreed a different overtime rate; the hours still
     *                       print beside whatever it sends
     * @param tds            typed, because the deduction depends on the member's election
     *                       between the two tax regimes, on declarations he makes and proofs
     *                       he produces, and this system holds none of the three
     */
    public record UpdatePayslipRequest(
            @NotNull @DecimalMin("0") @Digits(integer = 3, fraction = 2) BigDecimal paidDays,
            @DecimalMin("0") @Digits(integer = 5, fraction = 2) BigDecimal overtimeHours,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal overtimeAmount,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal professionalTax,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal tds,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal salaryAdvance,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal otherDeduction,
            @Size(max = 200) String otherDeductionNote,
            @Size(max = 300) String remarks,
            @NotNull Long version) {
    }

    /**
     * A month, its slips and what they come to.
     *
     * @param totals      summed from the slips on every read. The slips themselves are frozen
     *                    — that is what makes summing them safe — and a stored run total would
     *                    be a second version of a figure the rows already answer
     * @param notDrawn    everybody the run could not draw a slip for, and why. The register
     *                    would otherwise report a payroll of eleven people in an office of
     *                    fourteen and look complete doing it
     */
    public record PayrollRunResponse(
            UUID id,
            LocalDate periodMonth,
            LocalDate periodEnd,
            PayrollRun.Status status,
            int payableDays,
            String notes,
            Instant finalisedAt,
            UUID finalisedBy,
            PayrollTotals totals,
            List<PayslipResponse> payslips,
            List<NotDrawn> notDrawn,
            Long version) {
    }

    /** The month at a glance, and what the bank transfer has to come to. */
    public record PayrollTotals(
            int headcount,
            BigDecimal grossEarnings,
            BigDecimal totalDeductions,
            BigDecimal netPayable,
            BigDecimal pfEmployee,
            BigDecimal pfEmployer,
            BigDecimal epsEmployer,
            BigDecimal esiEmployee,
            BigDecimal esiEmployer,
            BigDecimal professionalTax,
            BigDecimal tds,
            /** What the month actually cost: paid to them plus remitted for them. */
            BigDecimal employerCost) {
    }

    /**
     * Somebody on the books with no slip in this run.
     *
     * <p>Named rather than counted, because "three members were skipped" is a sentence
     * somebody reads past and "Ramesh Yadav — no salary structure recorded" is one he acts
     * on.</p>
     */
    public record NotDrawn(UUID userId, String fullName, String reason) {
    }

    /** A month in the list: enough to choose one, without its slips. */
    public record PayrollRunSummary(
            UUID id,
            LocalDate periodMonth,
            PayrollRun.Status status,
            int payableDays,
            int headcount,
            BigDecimal netPayable,
            Instant finalisedAt) {
    }

    /**
     * One frozen payslip, whole.
     *
     * @param statutoryWages the wage the Code on Wages makes of this month's packet — basic
     *                       and dearness allowance, lifted to half the packet where the
     *                       allowances were let run past that. Shown because it is the basis
     *                       the fund was charged on, and an office looking at a provident
     *                       fund figure it did not expect is looking for this number
     * @param employerCost   what employing him cost: outside the deductions and outside the
     *                       net, because it was never his money
     */
    public record PayslipResponse(
            UUID id,
            UUID runId,
            UUID userId,
            LocalDate periodMonth,
            String employeeName,
            String employeeNumber,
            String designation,
            String uan,
            String esicNumber,

            BigDecimal structBasic,
            BigDecimal structDa,
            BigDecimal structHra,
            BigDecimal structConveyance,
            BigDecimal structOther,
            BigDecimal structGross,

            int payableDays,
            BigDecimal paidDays,
            BigDecimal earnedBasic,
            BigDecimal earnedDa,
            BigDecimal earnedHra,
            BigDecimal earnedConveyance,
            BigDecimal earnedOther,
            BigDecimal overtimeHours,
            BigDecimal overtimeAmount,
            BigDecimal totalEarnings,

            BigDecimal statutoryWages,
            BigDecimal pfWages,
            BigDecimal pfEmployee,
            BigDecimal esiWages,
            BigDecimal esiEmployee,
            BigDecimal professionalTax,
            BigDecimal tds,
            BigDecimal salaryAdvance,
            BigDecimal otherDeduction,
            String otherDeductionNote,
            BigDecimal totalDeductions,
            BigDecimal netAmount,

            BigDecimal pfEmployer,
            BigDecimal epsEmployer,
            BigDecimal esiEmployer,
            BigDecimal employerCost,

            String remarks,
            boolean finalised,
            Long version) {
    }
}

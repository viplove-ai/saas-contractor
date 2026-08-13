package in.nirman.modules.planning.api.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request and response shapes for generating, storing and reading an execution plan. */
public final class PlanDtos {

    private PlanDtos() {
    }

    /**
     * What the user chose, which always beats what was extracted.
     *
     * <p>Every field is optional. The screen shows what the tender said and the plan's own
     * defaults; what comes back is only what somebody deliberately changed, so a blank is "use
     * what you read" rather than "use zero".</p>
     *
     * @param quotedPercent the bid, above (positive) or below (negative) the estimate. Not
     *                      extractable — at the time the notice is read it has not been decided
     *                      — and the single number a bid case exists to move.
     */
    public record GeneratePlanRequest(
            UUID workTypeProfileId,
            LocalDate commencementDate,
            @Min(1) @Max(3650) Integer allowedDays,
            @Digits(integer = 3, fraction = 3) BigDecimal quotedPercent,
            @Min(0) @Max(365) Integer paymentLagDays,
            @Digits(integer = 8, fraction = 2) BigDecimal defaultDailyWage,
            Map<String, BigDecimal> dailyWageByTrade,
            @Min(1) @Max(31) Integer workingDaysPerMonth,
            @Digits(integer = 12, fraction = 2) BigDecimal monthlyStaffCost,
            @Digits(integer = 12, fraction = 2) BigDecimal siteSetupCost,
            @Digits(integer = 12, fraction = 2) BigDecimal monthlyPlantAndTransport,
            @Size(max = 60) String scenario,
            @Size(max = 200) String name) {
    }

    public record PhaseView(
            int sequence,
            String description,
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal targetPercent,
            BigDecimal plannedValue,
            BigDecimal plannedPercent,
            BigDecimal withheldPercent,
            boolean physical,
            boolean onTarget) {
    }

    public record PackageView(
            String category,
            String workPart,
            BigDecimal value,
            LocalDate startDate,
            LocalDate endDate,
            int gangs,
            int lineCount,
            boolean normed) {
    }

    public record LabourView(
            String month,
            String skillCode,
            boolean skilled,
            BigDecimal manDays,
            BigDecimal headCount,
            BigDecimal cost) {
    }

    /**
     * @param requiredQty what the month consumes
     * @param procureQty  what has to be ordered in this month for a later one to happen. A
     *                    different question, and the answer to "what do we need in advance".
     */
    public record MaterialView(
            String month,
            String materialCode,
            String materialName,
            String unitCode,
            BigDecimal requiredQty,
            BigDecimal procureQty,
            BigDecimal procureValue,
            LocalDate orderByDate) {
    }

    public record CashView(
            String month,
            BigDecimal labourCost,
            BigDecimal materialCost,
            BigDecimal staffCost,
            BigDecimal plantTransport,
            BigDecimal setupCost,
            BigDecimal overheadCost,
            BigDecimal totalOutflow,
            BigDecimal grossBilled,
            BigDecimal deductions,
            BigDecimal netReceived,
            BigDecimal netMovement,
            BigDecimal cumulative) {
    }

    /**
     * @param peakFundingRequired the deepest point of the cumulative trough. This is the answer
     *                            to "how much money do we need to start", and it is not the
     *                            first month's cost.
     */
    public record WorkingCapitalView(
            BigDecimal peakFundingRequired,
            String peakMonth,
            BigDecimal moneyBeforeDayOne,
            String breakEvenMonth,
            BigDecimal totalRetentionHeld,
            LocalDate retentionReleasedOn,
            BigDecimal totalOutflow,
            BigDecimal totalNetReceipts) {
    }

    public record NoteView(String kind, String severity, String subject, String value,
                           String message) {
    }

    /** A whole plan. {@code id} is null on a preview, which persists nothing. */
    public record PlanResponse(
            UUID id,
            UUID projectId,
            String name,
            String scenario,
            LocalDate commencementDate,
            int allowedDays,
            BigDecimal quotedPercent,
            BigDecimal contractValue,
            boolean baselined,
            int revision,
            List<PhaseView> phases,
            List<PackageView> packages,
            List<LabourView> labour,
            List<MaterialView> material,
            List<CashView> cash,
            WorkingCapitalView workingCapital,
            List<NoteView> notes) {
    }
}

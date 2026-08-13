package in.nirman.modules.planning.api.dto;

import in.nirman.modules.planning.domain.WorkTypeProfile;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/** Request and response shapes for the norms a construction programme is derived from. */
public final class PlanningDtos {

    private PlanningDtos() {
    }

    public record WorkTypeProfileResponse(
            UUID id,
            String code,
            String name,
            String description,
            WorkTypeProfile.PhaseBasis phaseBasis,
            boolean monsoonSensitive,
            BigDecimal defaultOverheadPercent,
            boolean active,
            long version) {
    }

    /**
     * One trade's share of a unit of work.
     *
     * @param source where the figure came from. A starter value and a figure this organisation
     *               measured on its own sites are different degrees of confidence, and a plan
     *               that could not tell them apart could not say which numbers were guesses.
     */
    public record ProductivityNormResponse(
            UUID id,
            String workCategory,
            String workSubType,
            UUID skillCategoryId,
            String skillCode,
            String skillName,
            boolean skilled,
            UUID workUnitId,
            String workUnitCode,
            BigDecimal manDaysPerWorkUnit,
            String source,
            boolean active,
            String notes,
            long version) {
    }

    /**
     * A correction. Only the figure and its status may be changed: the category, the trade and
     * the unit are what the norm <i>is</i>, and editing them would silently turn one norm into
     * another that some earlier plan had already been built on.
     */
    public record ReviseProductivityNormRequest(
            @NotNull @DecimalMin(value = "0", inclusive = false)
            @Digits(integer = 12, fraction = 6) BigDecimal manDaysPerWorkUnit,
            Boolean active,
            @Size(max = 500) String notes,
            @NotNull Long version) {
    }

    public record SequenceNormResponse(
            UUID id,
            UUID workTypeProfileId,
            String workCategory,
            int sequenceRank,
            BigDecimal maxOverlapPercent,
            int maxConcurrentGangs,
            boolean monsoonSensitive,
            boolean active,
            long version) {
    }

    public record ReviseSequenceNormRequest(
            @Min(1) Integer sequenceRank,
            @DecimalMin("0") @Digits(integer = 3, fraction = 2) BigDecimal maxOverlapPercent,
            @Min(1) @Max(500) Integer maxConcurrentGangs,
            Boolean monsoonSensitive,
            Boolean active,
            @NotNull Long version) {
    }

    /**
     * @param orderAheadDays lead plus buffer — how far before the need date an order must go out.
     *                       Derived rather than stored, because two numbers that must add up are
     *                       better added at the point of use than kept in a third column.
     */
    public record LeadTimeResponse(
            UUID id,
            UUID materialId,
            String materialCode,
            String materialName,
            int leadDays,
            int bufferDays,
            int orderAheadDays,
            Integer shelfLifeDays,
            boolean storable,
            boolean active,
            String notes,
            long version) {
    }

    public record ReviseLeadTimeRequest(
            @Min(0) @Max(365) Integer leadDays,
            @Min(0) @Max(365) Integer bufferDays,
            @Min(1) @Max(3650) Integer shelfLifeDays,
            Boolean storable,
            Boolean active,
            @Size(max = 500) String notes,
            @NotNull Long version) {
    }
}

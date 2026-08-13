package in.nirman.modules.tender.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The tender module's public read API for the contractual terms a plan is built on.
 *
 * <p>Shaped for the planner rather than exposing {@code NitDocument}: durations arrive as days
 * from commencement, the Clause 7 thresholds as a map by work part, and the milestones already
 * ordered. The planner should never have to know that a notice printed "02 Month".</p>
 */
public interface NitLookup {

    /**
     * @param completionDays      the time allowed, normalised
     * @param startReckoningDays  days between the acceptance letter and the reckoned start
     * @param clause7aApplicable  when true, nothing is paid until the labour registrations are
     *                            filed — a gate on the first rupee, not a deduction
     */
    record TenderTerms(
            UUID nitDocumentId,
            BigDecimal estimatedCost,
            BigDecimal emdAmount,
            BigDecimal performanceGuaranteePercent,
            BigDecimal securityDepositPercent,
            Integer completionDays,
            Integer startReckoningDays,
            Boolean clause7aApplicable,
            Map<String, BigDecimal> interimMinimums,
            List<MilestoneTerm> milestones) {
    }

    /** @param dueDays from commencement; null where the notice's wording defeated the reader */
    record MilestoneTerm(
            int sequence,
            String description,
            Integer dueDays,
            BigDecimal financialPercent,
            BigDecimal withheldPercent,
            boolean physical) {
    }

    /** @return empty when the project was not created from a tender this system read */
    Optional<TenderTerms> forProject(UUID projectId);
}

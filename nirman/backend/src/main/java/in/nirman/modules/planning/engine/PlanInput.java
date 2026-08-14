package in.nirman.modules.planning.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Everything the engine needs, and nothing it can look up for itself.
 *
 * <p>The engine is pure: no repository, no clock, no security context. Two adapters build this
 * record — one from a saved project and its BOQ, the other from a transient reading of an
 * uploaded notice — and that is what lets the same arithmetic serve the post-award plan and the
 * pre-award bid case without either becoming a special case of the other.</p>
 *
 * <p>It is also what makes the arithmetic testable. Every figure the plan asserts can be traced
 * to a field here, and a hand-written input is enough to pin any behaviour in the engine down.
 * See {@code docs/10-planning-and-execution-strategy.md} §5.</p>
 */
public record PlanInput(
        WorkTypeProfile profile,
        /** Day one of the programme: the acceptance letter plus the reckoning days. */
        LocalDate commencementDate,
        /** Days allowed from commencement. */
        int allowedDays,
        /**
         * The bid, as a percentage above (positive) or below (negative) the estimated cost.
         * A percentage-rate tender prices the BOQ at DSR rates and pays the contractor those
         * rates adjusted by his own quote, so a plan that ignores this is wrong by exactly the
         * margin that decides whether the job makes money.
         */
        BigDecimal quotedPercent,
        List<WorkItem> workItems,
        List<Milestone> milestones,
        CommercialTerms terms,
        CostBasis costs,
        Norms norms) {

    /** @param crewDensity max gangs on one front, by category; absent means the norm default */
    public record WorkTypeProfile(
            String code,
            String name,
            boolean monsoonSensitive,
            BigDecimal overheadPercent,
            Map<String, Integer> crewDensity) {
    }

    /**
     * One line of the priced schedule, as the planner sees it.
     *
     * @param category  the classifier's own vocabulary, which is what lets a norm find this line
     * @param synthetic a reconciliation placeholder. Carries value — the department still pays
     *                  against that part of the contract — but no work, because nothing can be
     *                  charged against it and scheduling it would invent activity with no
     *                  description.
     */
    public record WorkItem(
            String itemNumber,
            String description,
            String category,
            String workPart,
            BigDecimal quantity,
            String unitCode,
            BigDecimal amount,
            boolean synthetic) {
    }

    /**
     * A stipulated milestone, already normalised to days from commencement.
     *
     * @param financialPercent cumulative share of the contract due by then, where the notice
     *                         stated one; null where the milestone is purely physical
     * @param withheldPercent  held back on a miss and released when a later milestone is met
     */
    public record Milestone(
            int sequence,
            String description,
            int dueDay,
            BigDecimal financialPercent,
            BigDecimal withheldPercent,
            boolean physical) {
    }

    /**
     * What the contract does to the money.
     *
     * @param interimMinimums   gross work needed before a bill may be raised, by work part;
     *                          the key is null for a notice that states a single figure
     * @param clause7aApplicable when true, no bill is paid until the labour licences and the
     *                          EPFO, ESIC and BOCW registrations are filed. A gate on being
     *                          paid at all rather than a deduction.
     * @param paymentLagDays    measurement, recording, checking, passing, payment. The number
     *                          the plan is most sensitive to and the one a contractor knows
     *                          best from his own division.
     */
    public record CommercialTerms(
            BigDecimal contractValue,
            BigDecimal performanceGuaranteePercent,
            BigDecimal securityDepositPercent,
            Map<String, BigDecimal> interimMinimums,
            Boolean clause7aApplicable,
            /**
             * How often a bill is raised, in days. A contractor bills on a rhythm — most bill
             * monthly — and the Clause 7 minimum is the floor beneath it, not the trigger: a
             * cycle that comes round on work worth less than the threshold waits.
             */
            int billingCycleDays,
            int paymentLagDays,
            BigDecimal incomeTaxTdsPercent,
            BigDecimal gstTdsPercent,
            BigDecimal labourCessPercent,
            BigDecimal waterElectricityPercent,
            int defectLiabilityMonths,
            BigDecimal emdAmount,
            /**
             * The estimated cost put to tender. Distinct from {@code contractValue}, and the
             * distinction is the point: the performance guarantee is a share of the estimate
             * <b>or</b> the contract, whichever is higher, so bidding low does not shrink it.
             */
            BigDecimal estimatedCostPutToTender,
            /** Null where the notice states no additional-guarantee clause. */
            AdditionalGuarantee additionalGuarantee) {
    }

    /**
     * The second guarantee a low bid has to raise, as the notice stated it.
     *
     * @param method {@code DIFFERENCE} is the CPWD form's own arithmetic — the threshold share
     *               of the estimate less what was bid. It grows far faster than a percentage:
     *               thirty percent below a one-crore estimate is ten lakh of extra guarantee.
     *               {@code PERCENT_OF_BID} is the flat levy other departments use.
     */
    public record AdditionalGuarantee(BigDecimal thresholdPercent, String method,
                                      BigDecimal percent) {

        public static final String DIFFERENCE = "DIFFERENCE";
        public static final String PERCENT_OF_BID = "PERCENT_OF_BID";
    }

    /**
     * What things cost this contractor.
     *
     * @param dailyWageByTrade   by skill code. Absent trades fall back to
     *                           {@code defaultDailyWage}, which is recorded as an assumption
     *                           rather than quietly applied.
     * @param workingDaysPerMonth the site's own figure; 26 is the ordinary answer
     */
    public record CostBasis(
            Map<String, BigDecimal> dailyWageByTrade,
            BigDecimal defaultDailyWage,
            int workingDaysPerMonth,
            BigDecimal monthlyStaffCost,
            BigDecimal siteSetupCost,
            BigDecimal monthlyPlantAndTransport,
            BigDecimal bankGuaranteeCommissionPercent) {
    }

    /** The catalogue, already narrowed to this organisation. */
    public record Norms(
            List<ProductivityNorm> productivity,
            List<SequenceNorm> sequence,
            List<ConsumptionNorm> consumption,
            Map<String, LeadTime> leadTimeByMaterialCode) {
    }

    /** @param manDaysPerUnit of one trade, per unit of work in this category */
    public record ProductivityNorm(
            String category,
            String subType,
            String skillCode,
            boolean skilled,
            String unitCode,
            BigDecimal manDaysPerUnit) {
    }

    public record SequenceNorm(
            String category,
            int rank,
            BigDecimal maxOverlapPercent,
            int maxConcurrentGangs,
            boolean monsoonSensitive) {
    }

    /** @param qtyPerWorkUnit of a material, per unit of work in this category */
    public record ConsumptionNorm(
            String category,
            String subType,
            String materialCode,
            String materialName,
            String workUnitCode,
            BigDecimal qtyPerWorkUnit,
            BigDecimal standardRate) {
    }

    /**
     * @param shelfLifeDays null where the material does not deteriorate. What stops the answer
     *                      being "order the whole job in month one".
     */
    public record LeadTime(int leadDays, int bufferDays, Integer shelfLifeDays, boolean storable) {

        public int orderAheadDays() {
            return leadDays + bufferDays;
        }
    }
}

package in.nirman.modules.tender.parser;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Everything a Notice Inviting Tender was read to say.
 *
 * <p>Every field is nullable, and that is the design. A tender is a scanned-and-retyped
 * government document with no schema; some notices omit the electrical component, some print
 * the estimated cost only in words, some defeat the reader entirely. A field that could not be
 * found is {@code null} rather than zero or empty, so the preview can show the user what to
 * fill in rather than presenting a confident wrong number.</p>
 *
 * @param scheduleF the contractual terms the planner runs on — milestones, the time allowed,
 *                  and when a bill may be raised. Never null; its own fields are absent
 *                  individually. See {@code docs/10-planning-and-execution-strategy.md}.
 * @param warnings what the reader is unsure about, in the user's language. These are the
 *                 honest part of the output and the preview shows them prominently.
 */
public record NitExtraction(
        String fileName,
        int pageCount,
        String nitNo,
        String workName,
        BigDecimal estimatedCost,
        BigDecimal civilEstimatedCost,
        BigDecimal electricalEstimatedCost,
        BigDecimal emdAmount,
        String completionPeriod,
        LocalDateTime submissionClosing,
        LocalDateTime bidOpening,
        String division,
        String location,
        String bidType,
        String contractorEligibility,
        String similarWorkCriteria,
        BigDecimal performanceGuaranteePercent,
        BigDecimal securityDepositPercent,
        Integer civilDsrYear,
        BigDecimal civilCostIndexPercent,
        Integer electricalDsrYear,
        BigDecimal electricalCostIndexPercent,
        List<BoqLine> boqItems,
        BigDecimal boqTotal,
        ScheduleFExtractor.ScheduleF scheduleF,
        List<String> warnings) {

    /**
     * The parser's own version, stored alongside a result so a re-read can be compared.
     *
     * <p>1.1.0 added the Schedule F reading: milestones, the time allowed as a number rather
     * than as printed text, the date-of-start reckoning, and the Clause 7 interim minimums.
     * Nothing that 1.0.0 extracted changed, so a document read by both differs only by
     * addition.</p>
     */
    public static final String PARSER_VERSION = "1.1.0";
}

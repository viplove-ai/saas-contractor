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
        List<String> warnings) {

    /** The parser's own version, stored alongside a result so a re-read can be compared. */
    public static final String PARSER_VERSION = "1.0.0";
}

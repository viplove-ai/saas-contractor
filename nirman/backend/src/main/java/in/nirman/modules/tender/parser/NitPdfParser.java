package in.nirman.modules.tender.parser;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a CPWD Notice Inviting Tender into structured data.
 *
 * <p>A NIT is a 130-to-240 page PDF whose useful content is concentrated in two places: a
 * summary table near the front carrying the identifiers and the money, and a Schedule of
 * Quantities near the back carrying the priced work. The rest is standard conditions,
 * identical across thousands of tenders. So the reader deliberately narrows before it
 * searches — most fields are taken only from the summary page, because the same words appear
 * in the boilerplate and a document-wide search finds the boilerplate first.</p>
 *
 * <p>This is a port of {@code tender-intelligence/src/nit_parser.py}, kept close to the
 * original on purpose: the patterns were tuned against a corpus this codebase does not have,
 * and the fixture tests assert that the two implementations agree field for field.</p>
 */
public final class NitPdfParser {

    /** Identifiers and money live in the front matter; the rest of the file is conditions. */
    private static final int FRONT_PAGES = 20;

    /** Eligibility clauses copied from an unrelated tender usually mention these. */
    private static final Set<String> UNRELATED_SCOPE_WORDS =
            Set.of("housekeeping", "caretaking", "ward", "vehicle", "manpower");

    private NitPdfParser() {
    }

    public static NitExtraction parse(byte[] content, String fileName) {
        List<String> pageTexts = NitTextExtractor.pageTexts(content);
        return parsePages(pageTexts, fileName);
    }

    /** Split out from {@link #parse} so tests can drive the reader from captured text. */
    public static NitExtraction parsePages(List<String> pageTexts, String fileName) {
        String text = String.join("\n", pageTexts);
        String front = String.join("\n", pageTexts.subList(0, Math.min(FRONT_PAGES, pageTexts.size())));
        String detail = detailPage(pageTexts, front);

        String nitNo = first(NitPatterns.NIT_NO, detail);
        if (nitNo != null) {
            nitNo = TextCleaning.strip(
                    NitPatterns.NIT_NO_SLASH.matcher(nitNo).replaceAll("/"), " .:-");
        }
        String workName = first(NitPatterns.WORK_NAME, detail);

        BigDecimal estimated = money(NitPatterns.ESTIMATED_COST, detail);
        // A composite notice lists each component and then repeats the sum as "(Total)".
        // When that is present it is the authoritative figure.
        BigDecimal totalAfterAmount = money(NitPatterns.TOTAL_AFTER_AMOUNT, detail);
        if (totalAfterAmount != null && totalAfterAmount.signum() != 0) {
            estimated = totalAfterAmount;
        }
        BigDecimal civil = firstNonZero(
                money(NitPatterns.CIVIL_COST_LABELLED, detail),
                money(NitPatterns.CIVIL_COST_BRACKETED, detail));
        BigDecimal electrical = firstNonZero(
                money(NitPatterns.ELECTRICAL_COST_LABELLED, detail),
                money(NitPatterns.ELECTRICAL_COST_BRACKETED, detail));
        BigDecimal emd = money(NitPatterns.EARNEST_MONEY, detail);
        String completion = first(NitPatterns.COMPLETION_PERIOD, detail);

        String division = firstNonNull(
                first(NitPatterns.DIVISION_HEADING, detail),
                first(NitPatterns.DIVISION_BRACKETED, front),
                first(NitPatterns.DIVISION_DASHED, front));
        String location = location(detail, front, workName);

        String bidType = NitPatterns.PERCENTAGE_RATE_BID.matcher(front).find()
                ? "Percentage Rate" : null;
        String eligibility = firstNonNull(
                first(NitPatterns.ELIGIBILITY_ENLISTED, front),
                first(NitPatterns.ELIGIBILITY_CPWD, front));
        String similar = firstNonNull(
                first(NitPatterns.SIMILAR_WORK, text),
                specialisedSimilarWork(front));

        BigDecimal performance = money(NitPatterns.PERFORMANCE_GUARANTEE, text);
        BigDecimal security = money(NitPatterns.SECURITY_DEPOSIT, text);

        List<RateScheduleExtractor.RateSchedule> schedules = RateScheduleExtractor.schedules(text);
        Integer civilDsrYear = schedules.size() > 0 ? schedules.get(0).year() : null;
        BigDecimal civilCostIndex = schedules.size() > 0 ? schedules.get(0).costIndexPercent() : null;
        Integer electricalDsrYear = schedules.size() > 1 ? schedules.get(1).year() : null;
        BigDecimal electricalCostIndex =
                schedules.size() > 1 ? schedules.get(1).costIndexPercent() : null;

        BoqScheduleParser.Result boq = BoqScheduleParser.parse(pageTexts, estimated);

        // Schedule F is read from the whole document rather than the front matter: it sits two
        // hundred pages in, and in three of the ten corpus notices the milestone table is an
        // annexure behind the schedule of quantities.
        ScheduleFExtractor.ScheduleF scheduleF = ScheduleFExtractor.extract(text, completion);

        return new NitExtraction(fileName, pageTexts.size(), nitNo, workName, estimated, civil,
                electrical, emd, completion,
                DeadlineExtractor.submissionClosing(front), DeadlineExtractor.bidOpening(front),
                division, location, bidType, eligibility, similar, performance, security,
                civilDsrYear, civilCostIndex, electricalDsrYear, electricalCostIndex,
                boq.items(), boq.total(), scheduleF,
                warnings(nitNo, workName, estimated, similar, boq));
    }

    /**
     * The summary page states the NIT number, the estimated cost and the earnest money
     * together. Reading the scalar fields from that one page rather than from the whole
     * document is what stops a boilerplate clause 90 pages later supplying the answer.
     */
    private static String detailPage(List<String> pageTexts, String fallback) {
        for (String page : pageTexts.subList(0, Math.min(FRONT_PAGES, pageTexts.size()))) {
            if (NitPatterns.NIT_NO_LABEL.matcher(page).find()
                    && NitPatterns.ESTIMATED_COST_LABEL.matcher(page).find()
                    && NitPatterns.EARNEST_MONEY_LABEL.matcher(page).find()) {
                return page;
            }
        }
        return fallback;
    }

    private static String location(String detail, String front, String workName) {
        String location = firstNonNull(
                first(NitPatterns.LOCATION, detail),
                first(NitPatterns.LOCATION, front));
        // A capture that ran past its cell and swallowed the next label is not a location.
        if (location != null
                && (location.length() > 200 || NitPatterns.LOCATION_BLEED.matcher(location).find())) {
            location = null;
        }
        if (location == null && workName != null) {
            // Almost every work name ends "... at <place>." — the place is the site.
            location = first(NitPatterns.LOCATION_IN_WORK_NAME, workName);
        }
        return location;
    }

    /**
     * Specialised tenders do not carry the standard similar-work paragraph; they define the
     * term inline, sometimes several times for several trades. All the distinct definitions
     * are worth keeping, because together they are the eligibility rule.
     */
    private static String specialisedSimilarWork(String text) {
        String normalised = text.replaceAll("\\s+", " ");
        Set<String> unique = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = NitPatterns.SIMILAR_WORK_DEFINITION.matcher(normalised);
        while (matcher.find()) {
            String cleaned = TextCleaning.compact(matcher.group(1));
            if (cleaned != null && seen.add(cleaned.toLowerCase(Locale.ROOT))) {
                unique.add(cleaned);
            }
        }
        if (unique.isEmpty()) {
            return null;
        }
        return "Specialized-work definitions: " + String.join("; ", unique);
    }

    /**
     * Note that nothing here reports on Schedule F, deliberately. This list is held to the
     * Python reference's output field for field by {@code NitPdfParserFixtureTest}, and the
     * reference never read Schedule F. Warnings about a missing milestone table are added a
     * layer up, in {@code NitImportService}, which already exists to carry warnings the parser
     * itself could not have produced.
     */
    private static List<String> warnings(String nitNo, String workName, BigDecimal estimated,
                                         String similar, BoqScheduleParser.Result boq) {
        List<String> warnings = new ArrayList<>();
        if (estimated != null && estimated.signum() != 0 && boq.total() != null
                && boq.total().signum() != 0 && !TextCleaning.withinOneRupee(estimated, boq.total())) {
            warnings.add("The BOQ total does not match the stated estimated cost.");
        }
        if (boq.items().isEmpty()) {
            warnings.add("No priced BOQ rows were detected; "
                    + "verify the Schedule of Quantities manually.");
        }
        // An eligibility clause about housekeeping staff, on a tender to build a boundary
        // wall, was pasted from a different notice. That is worth saying out loud: bidding
        // against the wrong eligibility rule is an expensive mistake.
        Set<String> workWords = words(workName);
        Set<String> criteriaWords = words(similar);
        boolean suspicious = criteriaWords.stream()
                .anyMatch(word -> UNRELATED_SCOPE_WORDS.contains(word) && !workWords.contains(word));
        if (suspicious) {
            warnings.add("The similar-work eligibility appears unrelated to the tender scope "
                    + "and may be a copied clause.");
        }
        if (nitNo == null) {
            warnings.add("NIT number was not detected.");
        }
        if (estimated == null || estimated.signum() == 0) {
            warnings.add("Estimated cost was not detected.");
        }
        return warnings;
    }

    private static Set<String> words(String value) {
        Set<String> words = new LinkedHashSet<>();
        if (value == null) {
            return words;
        }
        Matcher matcher = NitPatterns.WORD.matcher(value.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return words;
    }

    // ------------------------------------------------------------------ matching helpers

    /** The first capture of {@code pattern}, whitespace-collapsed, or null. */
    static String first(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? TextCleaning.compact(matcher.group(1)) : null;
    }

    static BigDecimal money(Pattern pattern, String text) {
        return TextCleaning.parseCurrency(first(pattern, text));
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** Mirrors the reference's falsiness: a zero reading counts as "not found". */
    private static BigDecimal firstNonZero(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null && value.signum() != 0) {
                return value;
            }
        }
        return null;
    }
}

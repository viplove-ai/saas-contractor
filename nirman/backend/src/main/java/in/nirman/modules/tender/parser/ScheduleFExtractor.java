package in.nirman.modules.tender.parser;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Reads Schedule F — the terms that decide when work must be finished and when it gets paid.
 *
 * <p>Everything the existing reader takes comes off the summary page near the front, where a
 * notice is at its most uniform. Schedule F is the opposite: it is retyped per division, so the
 * same field appears in four layouts across ten documents, and it sits two hundred pages in
 * among boilerplate that repeats its vocabulary. The strategy that follows from that is to
 * <b>anchor hard and read locally</b> — find the one heading that can only be the milestone
 * table, cut a region, and parse inside it — rather than to search the document for a field.</p>
 *
 * <p>What comes out of here is what turns a tender into a plan. The milestones are the phase
 * boundaries the contract is enforced against; the Clause 7 minimums set the billing rhythm and
 * therefore the depth of the cash trough; Clause 7A decides whether the first rupee arrives at
 * all. See {@code docs/10-planning-and-execution-strategy.md} §2.</p>
 */
public final class ScheduleFExtractor {

    /** Enough to hold the longest milestone table in the corpus, which runs five prose rows. */
    private static final int REGION_CHARS = 9000;

    /** How far into the region the column headings can still be. */
    private static final int HEADER_WINDOW_CHARS = 1200;

    /** Where a Clause 7 figure sits relative to the clause's own words. */
    private static final int CLAUSE_7_WINDOW = 400;

    /** Residue shorter than this is punctuation and filler, not a description of work. */
    private static final int PHYSICAL_RESIDUE_CHARS = 30;

    private static final BigDecimal LAKH = new BigDecimal("100000");
    private static final BigDecimal CRORE = new BigDecimal("10000000");

    private ScheduleFExtractor() {
    }

    /**
     * Everything Schedule F was read to say. Absent fields are null and absent lists are empty,
     * never a zero or a placeholder — a plan built on an invented threshold is worse than one
     * that says it does not know the threshold.
     */
    public record ScheduleF(
            AllowedTime completionTime,
            Integer startReckoningDays,
            List<MilestoneLine> milestones,
            List<InterimMinimum> interimMinimums,
            Boolean clause7aApplicable,
            AdditionalGuarantee additionalGuarantee) {

        public static final ScheduleF EMPTY =
                new ScheduleF(null, null, List.of(), List.of(), null, null);
    }

    /**
     * The second guarantee a low bid has to raise.
     *
     * <p>Null where the notice says nothing, and that is a reading: nine of the ten in the corpus
     * carry no such clause, and applying one anyway would invent a lakh of bank guarantee that
     * nobody asked the contractor for.</p>
     *
     * @param thresholdPercent the bid must fall below this share of the estimate before any of
     *                         it is due
     * @param method           {@link #DIFFERENCE} is the CPWD form's own arithmetic — the
     *                         threshold share of the estimate <i>less</i> what was bid, which
     *                         grows far faster than a percentage. {@link #PERCENT_OF_BID} is the
     *                         flat levy other departments use.
     */
    public record AdditionalGuarantee(BigDecimal thresholdPercent, String method,
                                      BigDecimal percent) {

        public static final String DIFFERENCE = "DIFFERENCE";
        public static final String PERCENT_OF_BID = "PERCENT_OF_BID";
    }

    public static ScheduleF extract(String text, String completionPeriodText) {
        if (text == null || text.isBlank()) {
            return ScheduleF.EMPTY;
        }
        return new ScheduleF(
                completionTime(text, completionPeriodText),
                startReckoningDays(text),
                milestones(text),
                interimMinimums(text),
                clause7aApplicable(text),
                additionalGuarantee(text));
    }

    // ------------------------------------------------------------------ time allowed

    /**
     * Schedule F's own statement wins over the summary page's. Both say the same thing in all
     * ten notices, but Schedule F is the contractual one, and where they ever disagree the
     * contract is the answer.
     */
    static AllowedTime completionTime(String text, String completionPeriodText) {
        Matcher scheduleF = NitPatterns.TIME_ALLOWED.matcher(text);
        if (scheduleF.find()) {
            AllowedTime time = AllowedTime.of(scheduleF.group(1), scheduleF.group(2));
            if (time != null) {
                return time;
            }
        }
        return durationIn(completionPeriodText);
    }

    /** @return the duration inside free text such as {@code "12 (Twelve) Months"}, or null */
    static AllowedTime durationIn(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher matcher = NitPatterns.DURATION_IN_TEXT.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        // Years appear in no notice in the corpus, but the summary-page pattern accepts them,
        // so they are folded here rather than silently dropped as an unknown unit.
        if (matcher.group(2).toLowerCase(Locale.ROOT).startsWith("year")) {
            try {
                return new AllowedTime(Integer.parseInt(matcher.group(1)) * 12,
                        AllowedTime.Unit.MONTHS);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return AllowedTime.of(matcher.group(1), matcher.group(2));
    }

    static Integer startReckoningDays(String text) {
        Matcher matcher = NitPatterns.START_RECKONING_DAYS.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            int days = Integer.parseInt(matcher.group(1));
            return days > 0 && days <= 365 ? days : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ milestones

    /**
     * The milestone table, from whichever occurrence of the heading actually carries rows.
     *
     * <p>Three of the ten notices print the heading three times — once in a contents list, once
     * as an annexure title, once over the table itself — so position is no guide to which is
     * which. Rather than guess, every occurrence is parsed and the richest reading wins. That
     * costs a few passes over nine kilobytes and removes a whole class of silent
     * mis-anchoring.</p>
     */
    static List<MilestoneLine> milestones(String text) {
        List<MilestoneLine> best = List.of();
        Matcher heading = NitPatterns.MILESTONE_TABLE.matcher(text);
        while (heading.find()) {
            List<MilestoneLine> found = parseRows(region(text, heading.end()));
            if (found.size() > best.size()) {
                best = found;
            }
        }
        return best;
    }

    /** The table's rows: cut at whatever follows, page breaks removed, headings skipped. */
    private static String region(String text, int from) {
        String slice = text.substring(from, Math.min(text.length(), from + REGION_CHARS));
        Matcher end = NitPatterns.MILESTONE_TABLE_END.matcher(slice);
        if (end.find()) {
            slice = slice.substring(0, end.start());
        }
        return afterColumnHeadings(withoutPageFurniture(slice).replaceAll("\\s+", " ").strip());
    }

    /** Line-based, and so done before the whitespace collapse that makes the rows one string. */
    private static String withoutPageFurniture(String slice) {
        StringBuilder kept = new StringBuilder(slice.length());
        for (String line : slice.split("\n", -1)) {
            if (!NitPatterns.MILESTONE_FURNITURE.matcher(line.strip()).matches()) {
                kept.append(line).append('\n');
            }
        }
        return kept.toString();
    }

    /**
     * Skips the column headings, taking the <b>last</b> heading close to the start.
     *
     * <p>Three notices print "TABLE OF MILE STONES" more than once — a contents entry, an
     * annexure title, then the table — and the region may therefore open on a heading that is
     * still several lines above the real one. Taking the last match inside the opening window
     * lands after the true heading row in every case, where taking the first leaves the column
     * titles inside milestone 1's description.</p>
     */
    private static String afterColumnHeadings(String flat) {
        Matcher header = NitPatterns.MILESTONE_HEADER_END
                .matcher(flat.substring(0, Math.min(flat.length(), HEADER_WINDOW_CHARS)));
        int start = -1;
        while (header.find()) {
            start = header.end();
        }
        return start < 0 ? flat : flat.substring(start).strip();
    }

    /**
     * Rows, read left to right so each match resumes where the last ended.
     *
     * <p>That sequencing is what segments the table: a row is recognised by its tail — a
     * duration then a percentage — and the description is whatever lies between the previous
     * row's end and this row's tail. It is also why the sequence numbers are only sanity
     * checked rather than trusted; a table that numbers its rows 1, 2, 2, 4 still parses.</p>
     */
    private static List<MilestoneLine> parseRows(String region) {
        List<MilestoneLine> rows = new ArrayList<>();
        Matcher row = NitPatterns.MILESTONE_ROW.matcher(region);
        int expected = 1;
        while (row.find()) {
            String description = TextCleaning.compact(row.group(2));
            if (description == null) {
                continue;
            }
            AllowedTime time = AllowedTime.of(row.group(3), row.group(4));
            int sequence = sequence(row.group(1), expected);
            rows.add(new MilestoneLine(sequence, description, time,
                    null, percent(row.group(5)), physical(description)));
            expected = sequence + 1;
        }
        return assignFinancialPercents(rows);
    }

    /**
     * Fills in each row's financial percentage, repairing the one way the table's columns
     * mislead a line-oriented reader.
     *
     * <p>A milestone's financial equivalent lives in the description column, but on the notices
     * that print a physical milestone the cell is long enough to wrap past the time and
     * withholding columns beside it — and where it wraps across a page break, PDFBox emits it
     * <i>after</i> the row it belongs to, so it arrives at the head of the next row's text.
     * The result is a row carrying two percentages, the first of which is its predecessor's,
     * while the predecessor carries none.</p>
     *
     * <p>So the rule: a row's own percentage is the <b>last</b> one in its text, and where a row
     * holds more than one and the row above holds none, the first is handed back up. Both
     * conditions are required, which is what keeps it from firing on a description that merely
     * mentions two figures.</p>
     */
    private static List<MilestoneLine> assignFinancialPercents(List<MilestoneLine> rows) {
        List<List<BigDecimal>> found = new ArrayList<>(rows.size());
        for (MilestoneLine row : rows) {
            found.add(financialPercents(row.description()));
        }
        boolean[] leaked = new boolean[rows.size()];
        BigDecimal[] own = new BigDecimal[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            List<BigDecimal> mine = found.get(i);
            own[i] = mine.isEmpty() ? null : mine.get(mine.size() - 1);
        }
        for (int i = 0; i + 1 < rows.size(); i++) {
            if (own[i] == null && found.get(i + 1).size() > 1) {
                own[i] = found.get(i + 1).get(0);
                leaked[i + 1] = true;
            }
        }

        List<MilestoneLine> resolved = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            MilestoneLine row = rows.get(i);
            String description = leaked[i] ? withoutLeadingFinancial(row.description())
                    : row.description();
            resolved.add(new MilestoneLine(row.sequence(), description, row.timeAllowed(),
                    own[i], row.withheldPercent(), physical(description)));
        }
        return resolved;
    }

    /**
     * Drops a financial clause that opens a description because it belonged to the row above.
     *
     * <p>Only called where that has just been established. The description is the half of a
     * milestone the planner phases the work from, so leaving the previous milestone's target
     * at the head of it would attach the wrong percentage to the wrong set of activities.</p>
     */
    private static String withoutLeadingFinancial(String description) {
        Matcher clause = NitPatterns.MILESTONE_FINANCIAL_CLAUSE.matcher(description);
        if (!clause.find()) {
            return description;
        }
        String remainder = TextCleaning.compact(description.substring(clause.end()));
        return remainder == null ? description : remainder;
    }

    /**
     * The printed number where it is plausible, the running count where it is not. A row whose
     * number was lost to the column layout is still a row, and renumbering it silently is
     * better than dropping it.
     */
    private static int sequence(String printed, int expected) {
        try {
            int value = Integer.parseInt(printed);
            return value == expected ? value : expected;
        } catch (NumberFormatException e) {
            return expected;
        }
    }

    /**
     * Every percentage in the description spoken of as a share of the tender, in order.
     *
     * <p>Falls back to the completion phrasing only when the tender-anchored reading finds
     * nothing at all, so a row that states its share properly is never second-guessed.</p>
     */
    static List<BigDecimal> financialPercents(String description) {
        List<BigDecimal> percents = new ArrayList<>(2);
        Matcher matcher = NitPatterns.MILESTONE_FINANCIAL.matcher(description);
        while (matcher.find()) {
            BigDecimal value = percent(matcher.group(1));
            if (value != null) {
                percents.add(value);
            }
        }
        if (percents.isEmpty()) {
            Matcher completion = NitPatterns.MILESTONE_COMPLETION_PERCENT.matcher(description);
            while (completion.find()) {
                BigDecimal value = percent(completion.group(1));
                if (value != null) {
                    percents.add(value);
                }
            }
        }
        return percents;
    }

    /**
     * Whether the row names work rather than only a figure.
     *
     * <p>Decided by subtraction: take out the financial phrasing and the connective filler, and
     * see whether a sentence is left. "15% of Tendered Amount" leaves nothing. "Civil Work:
     * mobilisation materials, T&amp;P, site laboratory … casting of grade slab or Financially
     * Gross value of work done : 10% of tendered Value" leaves the whole of the department's
     * phasing, which is the thing worth having.</p>
     */
    static boolean physical(String description) {
        String residue = NitPatterns.MILESTONE_FINANCIAL_CLAUSE.matcher(description)
                .replaceAll(" ");
        residue = NitPatterns.MILESTONE_RESIDUE_NOISE.matcher(residue).replaceAll(" ");
        return residue.replaceAll("\\s+", " ").strip().length() >= PHYSICAL_RESIDUE_CHARS;
    }

    private static BigDecimal percent(String value) {
        BigDecimal parsed = TextCleaning.parseCurrency(value);
        if (parsed == null || parsed.signum() < 0 || parsed.compareTo(new BigDecimal("100")) > 0) {
            return null;
        }
        return parsed;
    }

    // ------------------------------------------------------------------ Clause 7 and 7A

    /**
     * The interim-payment thresholds, read in the window after Clause 7's own words.
     *
     * <p>The labelled and bracketed forms are tried before the bare one, because a composite
     * notice states both parts and a bare read would take the civil figure and call it the
     * whole contract's — halving the apparent working capital on exactly the tenders where it
     * matters most. Where the clause defers elsewhere ("As Per Part-A of NIT", in one of the
     * ten) nothing is returned, which is the correct reading rather than a failed one.</p>
     */
    static List<InterimMinimum> interimMinimums(String text) {
        Matcher anchor = NitPatterns.INTERIM_PAYMENT_ANCHOR.matcher(text);
        if (!anchor.find()) {
            return List.of();
        }
        String window = text.substring(anchor.end(),
                Math.min(text.length(), anchor.end() + CLAUSE_7_WINDOW))
                .replaceAll("\\s+", " ");

        // Keyed by work part so a second reading of the same part cannot double up.
        Map<String, BigDecimal> byPart = new LinkedHashMap<>();
        putIfFound(byPart, BoqLine.CIVIL, window, NitPatterns.INTERIM_CIVIL_BRACKETED);
        putIfFound(byPart, BoqLine.ELECTRICAL, window, NitPatterns.INTERIM_ELECTRICAL_BRACKETED);
        putIfFound(byPart, BoqLine.CIVIL, window, NitPatterns.INTERIM_CIVIL_LABELLED);
        putIfFound(byPart, BoqLine.ELECTRICAL, window, NitPatterns.INTERIM_ELECTRICAL_LABELLED);

        if (!byPart.isEmpty()) {
            List<InterimMinimum> minimums = new ArrayList<>(byPart.size());
            byPart.forEach((part, amount) -> minimums.add(new InterimMinimum(part, amount)));
            return minimums;
        }
        Matcher bare = NitPatterns.INTERIM_BARE.matcher(window);
        if (bare.find()) {
            BigDecimal amount = amount(bare.group(1), bare.group(2));
            if (amount != null) {
                return List.of(new InterimMinimum(null, amount));
            }
        }
        return List.of();
    }

    private static void putIfFound(Map<String, BigDecimal> into, String workPart, String window,
                                   java.util.regex.Pattern pattern) {
        if (into.containsKey(workPart)) {
            return;
        }
        Matcher matcher = pattern.matcher(window);
        if (!matcher.find()) {
            return;
        }
        BigDecimal amount = amount(matcher.group(1), matcher.group(2));
        if (amount != null) {
            into.put(workPart, amount);
        }
    }

    /** {@code 21} + {@code Lakhs} is ₹21,00,000. A figure with no multiplier is already rupees. */
    private static BigDecimal amount(String number, String multiplier) {
        BigDecimal value = TextCleaning.parseCurrency(number);
        if (value == null || value.signum() <= 0) {
            return null;
        }
        if (multiplier == null) {
            return value;
        }
        String lower = multiplier.toLowerCase(Locale.ROOT);
        if (lower.startsWith("crore")) {
            return value.multiply(CRORE);
        }
        return lower.startsWith("lakh") || lower.startsWith("lac") ? value.multiply(LAKH) : value;
    }

    /**
     * The additional performance guarantee clause, where the notice carries one.
     *
     * <p>The threshold and the arithmetic are read separately because they are stated in separate
     * sentences, and a notice that names a threshold without saying how the amount is worked out
     * is worth nothing to a plan — so both must be found or neither is returned.</p>
     */
    static AdditionalGuarantee additionalGuarantee(String text) {
        Matcher threshold = NitPatterns.APG_THRESHOLD.matcher(text);
        if (threshold.find()) {
            BigDecimal at = percent(threshold.group(1));
            Matcher difference = NitPatterns.APG_DIFFERENCE.matcher(text);
            if (at != null && difference.find()) {
                return new AdditionalGuarantee(at,
                        AdditionalGuarantee.DIFFERENCE, null);
            }
            Matcher flat = NitPatterns.APG_PERCENT_OF_BID.matcher(text);
            if (at != null && flat.find()) {
                return new AdditionalGuarantee(at,
                        AdditionalGuarantee.PERCENT_OF_BID, percent(flat.group(1)));
            }
        }
        Matcher flat = NitPatterns.APG_PERCENT_OF_BID.matcher(text);
        if (flat.find()) {
            // A flat levy stated without its threshold: the standard trigger is 80%.
            return new AdditionalGuarantee(new BigDecimal("80"),
                    AdditionalGuarantee.PERCENT_OF_BID, percent(flat.group(1)));
        }
        return null;
    }

    /** @return null where the notice defers the answer, rather than a guessed false */
    static Boolean clause7aApplicable(String text) {
        Matcher matcher = NitPatterns.CLAUSE_7A.matcher(text);
        return matcher.find() ? "yes".equalsIgnoreCase(matcher.group(1)) : null;
    }
}

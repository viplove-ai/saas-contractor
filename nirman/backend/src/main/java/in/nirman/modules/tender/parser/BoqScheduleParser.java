package in.nirman.modules.tender.parser;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the Schedule of Quantities out of a tender's page text.
 *
 * <p>A schedule is a table, but by the time it reaches here it is plain text, and a row's
 * cells may or may not have survived on one line. So the reader is a small state machine
 * rather than a line matcher: an item number opens a row, every following line is appended to
 * its description, and the row closes the moment the accumulated text ends in the
 * quantity-unit-rate-amount tail that {@link NitPatterns#PRICED_END} describes.</p>
 *
 * <p>That design is what makes the parser tolerant of where the text extractor chose to break
 * lines — the same row split across two lines still closes correctly — and it is why the
 * extractor is tuned to over-split rather than under-split. Two rows merged onto one line
 * would close as a single wrong row, and nothing downstream could tell.</p>
 */
final class BoqScheduleParser {

    /** The schedule and its total, as read. */
    record Result(List<BoqLine> items, BigDecimal total) {}

    private BoqScheduleParser() {
    }

    static Result parse(List<String> pageTexts, BigDecimal estimatedCost) {
        int start = firstSchedulePage(pageTexts);
        if (start < 0) {
            return new Result(List.of(), null);
        }
        List<BoqLine> items = new ArrayList<>();
        String workPart = BoqLine.CIVIL;
        String currentNo = null;
        List<String> descriptionParts = new ArrayList<>();

        for (String page : pageTexts.subList(start, pageTexts.size())) {
            // Which schedule a page belongs to is stated in its heading, so the work part is
            // re-read per page rather than guessed from the item numbering.
            if (NitPatterns.ELECTRICAL_SCHEDULE.matcher(page).find()) {
                workPart = BoqLine.ELECTRICAL;
            } else if (NitPatterns.CIVIL_SCHEDULE.matcher(page).find()) {
                workPart = BoqLine.CIVIL;
            }
            for (String raw : page.split("\\R", -1)) {
                String line = raw.strip();
                if (line.isEmpty() || NitPatterns.PAGE_FURNITURE.matcher(line).find()) {
                    continue;
                }
                Matcher newItem = NitPatterns.ITEM_START.matcher(line);
                if (newItem.matches() && !isFalseItemStart(newItem.group(1), newItem.group(2), currentNo)) {
                    currentNo = newItem.group(1);
                    String remainder = newItem.group(2);
                    descriptionParts = new ArrayList<>(List.of(remainder));
                    Matcher priced = NitPatterns.PRICED_END.matcher(remainder);
                    if (priced.matches()) {
                        items.add(toLine(currentNo, priced, workPart));
                        currentNo = null;
                        descriptionParts = new ArrayList<>();
                    }
                    continue;
                }
                if (currentNo == null) {
                    continue;
                }
                descriptionParts.add(line);
                String combined = String.join(" ", descriptionParts);
                Matcher priced = NitPatterns.PRICED_END.matcher(combined);
                if (priced.matches()) {
                    items.add(toLine(currentNo, priced, workPart));
                    currentNo = null;
                    descriptionParts = new ArrayList<>();
                }
            }
        }
        return new Result(items, total(pageTexts.subList(start, pageTexts.size()), estimatedCost));
    }

    /**
     * The BOQ begins at the first page that both announces the schedule and carries a
     * quantity column. An index page naming the schedule has no such column.
     */
    private static int firstSchedulePage(List<String> pageTexts) {
        for (int i = 0; i < pageTexts.size(); i++) {
            String page = pageTexts.get(i);
            if (NitPatterns.SCHEDULE_HEADING.matcher(page).find()
                    && NitPatterns.QUANTITY_COLUMN.matcher(page).find()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Two things look like an item number and are not.
     *
     * <p>A wrapped quantity cell — {@code 12.00 cum 450.00 5400.00} — begins with what reads
     * as item 12.00. And a measurement inside a description — {@code 1.5 m dia} — reads as
     * item 1.5. Treating either as a new row abandons the row actually being accumulated.</p>
     */
    private static boolean isFalseItemStart(String token, String remainder, String currentNo) {
        boolean quantityToken = NitPatterns.QUANTITY_TOKEN.matcher(token).matches()
                && NitPatterns.QUANTITY_TOKEN_UNIT.matcher(remainder).lookingAt();
        boolean measurementFragment = currentNo != null
                && NitPatterns.MEASUREMENT_TOKEN.matcher(token).matches()
                && NitPatterns.MEASUREMENT_FRAGMENT.matcher(remainder).lookingAt();
        return quantityToken || measurementFragment;
    }

    private static BoqLine toLine(String itemNo, Matcher priced, String workPart) {
        // Groups: description, quantity, unit, rate, an optional second rate column, amount.
        return new BoqLine(
                itemNo,
                TextCleaning.compact(priced.group(1)),
                TextCleaning.parseCurrency(priced.group(2)),
                TextCleaning.stripTrailing(priced.group(3).strip(), "."),
                TextCleaning.parseCurrency(priced.group(4)),
                TextCleaning.parseCurrency(priced.group(6)),
                workPart);
    }

    /**
     * Picks the schedule's stated total from the several the document prints.
     *
     * <p>A composite tender totals each schedule and then totals the totals, so simply taking
     * the last figure would report the electrical subtotal on some layouts. Preference goes
     * to a figure that agrees with the tender's own estimated cost; failing that, to the sum
     * of the distinct figures if <i>that</i> agrees; and only then to the last one seen.</p>
     */
    private static BigDecimal total(List<String> pages, BigDecimal estimatedCost) {
        String tail = String.join("\n", pages);
        List<BigDecimal> values = new ArrayList<>();
        for (Pattern pattern : NitPatterns.BOQ_TOTALS) {
            Matcher matcher = pattern.matcher(tail);
            while (matcher.find()) {
                BigDecimal parsed = TextCleaning.parseCurrency(matcher.group(1));
                if (parsed != null) {
                    values.add(parsed);
                }
            }
        }
        for (BigDecimal value : values) {
            if (TextCleaning.withinOneRupee(value, estimatedCost)) {
                return value;
            }
        }
        if (values.isEmpty()) {
            return null;
        }
        // Distinct, in order: a Civil total plus an E&M total should add up to the composite
        // estimate, but the same figure printed twice should not be counted twice.
        Set<String> seen = new LinkedHashSet<>();
        BigDecimal combined = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            if (seen.add(value.stripTrailingZeros().toPlainString())) {
                combined = combined.add(value);
            }
        }
        if (TextCleaning.withinOneRupee(combined, estimatedCost)) {
            return estimatedCost;
        }
        return values.get(values.size() - 1);
    }
}

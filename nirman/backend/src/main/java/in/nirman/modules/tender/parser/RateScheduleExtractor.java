package in.nirman.modules.tender.parser;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the rate schedules a tender is priced against — the DSR year and the cost index
 * applied to it.
 *
 * <p>Those two numbers are how a rate on the page relates to a rate in the published schedule,
 * so a costing done later without them is a costing against the wrong base year.</p>
 */
final class RateScheduleExtractor {

    /** A published schedule and the percentage index applied to it. */
    record RateSchedule(int year, BigDecimal costIndexPercent) {}

    /**
     * How far past the heading to keep reading. The year and the index sit in the same
     * sentence or the same small table; a wider window starts collecting the next clause's
     * numbers.
     */
    private static final int BLOCK_CHARS = 800;

    /** Some encoders space out the digits of the year, so they are squeezed back together. */
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private RateScheduleExtractor() {
    }

    /**
     * @return the schedules in the order the document introduces them, without duplicates.
     *         Callers read position 0 as civil and position 1 as electrical, which is the
     *         order a composite CPWD notice uses.
     */
    static List<RateSchedule> schedules(String text) {
        List<RateSchedule> schedules = new ArrayList<>();
        Matcher anchor = NitPatterns.RATE_SCHEDULE_ANCHOR.matcher(text);
        while (anchor.find()) {
            String block = text.substring(anchor.start(),
                    Math.min(text.length(), anchor.start() + BLOCK_CHARS));

            // A composite notice names its disciplines, which is more reliable than position.
            for (String discipline : List.of("Civil", "Electrical")) {
                Matcher match = Pattern.compile(NitPatterns.disciplineSchedule(discipline),
                        Pattern.CASE_INSENSITIVE).matcher(block);
                if (match.find()) {
                    add(schedules, new RateSchedule(year(match.group(1)),
                            new BigDecimal(match.group(2))));
                }
            }

            Matcher year = NitPatterns.DSR_YEAR.matcher(block);
            if (!year.find()) {
                continue;
            }
            Matcher index = NitPatterns.COST_INDEX.matcher(block);
            BigDecimal costIndex = null;
            if (index.find()) {
                costIndex = new BigDecimal(index.group(1));
            } else {
                Matcher reversed = NitPatterns.COST_INDEX_REVERSED.matcher(block);
                if (reversed.find()) {
                    costIndex = new BigDecimal(reversed.group(1));
                }
            }
            if (costIndex == null) {
                continue;
            }
            add(schedules, new RateSchedule(year(year.group(1)), costIndex));
        }
        return schedules;
    }

    private static void add(List<RateSchedule> schedules, RateSchedule schedule) {
        boolean known = schedules.stream().anyMatch(existing ->
                existing.year() == schedule.year()
                        && existing.costIndexPercent().compareTo(schedule.costIndexPercent()) == 0);
        if (!known) {
            schedules.add(schedule);
        }
    }

    private static int year(String raw) {
        return Integer.parseInt(SPACES.matcher(raw).replaceAll(""));
    }
}

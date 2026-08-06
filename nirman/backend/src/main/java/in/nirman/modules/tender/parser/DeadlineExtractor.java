package in.nirman.modules.tender.parser;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the two dates a bidder actually has to act on: when submission closes and when bids
 * are opened.
 *
 * <p>Notices print these in no consistent order — "up to 15:30 on 01.07.2026" and
 * "01.07.2026 up to 15:30" are both common, sometimes in the same document — so rather than
 * fix an order the extractor works out which captured group is the date by looking at it.</p>
 */
final class DeadlineExtractor {

    /**
     * The orders a CPWD notice writes a date and time in. Tried in sequence, so a
     * two-digit-year format never parses; that is the reference behaviour and changing it
     * would silently reinterpret documents nobody re-checked.
     */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final List<DateTimeFormatter> FORMATS = List.of(
            format("d.M.yyyy h:mm a"), format("d.M.yyyy H:mm"),
            format("d/M/yyyy h:mm a"), format("d/M/yyyy H:mm"),
            format("d-M-yyyy h:mm a"), format("d-M-yyyy H:mm"));

    private DeadlineExtractor() {
    }

    static LocalDateTime submissionClosing(String text) {
        return firstMatch(NitPatterns.SUBMISSION_CLOSING, text);
    }

    static LocalDateTime bidOpening(String text) {
        return firstMatch(NitPatterns.BID_OPENING, text);
    }

    private static LocalDateTime firstMatch(List<Pattern> patterns, String text) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            if (!matcher.find()) {
                continue;
            }
            if (matcher.groupCount() == 2) {
                String first = matcher.group(1).strip();
                // Whichever group looks like a date is the date; the other is the time.
                return NitPatterns.BARE_DATE.matcher(first).matches()
                        ? parse(matcher.group(1), matcher.group(2))
                        : parse(matcher.group(2), matcher.group(1));
            }
            if (matcher.groupCount() == 1) {
                return parse(matcher.group(1), null);
            }
        }
        return null;
    }

    /** @return null when the pair does not form a date this parser recognises */
    static LocalDateTime parse(String date, String time) {
        if (date == null || date.isBlank()) {
            return null;
        }
        String normalised = (time == null || time.isBlank() ? "00:00" : time)
                .toUpperCase(Locale.ROOT).strip();
        normalised = NitPatterns.TRAILING_HRS.matcher(normalised).replaceAll("").replace('.', ':');
        // A notice wraps mid-value often enough that "03.30" and "PM" arrive on separate
        // lines. The reference parser tolerates it because Python's strptime treats a space
        // in the format as "any run of whitespace"; Java's wants the exact character, so the
        // run is collapsed here instead.
        String combined = WHITESPACE.matcher(date.strip() + " " + normalised).replaceAll(" ");
        for (DateTimeFormatter formatter : FORMATS) {
            try {
                return LocalDateTime.parse(combined, formatter);
            } catch (DateTimeParseException e) {
                // Try the next shape.
            }
        }
        return null;
    }

    private static DateTimeFormatter format(String pattern) {
        return new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(pattern)
                .toFormatter(Locale.ENGLISH);
    }
}

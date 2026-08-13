package in.nirman.modules.tender.parser;

import java.util.Locale;

/**
 * A span of time as the notice printed it — {@code 15 Days}, {@code 02 Month}, {@code 2 Months}.
 *
 * <p>The unit is kept rather than folded into days, because a CPWD month is a calendar month and
 * flattening it loses the difference. Twelve months from a 15th is the following year's 15th;
 * 360 days from it is five days short, and on a milestone that decides whether 1.25% of the
 * contract is withheld, five days is not a rounding error. The planner adds calendar months
 * properly and uses {@link #approximateDays()} only where an ordering is wanted rather than a
 * date.</p>
 */
public record AllowedTime(int value, Unit unit) {

    public enum Unit { DAYS, MONTHS }

    /** Thirty-day months, for sorting and for coarse comparison. Never for computing a date. */
    public int approximateDays() {
        return unit == Unit.MONTHS ? value * 30 : value;
    }

    /** @return null when {@code word} is neither days nor months */
    static Unit unitOf(String word) {
        if (word == null) {
            return null;
        }
        String lower = word.toLowerCase(Locale.ROOT);
        if (lower.startsWith("day")) {
            return Unit.DAYS;
        }
        return lower.startsWith("month") ? Unit.MONTHS : null;
    }

    /** @return null when either part is missing, so a half-read duration is never asserted */
    static AllowedTime of(String number, String word) {
        Unit unit = unitOf(word);
        if (unit == null || number == null || number.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(number.strip());
            return value <= 0 ? null : new AllowedTime(value, unit);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

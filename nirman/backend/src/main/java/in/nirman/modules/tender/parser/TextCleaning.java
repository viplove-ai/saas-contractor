package in.nirman.modules.tender.parser;

import java.math.BigDecimal;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The small string and number helpers every extraction step leans on.
 *
 * <p>Ported from the Python implementation's {@code cleaning.py}, and deliberately kept as
 * literal a translation as the two languages allow. These functions decide whether a field
 * comes out as {@code null} or as a value, so a well-meaning "improvement" here moves
 * extraction results on documents nobody re-checked.</p>
 */
public final class TextCleaning {

    /** Placeholders a form was filled with when there was nothing to fill it with. */
    private static final Set<String> NULL_WORDS =
            Set.of("", "nan", "none", "null", "na", "n/a", "-", "--");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern CURRENCY_WORDS = Pattern.compile("(?i)(inr|rs\\.?|₹)");
    private static final Pattern NOT_NUMERIC = Pattern.compile("[^0-9.\\-]");

    private TextCleaning() {
    }

    public static boolean isBlank(String value) {
        return value == null || NULL_WORDS.contains(value.strip().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Collapses internal whitespace and trims, or returns {@code null} when the text says
     * nothing. PDF text arrives full of line breaks mid-sentence, so this is what makes a
     * captured group comparable to what a person would have typed.
     */
    public static String compact(String value) {
        if (isBlank(value)) {
            return null;
        }
        return WHITESPACE.matcher(value).replaceAll(" ").strip();
    }

    /**
     * Reads a rupee figure the way a tender prints it: {@code Rs. 42,26,546.00}, {@code ₹1,234},
     * or {@code (500)} for a negative.
     *
     * @return {@code null} when the text holds no number at all, so an absent figure stays
     *         absent rather than becoming a confident zero
     */
    public static BigDecimal parseCurrency(String value) {
        if (isBlank(value)) {
            return null;
        }
        String text = value.strip();
        boolean negative = text.startsWith("(") && text.endsWith(")");
        text = CURRENCY_WORDS.matcher(text).replaceAll("");
        text = text.replace(",", "").replace(" ", "");
        text = strip(text, "()");
        text = NOT_NUMERIC.matcher(text).replaceAll("");
        if (text.isEmpty() || ".".equals(text) || "-".equals(text)) {
            return null;
        }
        BigDecimal number;
        try {
            number = new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
        return negative ? number.abs().negate() : number;
    }

    /**
     * Python's {@code str.strip(chars)}, which Java has no equivalent of: removes any leading
     * and trailing character that appears in {@code chars}, rather than one fixed suffix.
     *
     * <p>The NIT number depends on it — {@code "23/EE/ACD/CPWD/Almora/2026-27 ."} has to lose
     * the trailing space and full stop but keep the internal hyphen.</p>
     */
    public static String strip(String value, String chars) {
        if (value == null) {
            return null;
        }
        int start = 0;
        int end = value.length();
        while (start < end && chars.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && chars.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(start, end);
    }

    /** Python's {@code str.rstrip(chars)}: trailing characters only. */
    public static String stripTrailing(String value, String chars) {
        if (value == null) {
            return null;
        }
        int end = value.length();
        while (end > 0 && chars.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(0, end);
    }

    /** True when the two figures agree to within a rupee, the tolerance the totals use. */
    public static boolean withinOneRupee(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return false;
        }
        return left.subtract(right).abs().compareTo(BigDecimal.ONE) <= 0;
    }
}

package in.nirman.modules.tender.parser;

import java.math.BigDecimal;

/**
 * One priced row lifted from a Schedule of Quantities.
 *
 * <p>{@code amount} is what the document printed, kept as printed. It is not always equal to
 * {@code quantity × rate} — tenders round per line — and the difference is worth preserving
 * because it is how the preview tells the user a row was read correctly.</p>
 *
 * @param synthetic true for a reconciliation placeholder rather than real work; see
 *                  {@link BoqReconciler}
 */
public record BoqLine(
        String itemNo,
        String description,
        BigDecimal quantity,
        String unit,
        BigDecimal rate,
        BigDecimal amount,
        String workPart,
        boolean synthetic) {

    public static final String CIVIL = "Civil Works";
    public static final String ELECTRICAL = "E&M Works";

    public BoqLine(String itemNo, String description, BigDecimal quantity, String unit,
                   BigDecimal rate, BigDecimal amount, String workPart) {
        this(itemNo, description, quantity, unit, rate, amount, workPart, false);
    }

    /** What the row is worth on the printed page, falling back to quantity × rate. */
    public BigDecimal effectiveAmount() {
        if (amount != null && amount.signum() != 0) {
            return amount;
        }
        return derivedAmount();
    }

    /** What the row will be worth once stored, where amount is always derived. */
    public BigDecimal derivedAmount() {
        if (quantity == null || rate == null) {
            return BigDecimal.ZERO;
        }
        return quantity.multiply(rate).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}

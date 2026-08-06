package in.nirman.modules.tender.parser;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Closes the gap between what the schedule was read to contain and what the tender says it is
 * worth.
 *
 * <p>Extraction is not lossless: a row whose unit falls outside the recognised vocabulary, or
 * whose layout defeated the line reader, is simply not there. Left alone, that turns into a
 * project whose BOQ quietly totals less than the contract it was won under, and every
 * percentage computed from it afterwards is wrong by an amount nobody can see.</p>
 *
 * <p>So the shortfall is made explicit as a placeholder line. It is marked
 * {@link BoqLine#synthetic()}, and {@code boq_items.is_synthetic} carries that into the
 * database, where the BOQ service already refuses to let labour, material or cash be charged
 * against it. It is a stated ignorance, not work.</p>
 */
public final class BoqReconciler {

    /**
     * A gap smaller than this is rounding, not a missing row. Proportional rather than fixed
     * because a rupee matters on a five-lakh tender and does not on a twenty-crore one.
     */
    private static final BigDecimal RELATIVE_THRESHOLD = new BigDecimal("0.001");

    private BoqReconciler() {
    }

    /**
     * @param componentTotals the stated per-work-part totals, when the notice prices civil and
     *                        electrical separately. Reconciling per part is strictly better
     *                        than one project-wide figure, because it says <i>where</i> the
     *                        reading fell short.
     * @param statedTotal     used only when no component total is known
     * @return the input lines followed by any placeholder needed to reach the stated totals
     */
    public static List<BoqLine> reconcile(List<BoqLine> items, BigDecimal statedTotal,
                                          Map<String, BigDecimal> componentTotals) {
        List<BoqLine> reconciled = new ArrayList<>(items);

        Map<String, BigDecimal> usable = new LinkedHashMap<>();
        if (componentTotals != null) {
            componentTotals.forEach((part, total) -> {
                if (total != null && total.signum() > 0) {
                    usable.put(part, total);
                }
            });
        }

        if (!usable.isEmpty()) {
            Map<String, BigDecimal> extracted = new LinkedHashMap<>();
            for (BoqLine item : items) {
                extracted.merge(BoqClassifier.workPart(item), item.derivedAmount(), BigDecimal::add);
            }
            usable.forEach((part, stated) -> {
                BigDecimal gap = stated.subtract(extracted.getOrDefault(part, BigDecimal.ZERO));
                if (gap.compareTo(threshold(stated)) > 0) {
                    reconciled.add(placeholder("UNALLOCATED-" + part, part, gap));
                }
            });
            return reconciled;
        }

        if (statedTotal == null || statedTotal.signum() <= 0) {
            return reconciled;
        }
        BigDecimal extracted = items.stream()
                .map(BoqLine::derivedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal gap = statedTotal.subtract(extracted);
        if (gap.compareTo(threshold(statedTotal)) > 0) {
            reconciled.add(placeholder("UNALLOCATED", null, gap));
        }
        return reconciled;
    }

    private static BigDecimal threshold(BigDecimal stated) {
        return stated.multiply(RELATIVE_THRESHOLD).max(BigDecimal.ONE);
    }

    private static BoqLine placeholder(String itemNo, String workPart, BigDecimal gap) {
        String scope = workPart == null ? "BOQ" : workPart;
        return new BoqLine(
                itemNo,
                "Stated " + scope + " total not represented by extracted priced rows",
                BigDecimal.ONE,
                "Lot",
                gap,
                gap,
                workPart,
                true);
    }
}

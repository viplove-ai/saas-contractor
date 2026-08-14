package in.nirman.modules.planning.service;

import in.nirman.modules.planning.engine.PlanInput;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Works out what kind of job this is, so nobody has to be asked.
 *
 * <p>The tender already says. A road's schedule is full of pavement and earthwork, a water supply
 * scheme is mostly pipe, an E&amp;M package has no civil work in it at all — and the NIT importer
 * has already sorted every line into those categories. Asking the user to pick from a list of
 * seven when the document in front of them answers it is a question with a right answer, and a
 * screen that asks one of those has failed.</p>
 *
 * <p>Decided by <b>value</b> rather than by line count, because a tender's character is where its
 * money is: fifty small electrical lines beside one large RCC package is a building, not an
 * electrical job. The answer is still shown and still changeable — the classifier is good and a
 * government notice is not a structured document, and a plan built on a wrong guess about what
 * kind of job this is would be wrong in a way no individual number reveals.</p>
 */
final class WorkTypeDetector {

    /** A category has to hold this much of the contract before it names the whole job. */
    private static final BigDecimal DOMINANT_SHARE = new BigDecimal("0.55");

    /** Below this the two schedules are both real work and the job is composite. */
    private static final BigDecimal COMPOSITE_SHARE = new BigDecimal("0.15");

    private static final Map<String, String> PROFILE_BY_CATEGORY = Map.of(
            "External Development", "ROAD",
            "Plumbing & Sanitary", "WATER_SANITARY",
            "Electrical", "ELECTRICAL_EM",
            "HVAC & Mechanical", "ELECTRICAL_EM",
            "Firefighting & Fire Alarm", "ELECTRICAL_EM",
            "IT, CCTV & Communications", "ELECTRICAL_EM",
            "Solar & Renewable Energy", "ELECTRICAL_EM");

    private WorkTypeDetector() {
    }

    /**
     * @return the profile code the schedule points at, never null — {@code BUILDING_NEW} is the
     *         fallback because it is the commonest tender and the one whose defaults are safest
     *         when the reading is unclear
     */
    static String detect(List<PlanInput.WorkItem> items, String projectName) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal electrical = BigDecimal.ZERO;
        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();

        for (PlanInput.WorkItem item : items) {
            BigDecimal amount = item.amount() == null ? BigDecimal.ZERO : item.amount();
            if (amount.signum() <= 0 || item.synthetic()) {
                continue;
            }
            total = total.add(amount);
            byCategory.merge(String.valueOf(item.category()), amount, BigDecimal::add);
            if ("E&M Works".equals(item.workPart())) {
                electrical = electrical.add(amount);
            }
        }
        if (total.signum() == 0) {
            return fromName(projectName);
        }

        // A single trade carrying most of the money names the job.
        for (Map.Entry<String, BigDecimal> entry : byCategory.entrySet()) {
            String profile = PROFILE_BY_CATEGORY.get(entry.getKey());
            if (profile != null && share(entry.getValue(), total).compareTo(DOMINANT_SHARE) >= 0) {
                return profile;
            }
        }
        // Otherwise, two schedules both worth taking seriously make it composite.
        BigDecimal electricalShare = share(electrical, total);
        if (electricalShare.compareTo(COMPOSITE_SHARE) >= 0
                && electricalShare.compareTo(DOMINANT_SHARE) < 0) {
            return "COMPOSITE";
        }
        String fromName = fromName(projectName);
        return fromName == null ? "BUILDING_NEW" : fromName;
    }

    /**
     * The work name, as a tie-breaker. CPWD names a repair tender as one — "SH: Repair and
     * renovation of ..." — and a maintenance job is phased completely differently from a new
     * building, so it is worth reading even though it is only words.
     */
    private static String fromName(String projectName) {
        if (projectName == null) {
            return "BUILDING_NEW";
        }
        String name = projectName.toLowerCase(Locale.ROOT);
        if (name.contains("repair") || name.contains("renovation") || name.contains("maintenance")
                || name.contains("restoration") || name.contains("replacement")) {
            return "BUILDING_MAINT";
        }
        if (name.contains("road") || name.contains("pavement") || name.contains("path")) {
            return "ROAD";
        }
        if (name.contains("water supply") || name.contains("sewer") || name.contains("drainage")) {
            return "WATER_SANITARY";
        }
        if (name.contains("horticulture") || name.contains("landscap") || name.contains("plantation")) {
            return "HORTICULTURE";
        }
        return "BUILDING_NEW";
    }

    private static BigDecimal share(BigDecimal part, BigDecimal total) {
        return part.divide(total, 4, java.math.RoundingMode.HALF_UP);
    }
}

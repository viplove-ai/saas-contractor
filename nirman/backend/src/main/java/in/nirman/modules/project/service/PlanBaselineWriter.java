package in.nirman.modules.project.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The one thing a plan is allowed to write into the project's own records.
 *
 * <p>{@code boq_items} has carried {@code planned_start_date} and {@code planned_completion_date}
 * since {@code V1}. They are mapped on the entity and nothing has ever written them: V1
 * provisioned a planner and the planner was never built. Baselining a plan fills them, and that
 * is the whole of its write-forward.</p>
 *
 * <p>It is legitimate because those columns are the project's own statement of intent, not a
 * ledger. Everything else stays forbidden — a planned quantity never reaches the measurement
 * book, a planned requirement is never a stock transaction, and a planned cost is never an
 * expense. The interface exists so planning asks rather than reaches: {@code boq_items} belongs
 * to this module and its invariants stay owned in one place.</p>
 */
public interface PlanBaselineWriter {

    /** @param workPart null matches every part, for a non-composite contract */
    record PlannedCategory(String category, String workPart, LocalDate start, LocalDate end) {
    }

    /**
     * Stamps the planned window onto every live BOQ line of each category.
     *
     * @return how many lines were dated
     */
    int applyPlannedDates(UUID projectId, List<PlannedCategory> categories);
}

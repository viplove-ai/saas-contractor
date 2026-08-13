package in.nirman.modules.project.service;

import in.nirman.modules.project.domain.BoqItem;
import in.nirman.modules.project.repository.BoqItemRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * {@link PlanBaselineWriter}.
 *
 * <p>Carries no permission check of its own: the caller has already passed
 * {@code planning:baseline}, which is the act this is part of. Gating it again would mean the
 * one user allowed to freeze a plan could not finish freezing it.</p>
 */
@Service
@Transactional
public class PlanBaselineWriterService implements PlanBaselineWriter {

    private final BoqItemRepository items;
    private final CurrentUserProvider currentUser;

    public PlanBaselineWriterService(BoqItemRepository items, CurrentUserProvider currentUser) {
        this.items = items;
        this.currentUser = currentUser;
    }

    @Override
    public int applyPlannedDates(UUID projectId, List<PlannedCategory> categories) {
        if (categories == null || categories.isEmpty()) {
            return 0;
        }
        List<BoqItem> lines = items.search(currentUser.currentOrgId(), projectId, null, null);
        int dated = 0;
        for (BoqItem line : lines) {
            // A reconciliation placeholder describes no work, so it gets no dates. Nothing can
            // be charged to one and nothing can be started on one.
            if (line.isSynthetic()) {
                continue;
            }
            PlannedCategory match = categories.stream()
                    .filter(category -> category.category().equals(line.getCategory()))
                    .filter(category -> category.workPart() == null
                            || category.workPart().equals(line.getWorkPart()))
                    .findFirst().orElse(null);
            if (match == null) {
                continue;
            }
            line.setPlannedStartDate(match.start());
            line.setPlannedCompletionDate(match.end());
            dated++;
        }
        return dated;
    }
}

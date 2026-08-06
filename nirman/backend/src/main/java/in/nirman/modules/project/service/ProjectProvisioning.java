package in.nirman.modules.project.service;

import in.nirman.modules.project.api.dto.ProjectDtos.CreateProjectRequest;
import in.nirman.modules.project.api.dto.ProjectDtos.ProjectResponse;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Creating a project together with the schedule of work it was awarded under, in one act.
 *
 * <p>Published for the tender module, which reads a NIT and then needs both written or
 * neither. It cannot write {@code boq_items} itself — that table's invariants live here, in
 * the module that owns it — so the rows come in as plain values and this module builds them.
 * The same reason {@code inventory} asks {@code masterdata} for a conversion instead of
 * reading the conversion table.</p>
 */
public interface ProjectProvisioning {

    /**
     * One line of the awarded schedule.
     *
     * @param quantity  and {@code rate} as the tender printed them. The amount is derived
     *                  from the two rather than passed, because that is the only way
     *                  {@code contract_amount} can be relied on downstream.
     * @param synthetic a reconciliation placeholder for value the reader could not account
     *                  for, not real work. Nothing may be charged against one.
     */
    record ImportedBoqLine(
            String itemNumber,
            String description,
            UUID unitId,
            BigDecimal quantity,
            BigDecimal rate,
            String workPart,
            String category,
            boolean synthetic,
            int sortOrder) {
    }

    record ProvisionRequest(CreateProjectRequest project, List<ImportedBoqLine> lines, String source) {
    }

    record ProvisionResult(ProjectResponse project, int lineCount, BigDecimal boqValue) {
    }

    /**
     * @throws in.nirman.common.BusinessException if the project code is taken, or two lines
     *         claim the same item number — the schedule would be ambiguous about which line
     *         work was measured against
     */
    ProvisionResult createWithBoq(ProvisionRequest request);
}

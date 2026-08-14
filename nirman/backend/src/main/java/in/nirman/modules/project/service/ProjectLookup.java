package in.nirman.modules.project.service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * The project module's public read API for the handful of contract facts other modules plan on.
 *
 * <p>Deliberately tiny. Planning needs the bid and the dates to build a programme, and giving it
 * the whole project entity would let it reach for anything.</p>
 */
public interface ProjectLookup {

    /** @param quotedPercent above (+) or below (-) the estimate; null when nobody has said */
    record ProjectContract(
            UUID id,
            String name,
            BigDecimal contractValue,
            BigDecimal quotedPercent,
            java.time.LocalDate startDate,
            java.time.LocalDate expectedCompletionDate) {
    }

    Optional<ProjectContract> contract(UUID projectId);
}

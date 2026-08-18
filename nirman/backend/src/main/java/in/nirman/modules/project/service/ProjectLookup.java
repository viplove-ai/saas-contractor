package in.nirman.modules.project.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
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

    /**
     * The dates and figures a deposit's release schedule hangs off.
     *
     * <p>Shaped for the treasury register rather than exposing the entity, in the manner of
     * every lookup here. {@code workNature} and {@code status} cross as strings on purpose:
     * the caller has its own vocabulary for both, and passing an entity's enum over the fence
     * would make the project module's internals part of another module's compile.</p>
     *
     * @param quotedCost   what the work pays at the rate bid: {@code contractValue}, which is
     *                      the notice's own figure, moved by {@code quotedPercent}
     * @param workStartDate the day work began. It stands in for the allotment letter V41
     *                      removed: the contract had been awarded by the day work started, and
     *                      this is a date the office actually enters.
     * @param completionOn the day a guarantee's clock starts — the day work actually finished
     *                     where that is recorded, and the expected completion until then.
     */
    record ContractCalendar(
            UUID id,
            String code,
            String name,
            String status,
            BigDecimal quotedCost,
            BigDecimal contractValue,
            BigDecimal quotedPercent,
            String workNature,
            LocalDate workStartDate,
            LocalDate actualCompletionDate,
            LocalDate completionOn,
            Integer defectLiabilityMonths) {
    }

    Optional<ContractCalendar> calendar(UUID projectId);

    /**
     * Calendars for the given projects, in one query. The register lists every deposit the
     * company holds and needs each one's contract beside it; asking project by project would
     * be a query per row.
     */
    List<ContractCalendar> calendars(Collection<UUID> projectIds);

    /** Every live project in the organisation, for the register's own project picker. */
    List<ContractCalendar> allCalendars();
}

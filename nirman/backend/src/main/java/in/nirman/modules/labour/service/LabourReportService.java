package in.nirman.modules.labour.service;

import in.nirman.modules.labour.api.dto.LabourReportDtos.AttendanceRegisterReport;
import in.nirman.modules.labour.api.dto.LabourReportDtos.AttendanceRegisterReport.RegisterRow;
import in.nirman.modules.labour.api.dto.LabourReportDtos.WageSummaryReport;
import in.nirman.modules.labour.api.dto.LabourReportDtos.WageSummaryReport.WageRow;
import in.nirman.modules.labour.api.dto.LabourReportDtos.WageSummaryReport.WageTotals;
import in.nirman.modules.labour.domain.AttendanceRecord;
import in.nirman.modules.labour.domain.AttendanceStatus;
import in.nirman.modules.labour.domain.Worker;
import in.nirman.modules.labour.domain.WorkerLedgerEntry;
import in.nirman.modules.labour.domain.WorkflowStatus;
import in.nirman.modules.labour.repository.AttendanceRecordRepository;
import in.nirman.modules.labour.repository.WorkerLedgerEntryRepository;
import in.nirman.modules.labour.repository.WorkerRepository;
import in.nirman.modules.project.service.SiteLookup;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The two labour reports, as read-only projections over attendance and the wage ledger.
 *
 * <p>They are split along the permission matrix rather than by convenience. The register
 * carries days and hours and needs {@code report:operational}, which every field role holds;
 * the wage summary carries money and needs {@code report:financial}, which only the
 * administrator and the accountant hold. Putting wages on the register would have quietly
 * handed every supervisor the site payroll.</p>
 *
 * <p>Both count only rows that were actually signed off — verified or locked. A draft is
 * what somebody typed; it is not yet a fact about the month, and a register that includes
 * it will disagree with the one printed an hour later.</p>
 */
@Service
@Transactional(readOnly = true)
public class LabourReportService {

    private final AttendanceRecordRepository records;
    private final WorkerRepository workers;
    private final WorkerLedgerEntryRepository ledgerEntries;
    private final SiteLookup sites;
    private final SiteAccessGuard siteAccessGuard;
    private final CurrentUserProvider currentUser;

    public LabourReportService(AttendanceRecordRepository records, WorkerRepository workers,
                               WorkerLedgerEntryRepository ledgerEntries, SiteLookup sites,
                               SiteAccessGuard siteAccessGuard, CurrentUserProvider currentUser) {
        this.records = records;
        this.workers = workers;
        this.ledgerEntries = ledgerEntries;
        this.sites = sites;
        this.siteAccessGuard = siteAccessGuard;
        this.currentUser = currentUser;
    }

    @PreAuthorize("hasAuthority('report:operational')")
    public AttendanceRegisterReport attendanceRegister(UUID siteId, LocalDate from, LocalDate to) {
        siteAccessGuard.assertCanAccess(siteId);
        SiteLookup.SiteInfo site = sites.require(siteId);
        List<LocalDate> days = from.datesUntil(to.plusDays(1)).toList();

        Map<UUID, List<AttendanceRecord>> byWorker = signedOff(siteId, from, to);
        Map<UUID, Worker> workersById = workersFor(byWorker.keySet());

        List<RegisterRow> rows = byWorker.entrySet().stream()
                .map(e -> {
                    Worker worker = workersById.get(e.getKey());
                    Map<LocalDate, String> marks = new LinkedHashMap<>();
                    e.getValue().forEach(r -> marks.put(r.getAttendanceDate(), mark(r.getStatus())));
                    return new RegisterRow(e.getKey(),
                            worker == null ? null : worker.getWorkerCode(),
                            worker == null ? null : worker.getFullName(),
                            marks,
                            count(e.getValue(), AttendanceStatus.PRESENT),
                            count(e.getValue(), AttendanceStatus.HALF_DAY),
                            count(e.getValue(), AttendanceStatus.ABSENT),
                            count(e.getValue(), AttendanceStatus.LEAVE),
                            sum(e.getValue(), AttendanceRecord::getRegularHours),
                            sum(e.getValue(), AttendanceRecord::getOvertimeHours));
                })
                .sorted(Comparator.comparing(RegisterRow::workerCode,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        return new AttendanceRegisterReport(siteId, site.name(), from, to, days, rows);
    }

    @PreAuthorize("hasAuthority('report:financial')")
    public WageSummaryReport wageSummary(UUID siteId, LocalDate from, LocalDate to) {
        siteAccessGuard.assertCanAccess(siteId);
        SiteLookup.SiteInfo site = sites.require(siteId);

        Map<UUID, List<AttendanceRecord>> byWorker = signedOff(siteId, from, to);
        Map<UUID, Worker> workersById = workersFor(byWorker.keySet());
        Map<UUID, BigDecimal> advancesDrawn = advancesInPeriod(siteId, from, to);

        List<WageRow> rows = byWorker.entrySet().stream()
                .map(e -> {
                    Worker worker = workersById.get(e.getKey());
                    BigDecimal wage = sum(e.getValue(), AttendanceRecord::getComputedWageAmount);
                    BigDecimal overtime = sum(e.getValue(), AttendanceRecord::getComputedOtAmount);
                    BigDecimal earned = wage.add(overtime);
                    BigDecimal advance = advancesDrawn.getOrDefault(e.getKey(), BigDecimal.ZERO);
                    return new WageRow(e.getKey(),
                            worker == null ? null : worker.getWorkerCode(),
                            worker == null ? null : worker.getFullName(),
                            count(e.getValue(), AttendanceStatus.PRESENT),
                            count(e.getValue(), AttendanceStatus.HALF_DAY),
                            sum(e.getValue(), AttendanceRecord::getRegularHours),
                            sum(e.getValue(), AttendanceRecord::getOvertimeHours),
                            wage, overtime, earned, advance, earned.subtract(advance));
                })
                .sorted(Comparator.comparing(WageRow::workerCode,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        WageTotals totals = new WageTotals(rows.size(),
                total(rows, WageRow::regularHours),
                total(rows, WageRow::overtimeHours),
                total(rows, WageRow::totalEarned),
                total(rows, WageRow::advanceDrawn),
                total(rows, WageRow::netPayable));

        return new WageSummaryReport(siteId, site.name(), from, to, rows, totals);
    }

    // ------------------------------------------------------------------ internals

    /** Verified and locked rows only — a draft is not yet a fact about the month. */
    private Map<UUID, List<AttendanceRecord>> signedOff(UUID siteId, LocalDate from, LocalDate to) {
        return records.findForPeriod(siteId, from, to).stream()
                .filter(r -> r.getWorkflowStatus() == WorkflowStatus.VERIFIED
                        || r.getWorkflowStatus() == WorkflowStatus.LOCKED)
                .collect(Collectors.groupingBy(AttendanceRecord::getWorkerId,
                        LinkedHashMap::new, Collectors.toList()));
    }

    private Map<UUID, BigDecimal> advancesInPeriod(UUID siteId, LocalDate from, LocalDate to) {
        Map<UUID, BigDecimal> drawn = new HashMap<>();
        for (WorkerLedgerEntry entry : ledgerEntries.findForSitePeriod(siteId, from, to)) {
            if (entry.getEntryType() == WorkerLedgerEntry.EntryType.ADVANCE) {
                drawn.merge(entry.getWorkerId(), entry.getAmount(), BigDecimal::add);
            }
        }
        return drawn;
    }

    private Map<UUID, Worker> workersFor(java.util.Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return workers.findByIdInAndOrgIdAndDeletedAtIsNull(new ArrayList<>(ids),
                        currentUser.currentOrgId()).stream()
                .collect(Collectors.toMap(Worker::getId, Function.identity()));
    }

    /** The single letters a muster roll has always used. */
    private static String mark(AttendanceStatus status) {
        return switch (status) {
            case PRESENT -> "P";
            case HALF_DAY -> "H";
            case ABSENT -> "A";
            case LEAVE -> "L";
        };
    }

    private static long count(List<AttendanceRecord> rows, AttendanceStatus status) {
        return rows.stream().filter(r -> r.getStatus() == status).count();
    }

    private static BigDecimal sum(List<AttendanceRecord> rows,
                                  Function<AttendanceRecord, BigDecimal> field) {
        return rows.stream()
                .map(field)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static <T> BigDecimal total(List<T> rows, Function<T, BigDecimal> field) {
        return rows.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

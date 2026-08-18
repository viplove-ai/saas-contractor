package in.nirman.modules.treasury.service;

import in.nirman.modules.project.service.ProjectLookup;
import in.nirman.modules.project.service.ProjectLookup.ContractCalendar;
import in.nirman.modules.treasury.api.dto.TreasuryDtos.BankSlice;
import in.nirman.modules.treasury.api.dto.TreasuryDtos.ProjectTreasuryRow;
import in.nirman.modules.treasury.api.dto.TreasuryDtos.ReleaseBucket;
import in.nirman.modules.treasury.api.dto.TreasuryDtos.SecurityResponse;
import in.nirman.modules.treasury.api.dto.TreasuryDtos.TreasuryDashboard;
import in.nirman.modules.treasury.api.dto.TreasuryDtos.TypeSlice;
import in.nirman.modules.treasury.domain.ProjectSecurity;
import in.nirman.modules.treasury.domain.ProjectSecurity.Status;
import in.nirman.modules.treasury.domain.ProjectSecurity.Type;
import in.nirman.modules.treasury.repository.ProjectSecurityRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The company's blocked money in one view: what is out, what comes back when, and what is free
 * to fund the next tender.
 *
 * <p>Every figure is computed per call from the register and stored nowhere, like every other
 * roll-up in this system. A cached treasury total would be a second version of the truth about
 * the one thing an office rings the bank about.</p>
 */
@Service
@Transactional(readOnly = true)
public class TreasuryDashboardService {

    /** Forward window of the release calendar. Long enough to see a guarantee's whole tail. */
    private static final int CALENDAR_MONTHS = 18;

    private static final String CAVEAT =
            "Amounts are what the register says was lodged, not what a rule computes. A deposit "
                    + "not recorded here is invisible to every figure on this screen.";

    private final ProjectSecurityRepository securities;
    private final ProjectLookup projects;
    private final CurrentUserProvider currentUser;

    public TreasuryDashboardService(ProjectSecurityRepository securities, ProjectLookup projects,
                                    CurrentUserProvider currentUser) {
        this.securities = securities;
        this.projects = projects;
        this.currentUser = currentUser;
    }

    @PreAuthorize("hasAuthority('security:read')")
    public TreasuryDashboard build(LocalDate asOf) {
        LocalDate today = asOf == null ? LocalDate.now() : asOf;
        List<ProjectSecurity> rows = securities.findByOrgId(currentUser.currentOrgId());

        // Both ends of a redeployment, so a released EMD can name the tender it went into.
        Set<UUID> projectIds = new LinkedHashSet<>();
        rows.forEach(row -> {
            projectIds.add(row.getProjectId());
            if (row.getRedeployedToProjectId() != null) {
                projectIds.add(row.getRedeployedToProjectId());
            }
        });
        Map<UUID, ContractCalendar> contracts = new LinkedHashMap<>();
        projects.calendars(projectIds).forEach(c -> contracts.put(c.id(), c));

        LocalDate financialYearStart = financialYearStart(today);

        return new TreasuryDashboard(
                today,
                sumHeld(rows, row -> row.getStatus() == Status.LODGED),
                sumHeld(rows, ProjectSecurity::isLodged),
                sumHeld(rows, ProjectSecurity::isRetained),

                sumAmount(rows, row -> row.getStatus() == Status.DUE),
                count(rows, row -> row.getStatus() == Status.DUE),

                sumHeld(rows, row -> row.isReleasableBy(today)),
                count(rows, row -> row.isReleasableBy(today)),
                sumHeld(rows, releasingWithin(today, today.plusDays(30))),
                sumHeld(rows, releasingWithin(today, today.plusDays(90))),
                sumHeld(rows, releasingWithin(today, today.plusDays(365))),

                sumAmount(rows, ProjectSecurity::isFreeToReuse),
                count(rows, ProjectSecurity::isFreeToReuse),

                sumAmount(rows, settledOnOrAfter(Status.RELEASED, financialYearStart)),
                sumAmount(rows, settledOnOrAfter(Status.RELEASED, financialYearStart)
                        .and(row -> row.getRedeployedToProjectId() != null)),
                sumAmount(rows, row -> row.getStatus() == Status.FORFEITED),

                byType(rows),
                byBank(rows),
                releaseCalendar(rows, today),
                byProject(rows, contracts, today),

                detail(rows, contracts, today, row -> row.isReleasableBy(today)
                        || row.needsRenewalBy(today)),
                detail(rows, contracts, today, ProjectSecurity::isFreeToReuse),

                CAVEAT);
    }

    // ------------------------------------------------------------------ slices

    /**
     * One row per kind of deposit, in the order they fall due on a contract, and every kind
     * present even where nothing is held — an absent slice reads as "we have no guarantees"
     * when what it means is "none of them are open just now".
     */
    private List<TypeSlice> byType(List<ProjectSecurity> rows) {
        List<TypeSlice> slices = new ArrayList<>(Type.values().length);
        for (Type type : Type.values()) {
            Predicate<ProjectSecurity> ofType = row -> row.getSecurityType() == type;
            slices.add(new TypeSlice(type,
                    sumHeld(rows, ofType.and(row -> row.getStatus() == Status.LODGED)),
                    count(rows, ofType.and(row -> row.getStatus() == Status.LODGED)),
                    sumAmount(rows, ofType.and(row -> row.getStatus() == Status.DUE))));
        }
        return slices;
    }

    /**
     * Where the money is sitting, biggest first.
     *
     * <p>Retentions are left out: they are with a department rather than a bank, and a slice
     * labelled "not with a bank" next to four branch names invites the reader to treat it as a
     * fifth bank. A bank with no name recorded is grouped honestly rather than dropped —
     * "unnamed" is a gap the office should close, and hiding it hides the gap.</p>
     */
    private List<BankSlice> byBank(List<ProjectSecurity> rows) {
        Map<String, BigDecimal> held = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        rows.stream().filter(ProjectSecurity::isLodged).forEach(row -> {
            String bank = row.getBankName() == null || row.getBankName().isBlank()
                    ? "Not recorded" : row.getBankName().trim();
            held.merge(bank, row.getHeldAmount(), BigDecimal::add);
            counts.merge(bank, 1, Integer::sum);
        });
        return held.entrySet().stream()
                .map(entry -> new BankSlice(entry.getKey(), entry.getValue(),
                        counts.get(entry.getKey())))
                .sorted(Comparator.comparing(BankSlice::held).reversed())
                .toList();
    }

    /**
     * What unlocks when, month by month.
     *
     * <p>Every month in the window appears, including the empty ones, because the gaps are the
     * information: a quarter with nothing coming back is a quarter the next tender's earnest
     * money has to be found from somewhere else. Anything already overdue is folded into the
     * first bucket rather than dropped off the back of the calendar.</p>
     */
    private List<ReleaseBucket> releaseCalendar(List<ProjectSecurity> rows, LocalDate today) {
        YearMonth first = YearMonth.from(today);
        YearMonth last = first.plusMonths(CALENDAR_MONTHS - 1L);

        Map<YearMonth, BigDecimal> lodged = new LinkedHashMap<>();
        Map<YearMonth, BigDecimal> retained = new LinkedHashMap<>();
        Map<YearMonth, Integer> counts = new LinkedHashMap<>();

        for (ProjectSecurity row : rows) {
            if (row.getStatus() != Status.LODGED || row.getExpectedReleaseOn() == null) {
                continue;
            }
            YearMonth month = YearMonth.from(row.getExpectedReleaseOn());
            if (month.isBefore(first)) {
                month = first;
            }
            if (month.isAfter(last)) {
                continue;
            }
            if (row.isLodged()) {
                lodged.merge(month, row.getHeldAmount(), BigDecimal::add);
            } else {
                retained.merge(month, row.getHeldAmount(), BigDecimal::add);
            }
            counts.merge(month, 1, Integer::sum);
        }

        List<ReleaseBucket> buckets = new ArrayList<>(CALENDAR_MONTHS);
        for (int i = 0; i < CALENDAR_MONTHS; i++) {
            YearMonth month = first.plusMonths(i);
            buckets.add(new ReleaseBucket(month.toString(),
                    lodged.getOrDefault(month, BigDecimal.ZERO),
                    retained.getOrDefault(month, BigDecimal.ZERO),
                    counts.getOrDefault(month, 0)));
        }
        return buckets;
    }

    /** One row per contract that has anything recorded against it, most blocked first. */
    private List<ProjectTreasuryRow> byProject(List<ProjectSecurity> rows,
                                               Map<UUID, ContractCalendar> contracts,
                                               LocalDate today) {
        Map<UUID, List<ProjectSecurity>> grouped = new LinkedHashMap<>();
        rows.forEach(row -> grouped
                .computeIfAbsent(row.getProjectId(), key -> new ArrayList<>()).add(row));

        List<ProjectTreasuryRow> result = new ArrayList<>(grouped.size());
        grouped.forEach((projectId, own) -> {
            ContractCalendar contract = contracts.get(projectId);
            ProjectSecurity next = own.stream()
                    .filter(row -> row.getStatus() == Status.LODGED
                            && row.getExpectedReleaseOn() != null)
                    .min(Comparator.comparing(ProjectSecurity::getExpectedReleaseOn))
                    .orElse(null);

            result.add(new ProjectTreasuryRow(
                    projectId,
                    contract == null ? null : contract.code(),
                    contract == null ? null : contract.name(),
                    contract == null ? null : contract.status(),
                    contract == null ? null : contract.contractValue(),
                    heldOf(own, Type.EMD),
                    heldOf(own, Type.PERFORMANCE_GUARANTEE),
                    heldOf(own, Type.ADDITIONAL_PG),
                    heldOf(own, Type.SECURITY_DEPOSIT),
                    sumHeld(own, row -> row.getStatus() == Status.LODGED),
                    sumAmount(own, row -> row.getStatus() == Status.DUE),
                    sumHeld(own, row -> row.isReleasableBy(today)),
                    next == null ? null : next.getExpectedReleaseOn(),
                    next == null ? null : next.getHeldAmount(),
                    count(own, row -> row.isReleasableBy(today) || row.needsRenewalBy(today))));
        });
        result.sort(Comparator.comparing(ProjectTreasuryRow::totalHeld).reversed());
        return result;
    }

    private List<SecurityResponse> detail(List<ProjectSecurity> rows,
                                          Map<UUID, ContractCalendar> contracts, LocalDate today,
                                          Predicate<ProjectSecurity> matching) {
        return rows.stream()
                .filter(matching)
                .sorted(Comparator.comparing(ProjectSecurity::getExpectedReleaseOn,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(row -> ProjectSecurityService.toResponse(row, contracts, today))
                .toList();
    }

    // ------------------------------------------------------------------ arithmetic

    /**
     * The Indian financial year starts on 1 April, which is the year the books actually close
     * on — a treasury figure reported against a calendar year would not reconcile with any
     * other statement the accountant produces.
     */
    static LocalDate financialYearStart(LocalDate day) {
        int year = day.getMonthValue() >= 4 ? day.getYear() : day.getYear() - 1;
        return LocalDate.of(year, 4, 1);
    }

    private static Predicate<ProjectSecurity> releasingWithin(LocalDate from, LocalDate to) {
        return row -> row.getStatus() == Status.LODGED && row.getExpectedReleaseOn() != null
                && !row.getExpectedReleaseOn().isAfter(to)
                && !row.getExpectedReleaseOn().isBefore(from);
    }

    private static Predicate<ProjectSecurity> settledOnOrAfter(Status status, LocalDate from) {
        return row -> row.getStatus() == status && row.getReleasedOn() != null
                && !row.getReleasedOn().isBefore(from);
    }

    private static BigDecimal heldOf(List<ProjectSecurity> rows, Type type) {
        return sumHeld(rows, row -> row.getSecurityType() == type
                && row.getStatus() == Status.LODGED);
    }

    private static BigDecimal sumHeld(List<ProjectSecurity> rows,
                                      Predicate<ProjectSecurity> matching) {
        return rows.stream().filter(matching).map(ProjectSecurity::getHeldAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sumAmount(List<ProjectSecurity> rows,
                                        Predicate<ProjectSecurity> matching) {
        return rows.stream().filter(matching).map(ProjectSecurity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static int count(List<ProjectSecurity> rows, Predicate<ProjectSecurity> matching) {
        return (int) rows.stream().filter(matching).count();
    }
}

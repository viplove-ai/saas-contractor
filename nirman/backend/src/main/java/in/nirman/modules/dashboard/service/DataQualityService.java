package in.nirman.modules.dashboard.service;

import in.nirman.modules.dashboard.api.dto.DashboardDtos.DataQualityDashboard;
import in.nirman.modules.dashboard.api.dto.DashboardDtos.QualityFinding;
import in.nirman.modules.dpr.domain.DailyProgressReport;
import in.nirman.modules.dpr.repository.DailyProgressReportRepository;
import in.nirman.modules.expense.service.ExpenseLookup;
import in.nirman.modules.inventory.service.InventoryLookup;
import in.nirman.modules.labour.service.LabourLookup;
import in.nirman.modules.project.domain.Site;
import in.nirman.modules.project.repository.SiteRepository;
import in.nirman.modules.project.service.BoqLookup;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The data-quality dashboard: what is missing, wrong or unfinished in the records.
 *
 * <h2>Why this screen exists at all</h2>
 *
 * <p>Every other figure in the system is only as good as what the field entered. A labour cost
 * over a month with eleven unmarked days is not a labour cost, and a variance report over
 * material issued against no work item is a variance nobody can act on. Those failures are
 * silent — the numbers still render, they are just wrong — so something has to go looking for
 * them and say so out loud.</p>
 *
 * <h2>Two rules about how findings are written</h2>
 *
 * <p><b>Every finding carries what to do about it.</b> A dashboard that only counts problems is
 * one that gets looked at twice and then ignored. "Eleven days unmarked" is a complaint; the
 * eleven dates, and the sentence saying who can fix them, is a task.</p>
 *
 * <p><b>Two severities, not five.</b> ACT means somebody's money or contract is affected now;
 * WATCH means it will be if it continues. A finer scale only produces arguments about whether
 * something is a three or a four, and nobody ever acts on a three.</p>
 */
@Service
@Transactional(readOnly = true)
public class DataQualityService {

    private static final String ACT = "ACT";
    private static final String WATCH = "WATCH";

    /** How long an unverified attendance row is normal before it is a problem. */
    private static final int VERIFICATION_GRACE_DAYS = 3;

    private static final String CAVEAT = """
            These are gaps in what was entered, not accounting errors. Every one of them makes \
            some figure elsewhere in the system quietly wrong rather than visibly broken, which \
            is why they are listed here instead of being left for somebody to notice.""";

    private final SiteRepository sites;
    private final DailyProgressReportRepository reports;
    private final LabourLookup labour;
    private final InventoryLookup inventory;
    private final ExpenseLookup expenses;
    private final BoqLookup boqItems;
    private final SiteAccessGuard siteAccessGuard;
    private final CurrentUserProvider currentUser;

    public DataQualityService(SiteRepository sites, DailyProgressReportRepository reports,
                              LabourLookup labour, InventoryLookup inventory,
                              ExpenseLookup expenses, BoqLookup boqItems,
                              SiteAccessGuard siteAccessGuard, CurrentUserProvider currentUser) {
        this.sites = sites;
        this.reports = reports;
        this.labour = labour;
        this.inventory = inventory;
        this.expenses = expenses;
        this.boqItems = boqItems;
        this.siteAccessGuard = siteAccessGuard;
        this.currentUser = currentUser;
    }

    /**
     * @param siteId null asks about every site the caller can see. {@code dashboard:dataquality}
     *               is A for an engineer, so a named site is guarded and an unnamed one narrows
     *               to their postings — no filter means "mine", never "everyone's" (docs/04).
     */
    @PreAuthorize("hasAuthority('dashboard:dataquality')")
    public DataQualityDashboard dataQuality(UUID siteId, LocalDate from, LocalDate to) {
        DashboardService.assertSaneRange(from, to);
        if (siteId != null) {
            siteAccessGuard.assertCanAccess(siteId);
        }
        List<Site> scope = scopeSites(siteId);

        List<QualityFinding> findings = new ArrayList<>();
        findings.add(unmarkedDays(scope, from, to));
        findings.add(unverifiedAttendance(scope, from, to));
        findings.add(missingReports(scope, from, to));
        findings.add(unverifiedReports(siteId));
        findings.add(consumptionWithoutWorkItem(scope, from, to));
        findings.add(expensesWithoutEvidence(scope, from, to));
        findings.add(overClaimedWork(scope));
        findings.add(driftedStockValue(scope, from, to));

        List<QualityFinding> real = findings.stream().filter(f -> f.count() > 0).toList();
        return new DataQualityDashboard(from, to, siteId, scopeName(siteId, scope),
                (int) real.stream().filter(f -> ACT.equals(f.severity())).count(),
                (int) real.stream().filter(f -> WATCH.equals(f.severity())).count(),
                real, CAVEAT);
    }

    // ------------------------------------------------------------------ the findings

    /**
     * The one that matters most. A month with unmarked days has a labour cost that is simply
     * short, and nothing downstream can tell the difference between "nobody worked" and "nobody
     * typed it in".
     */
    private QualityFinding unmarkedDays(List<Site> scope, LocalDate from, LocalDate to) {
        LocalDate until = to.isAfter(LocalDate.now()) ? LocalDate.now() : to;
        List<String> examples = new ArrayList<>();
        int count = 0;
        for (Site site : scope) {
            // Nothing before the site opened is a gap; a site that started in February owes
            // nobody a muster roll for January.
            LocalDate start = site.getStartDate() == null || site.getStartDate().isBefore(from)
                    ? from : site.getStartDate();
            if (start.isAfter(until)) {
                continue;
            }
            for (LocalDate day : labour.daysWithoutAttendance(site.getId(), start, until)) {
                count++;
                if (examples.size() < DashboardService.MAX_EXAMPLES) {
                    examples.add(site.getCode() + " · " + day);
                }
            }
        }
        return new QualityFinding("attendance.unmarked", "Days with no attendance marked", count,
                ACT,
                "No muster roll was saved for these site-days. The labour cost for the period is "
                        + "short by whatever was worked on them.",
                "Mark the attendance for each day, or record why the site did not work.",
                examples);
    }

    /**
     * Unverified attendance is unfrozen money. The wage is pinned at verification, so a row left
     * sitting is a cost figure that can still move — and a month cannot be closed over it
     * without closing over a wrong number.
     */
    private QualityFinding unverifiedAttendance(List<Site> scope, LocalDate from, LocalDate to) {
        LocalDate stale = LocalDate.now().minusDays(VERIFICATION_GRACE_DAYS);
        LocalDate until = to.isBefore(stale) ? to : stale;
        List<String> examples = new ArrayList<>();
        int count = 0;
        for (Site site : scope) {
            if (until.isBefore(from)) {
                continue;
            }
            int pending = labour.period(site.getId(), from, until).pendingVerification();
            if (pending > 0) {
                count += pending;
                if (examples.size() < DashboardService.MAX_EXAMPLES) {
                    examples.add(site.getCode() + " · " + pending + " rows");
                }
            }
        }
        return new QualityFinding("attendance.unverified",
                "Attendance waiting more than " + VERIFICATION_GRACE_DAYS + " days for verification",
                count, ACT,
                "The wage is frozen at verification, so nothing here has been posted to a "
                        + "worker's ledger and the labour cost on it can still change.",
                "The site engineer verifies these from the verification queue.", examples);
    }

    /** A missing DPR is a day the department has no record of, whatever the system knows. */
    private QualityFinding missingReports(List<Site> scope, LocalDate from, LocalDate to) {
        LocalDate until = to.isBefore(LocalDate.now()) ? to : LocalDate.now().minusDays(1);
        List<String> examples = new ArrayList<>();
        int count = 0;
        for (Site site : scope) {
            LocalDate start = site.getStartDate() == null || site.getStartDate().isBefore(from)
                    ? from : site.getStartDate();
            if (start.isAfter(until)) {
                continue;
            }
            List<LocalDate> covered = reports.findForPeriod(orgId(), site.getId(), start, until)
                    .stream().map(DailyProgressReport::getReportDate).toList();
            for (LocalDate day = start; !day.isAfter(until); day = day.plusDays(1)) {
                if (!covered.contains(day)) {
                    count++;
                    if (examples.size() < DashboardService.MAX_EXAMPLES) {
                        examples.add(site.getCode() + " · " + day);
                    }
                }
            }
        }
        return new QualityFinding("dpr.missing", "Days with no progress report", count, WATCH,
                "No daily report was written for these site-days.",
                "Write the report from the DPR wizard — the figures prefill from the day's records.",
                examples);
    }

    private QualityFinding unverifiedReports(UUID siteId) {
        int count = (int) reports.countAwaitingVerification(orgId(), siteId);
        return new QualityFinding("dpr.unverified", "Reports waiting for the engineer's signature",
                count, WATCH,
                "Nothing on an unverified report has been claimed against the contract: measured "
                        + "quantities reach the measurement book only when it is signed.",
                "Verify or return them from the DPR list.", List.of());
    }

    /**
     * Material issued against no work item is consumption nobody can attribute. It is legitimate
     * for a handful of nails and a problem when it is half the cement, which is why this is a
     * count to watch rather than an error to block.
     */
    private QualityFinding consumptionWithoutWorkItem(List<Site> scope, LocalDate from,
                                                      LocalDate to) {
        List<String> examples = new ArrayList<>();
        int count = 0;
        for (Site site : scope) {
            int unattributed = inventory.consumptionWithoutBoqItem(site.getId(), from, to);
            if (unattributed > 0) {
                count += unattributed;
                if (examples.size() < DashboardService.MAX_EXAMPLES) {
                    examples.add(site.getCode() + " · " + unattributed + " issues");
                }
            }
        }
        return new QualityFinding("inventory.unattributed",
                "Material issued against no work item", count, WATCH,
                "This consumption cannot be charged to a BOQ line, so it falls outside every "
                        + "estimated-versus-actual comparison rather than into one.",
                "Name the work item when issuing material. Past issues stay as they are — the "
                        + "ledger is append-only — so the fix is forward-looking.",
                examples);
    }

    private QualityFinding expensesWithoutEvidence(List<Site> scope, LocalDate from, LocalDate to) {
        List<String> examples = new ArrayList<>();
        int count = 0;
        for (Site site : scope) {
            int missing = expenses.missingEvidenceCount(site.getId(), from, to);
            if (missing > 0) {
                count += missing;
                if (examples.size() < DashboardService.MAX_EXAMPLES) {
                    examples.add(site.getCode() + " · " + missing + " bills");
                }
            }
        }
        return new QualityFinding("expense.no-evidence",
                "Expenses above the threshold with no bill and no photograph", count, ACT,
                "These are above the organisation's evidence threshold and carry neither a real "
                        + "bill number nor an attached photograph.",
                "Attach the bill, or record in writing why there is none.", examples);
    }

    /** Over-measurement is ordinary; over-measurement nobody has agreed a variation for is not. */
    private QualityFinding overClaimedWork(List<Site> scope) {
        List<String> examples = new ArrayList<>();
        int count = 0;
        for (Site site : scope) {
            BoqLookup.ProgressSummary progress =
                    boqItems.progress(site.getProjectId(), site.getId());
            for (BoqLookup.ItemProgress item : progress.items()) {
                if (item.overClaimedQuantity().signum() <= 0) {
                    continue;
                }
                count++;
                if (examples.size() < DashboardService.MAX_EXAMPLES) {
                    examples.add("%s · %s over by %s".formatted(site.getCode(), item.itemNumber(),
                            item.overClaimedQuantity().toPlainString()));
                }
            }
        }
        return new QualityFinding("boq.over-claimed",
                "Work items measured beyond their contract quantity", count, WATCH,
                "More has been measured than the contract quantified. That is ordinary on site, "
                        + "and the value of work done is capped at the contract amount until a "
                        + "variation says otherwise — so this work is currently unpriced.",
                "Agree a variation, or correct the measurement with a negative entry.", examples);
    }

    /**
     * The ledger and its balance cache disagreeing is the one finding here that is a bug rather
     * than a gap. It is checked on the dashboard because the alternative is finding out from a
     * stock figure somebody has already acted on.
     */
    private QualityFinding driftedStockValue(List<Site> scope, LocalDate from, LocalDate to) {
        List<String> examples = new ArrayList<>();
        int count = 0;
        for (Site site : scope) {
            InventoryLookup.StockMovement movement = inventory.movement(site.getId(), from, to);
            if (!movement.reconciles()) {
                count++;
                if (examples.size() < DashboardService.MAX_EXAMPLES) {
                    examples.add("%s · residual %s".formatted(site.getCode(),
                            movement.residual().toPlainString()));
                }
            }
        }
        return new QualityFinding("inventory.drift",
                "Stores whose stock value does not reconcile", count, ACT,
                "Opening plus received less consumed does not land on the value the stock "
                        + "balances hold. Either a transfer crossed the edge of the period, or "
                        + "the ledger and its cache have drifted.",
                "Read the movement ledger for the store. The ledger is authoritative; the "
                        + "balance is a cache and can be rebuilt from it.",
                examples);
    }

    // ------------------------------------------------------------------ internals

    /**
     * The sites in scope. An unnamed site narrows to the caller's postings for a site-scoped
     * role, because for them no filter means "mine" — layer 3 of docs/04.
     */
    private List<Site> scopeSites(UUID siteId) {
        if (siteId != null) {
            return sites.findByIdInAndDeletedAtIsNullOrderByCode(List.of(siteId));
        }
        if (currentUser.seesAllSites()) {
            return sites.findByOrgIdAndDeletedAtIsNullOrderByCode(orgId());
        }
        return currentUser.assignedSiteIds().isEmpty() ? List.of()
                : sites.findByIdInAndDeletedAtIsNullOrderByCode(currentUser.assignedSiteIds());
    }

    private String scopeName(UUID siteId, List<Site> scope) {
        if (siteId == null) {
            return scope.size() == 1 ? scope.getFirst().getName() : "All sites";
        }
        return scope.isEmpty() ? "Unknown site" : scope.getFirst().getName();
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }
}

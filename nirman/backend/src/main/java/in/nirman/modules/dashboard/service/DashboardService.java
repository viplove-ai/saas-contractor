package in.nirman.modules.dashboard.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.dashboard.api.dto.DashboardDtos.CashTile;
import in.nirman.modules.dashboard.api.dto.DashboardDtos.CompanyDashboard;
import in.nirman.modules.dashboard.api.dto.DashboardDtos.DailyCost;
import in.nirman.modules.dashboard.api.dto.DashboardDtos.DprTile;
import in.nirman.modules.dashboard.api.dto.DashboardDtos.LabourTile;
import in.nirman.modules.dashboard.api.dto.DashboardDtos.MaterialPosition;
import in.nirman.modules.dashboard.api.dto.DashboardDtos.ProgressTile;
import in.nirman.modules.dashboard.api.dto.DashboardDtos.ProjectRow;
import in.nirman.modules.dashboard.api.dto.DashboardDtos.SiteDashboard;
import in.nirman.modules.dashboard.api.dto.DashboardDtos.WorkItemRow;
import in.nirman.modules.dpr.domain.DailyProgressReport;
import in.nirman.modules.dpr.repository.DailyProgressReportRepository;
import in.nirman.modules.expense.service.ExpenseLookup;
import in.nirman.modules.inventory.service.InventoryLookup;
import in.nirman.modules.labour.service.LabourLookup;
import in.nirman.modules.project.domain.Project;
import in.nirman.modules.project.domain.Site;
import in.nirman.modules.project.repository.ProjectRepository;
import in.nirman.modules.project.repository.SiteRepository;
import in.nirman.modules.project.service.BoqLookup;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The company and site dashboards.
 *
 * <p>The dashboard module owns no tables. Every figure comes from a business module's own read
 * API, which is what stops this class becoming a fourth opinion about what a project cost.</p>
 *
 * <h2>The one thing this class exists to get right</h2>
 *
 * <p>Material is reported as <b>three separate figures that add up</b>: what came into store,
 * what was consumed out of it, and what is standing there now — tied together by an identity
 * the response carries in full, {@code opening + received − consumed = inventory value}, with
 * the residual beside it. That is the Phase 6 exit criterion, and it is also the whole of
 * docs/02's double-counting guard: material received is inventory, and it becomes cost when it
 * is issued. A dashboard that blended them would report a project cost inflated by the value of
 * its own stockyard.</p>
 *
 * <p>The same discipline applies to cash. {@code totalBooked} is what left the books;
 * {@code costIncurred} is what the project cost. They differ by material purchases and wage
 * disbursements, both of which are costed elsewhere, and both figures are on the response
 * because a reader who cannot see the difference will add the wrong one to something.</p>
 */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    /** How many work items a site dashboard lists. The rest are a screen away on /boq. */
    private static final int TOP_WORK_ITEMS = 8;

    /** How many examples a finding carries, so a payload stays readable. */
    static final int MAX_EXAMPLES = 12;

    private static final String COST_CAVEAT = """
            Cost incurred is labour, plus material consumed at the moving average it left store \
            at, plus expenses that are neither a material purchase nor a wage payment. Total \
            booked is what left the books and is not a cost figure: a material purchase becomes \
            inventory and is costed again at issue, and a wage payment settles a wage already \
            costed through verified attendance.""";

    private final ProjectRepository projects;
    private final SiteRepository sites;
    private final DailyProgressReportRepository reports;
    private final LabourLookup labour;
    private final InventoryLookup inventory;
    private final ExpenseLookup expenses;
    private final BoqLookup boqItems;
    private final SiteAccessGuard siteAccessGuard;
    private final CurrentUserProvider currentUser;

    public DashboardService(ProjectRepository projects, SiteRepository sites,
                            DailyProgressReportRepository reports, LabourLookup labour,
                            InventoryLookup inventory, ExpenseLookup expenses, BoqLookup boqItems,
                            SiteAccessGuard siteAccessGuard, CurrentUserProvider currentUser) {
        this.projects = projects;
        this.sites = sites;
        this.reports = reports;
        this.labour = labour;
        this.inventory = inventory;
        this.expenses = expenses;
        this.boqItems = boqItems;
        this.siteAccessGuard = siteAccessGuard;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------------------ company

    /**
     * The whole firm over a period.
     *
     * <p>{@code dashboard:company} is company-wide by the matrix — administrator and accountant
     * only — so there is no site narrowing here. An engineer or supervisor gets the site
     * dashboard instead, which is a different question rather than a smaller version of this
     * one.</p>
     */
    @PreAuthorize("hasAuthority('dashboard:company')")
    public CompanyDashboard company(LocalDate from, LocalDate to) {
        assertSaneRange(from, to);

        LabourLookup.LabourPeriod labourPeriod = labour.period(null, from, to);
        InventoryLookup.StockMovement stock = inventory.movement(null, from, to);
        ExpenseLookup.PeriodSpend spend = expenses.period(null, from, to);

        List<Project> live = projects.search(orgId(), null, "", false, List.of(), Pageable.unpaged())
                .getContent();
        List<ProjectRow> rows = live.stream()
                .map(project -> projectRow(project, from, to))
                .sorted(Comparator.comparing(ProjectRow::projectCode))
                .toList();

        BigDecimal costIncurred = labourPeriod.cost()
                .add(stock.consumedValue())
                .add(spend.costIncurred());

        return new CompanyDashboard(from, to,
                (int) live.stream().filter(p -> p.getStatus() == Project.Status.ACTIVE).count(),
                sites.findByOrgIdAndDeletedAtIsNullOrderByCode(orgId()).size(),
                sum(live, Project::getContractValue), sum(live, Project::getBudgetAmount),
                costIncurred, labourPeriod.cost(), stock.consumedValue(), spend.costIncurred(),
                spend.totalBooked(), spend.payable(),
                materialPosition(stock, spend.materialPurchases()),
                rows, trend(null, from, to), COST_CAVEAT);
    }

    // ------------------------------------------------------------------ site

    @PreAuthorize("hasAuthority('dashboard:site')")
    public SiteDashboard site(UUID siteId, LocalDate from, LocalDate to) {
        assertSaneRange(from, to);
        siteAccessGuard.assertCanAccess(siteId);
        Site site = sites.findByIdAndOrgIdAndDeletedAtIsNull(siteId, orgId())
                .orElseThrow(() -> BusinessException.notFound("Site", siteId));

        LabourLookup.LabourPeriod labourPeriod = labour.period(siteId, from, to);
        InventoryLookup.StockMovement stock = inventory.movement(siteId, from, to);
        ExpenseLookup.PeriodSpend spend = expenses.period(siteId, from, to);
        BoqLookup.ProgressSummary progress = boqItems.progress(site.getProjectId(), siteId);

        List<WorkItemRow> topItems = progress.items().stream()
                // Biggest lines first: a site dashboard has room for eight of them, and the
                // eight that matter are the expensive ones, not the alphabetically early ones.
                .sorted(Comparator.comparing(BoqLookup.ItemProgress::contractAmount).reversed())
                .limit(TOP_WORK_ITEMS)
                .map(DashboardService::toWorkItemRow)
                .toList();

        return new SiteDashboard(siteId, site.getCode(), site.getName(), site.getProjectId(),
                projects.findById(site.getProjectId()).map(Project::getName).orElse(null),
                from, to,
                new LabourTile(labourPeriod.manDays(), labourPeriod.regularHours(),
                        labourPeriod.overtimeHours(), labourPeriod.cost(),
                        labourPeriod.verifiedCost(),
                        labourPeriod.cost().subtract(labourPeriod.verifiedCost()),
                        labourPeriod.pendingVerification(), labourPeriod.daysWithAttendance(),
                        labour.daysWithoutAttendance(siteId, from, to).size()),
                materialPosition(stock, spend.materialPurchases()),
                new CashTile(spend.totalBooked(), spend.costIncurred(), spend.materialPurchases(),
                        spend.labourDisbursements(), spend.paid(), spend.payable(),
                        spend.awaitingApproval()),
                new ProgressTile(progress.contractValue(), progress.valueOfWorkDone(),
                        progress.percentComplete(), progress.itemsTotal(),
                        progress.itemsCompleted(), progress.itemsInProgress(),
                        progress.itemsOverClaimed()),
                dprTile(siteId, from, to),
                trend(siteId, from, to), topItems, COST_CAVEAT);
    }

    // ------------------------------------------------------------------ shared pieces

    /**
     * The three figures and their arithmetic, assembled in one place so the company and site
     * dashboards cannot describe material differently.
     */
    static MaterialPosition materialPosition(InventoryLookup.StockMovement stock,
                                             BigDecimal purchased) {
        return new MaterialPosition(stock.openingValue(), stock.receivedValue(),
                stock.consumedValue(), stock.closingValue(), stock.residual(),
                stock.reconciles(), stock.issuedValue(), stock.wastedValue(), purchased,
                purchased.subtract(stock.receivedValue()),
                stock.reconciles()
                        ? "Opening plus received less consumed lands on the stock value the "
                        + "balances hold, so the three figures are the whole picture. Purchased "
                        + "is from the bills and will differ from received: freight is booked "
                        + "separately, and a bill and its lorry rarely arrive the same day."
                        : "Opening plus received less consumed does not land on the stock value "
                        + "the balances hold. The difference is shown as the residual: either a "
                        + "transfer crossed the edge of this scope, or the ledger and its "
                        + "balance cache have drifted and want looking at.");
    }

    private ProjectRow projectRow(Project project, LocalDate from, LocalDate to) {
        List<Site> projectSites =
                sites.findByOrgIdAndProjectIdAndDeletedAtIsNullOrderByCode(orgId(), project.getId());
        BoqLookup.ProgressSummary progress = boqItems.progress(project.getId(), null);

        // Summed per site rather than asked for by project, because labour, stock and expense
        // are all keyed on the site — the project is a roll-up of them, not a level of its own.
        BigDecimal cost = BigDecimal.ZERO;
        for (Site site : projectSites) {
            cost = cost.add(labour.period(site.getId(), from, to).cost())
                    .add(inventory.movement(site.getId(), from, to).consumedValue())
                    .add(expenses.period(site.getId(), from, to).costIncurred());
        }

        return new ProjectRow(project.getId(), project.getCode(), project.getName(),
                project.getStatus().name(), project.getContractValue(), project.getBudgetAmount(),
                cost, percentOf(cost, project.getBudgetAmount()),
                progress.valueOfWorkDone(), progress.percentComplete(), projectSites.size());
    }

    /**
     * The cost trend, three series rather than one line.
     *
     * <p>A single "cost" line would hide the thing a trend is read for: a week where labour
     * held steady and material consumption tripled is a different story from one where both
     * rose, and only the split tells them apart.</p>
     */
    private List<DailyCost> trend(UUID siteId, LocalDate from, LocalDate to) {
        Map<LocalDate, BigDecimal[]> byDay = new LinkedHashMap<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            byDay.put(day, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        }
        labour.dailyCost(siteId, from, to)
                .forEach(row -> bucket(byDay, row.date())[0] = row.cost());
        inventory.dailyConsumption(siteId, from, to)
                .forEach(row -> bucket(byDay, row.date())[1] = row.consumedValue());
        expenses.dailyCostIncurred(siteId, from, to)
                .forEach(row -> bucket(byDay, row.date())[2] = row.costIncurred());

        return byDay.entrySet().stream()
                .map(entry -> new DailyCost(entry.getKey(), entry.getValue()[0],
                        entry.getValue()[1], entry.getValue()[2]))
                .toList();
    }

    private static BigDecimal[] bucket(Map<LocalDate, BigDecimal[]> byDay, LocalDate date) {
        return byDay.computeIfAbsent(date,
                unused -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
    }

    private DprTile dprTile(UUID siteId, LocalDate from, LocalDate to) {
        List<DailyProgressReport> inRange = reports.findForPeriod(orgId(), siteId, from, to);
        List<LocalDate> covered = inRange.stream().map(DailyProgressReport::getReportDate).toList();

        List<LocalDate> missing = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            // Today's report is not late yet, and tomorrow's cannot exist.
            if (!day.isBefore(today) || covered.contains(day)) {
                continue;
            }
            missing.add(day);
        }

        return new DprTile(inRange.size(),
                (int) inRange.stream().filter(d -> d.getWorkflowStatus()
                        == DailyProgressReport.Workflow.VERIFIED).count(),
                (int) inRange.stream().filter(d -> d.getWorkflowStatus()
                        == DailyProgressReport.Workflow.SUBMITTED).count(),
                (int) inRange.stream().filter(d -> d.getWorkflowStatus().isEditable()).count(),
                missing.size() > MAX_EXAMPLES ? missing.subList(0, MAX_EXAMPLES) : missing);
    }

    private static WorkItemRow toWorkItemRow(BoqLookup.ItemProgress item) {
        return new WorkItemRow(item.boqItemId(), item.itemNumber(), item.description(),
                item.contractQuantity(), item.completedQuantity(), item.percentComplete(),
                item.overClaimedQuantity(), item.contractAmount(), item.status());
    }

    /** Null rather than a number when the denominator is zero: no budget is not 100% used. */
    private static BigDecimal percentOf(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return null;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(List<Project> live,
                                  java.util.function.Function<Project, BigDecimal> field) {
        return live.stream().map(field).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * A dashboard draws a point per day, so an unbounded range is a request that either times
     * out or returns a chart nobody can read. Same rule the reports live by.
     */
    static void assertSaneRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new BusinessException("dashboard.range", "The end date is before the start date.");
        }
        if (from.plusDays(366).isBefore(to)) {
            throw new BusinessException("dashboard.range-too-wide",
                    "Dashboards cover at most one year at a time.");
        }
    }

    UUID orgId() {
        return currentUser.currentOrgId();
    }
}

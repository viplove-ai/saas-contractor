package in.nirman.modules.dpr.service;

import in.nirman.modules.dpr.api.dto.DprDtos.DprPrefill;
import in.nirman.modules.dpr.api.dto.DprDtos.ExpensePrefill;
import in.nirman.modules.dpr.api.dto.DprDtos.LabourLine;
import in.nirman.modules.dpr.api.dto.DprDtos.LabourPrefill;
import in.nirman.modules.dpr.api.dto.DprDtos.MaterialLine;
import in.nirman.modules.dpr.api.dto.DprDtos.MaterialPrefill;
import in.nirman.modules.dpr.api.dto.DprDtos.OutsourcedLine;
import in.nirman.modules.dpr.api.dto.DprDtos.OutsourcedPrefill;
import in.nirman.modules.dpr.api.dto.DprDtos.SuggestedWorkItem;
import in.nirman.modules.dpr.domain.DailyProgressReport;
import in.nirman.modules.dpr.repository.DailyProgressReportRepository;
import in.nirman.modules.expense.service.ExpenseLookup;
import in.nirman.modules.inventory.service.InventoryLookup;
import in.nirman.modules.labour.service.LabourLookup;
import in.nirman.modules.project.service.BoqLookup;
import in.nirman.modules.project.service.SiteLookup;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The DPR's auto-prefill: one call that reads labour, inventory and expense for a site and a
 * day, so a supervisor confirms figures rather than copying them off three screens.
 *
 * <h2>Why this is the phase's hardest thing to get right</h2>
 *
 * <p>The exit criterion is that <b>the prefill matches the underlying records exactly</b>, and
 * the failure mode is not arithmetic — it is a figure that was right when it was computed and
 * has since drifted. So nothing here is cached, stored or incrementally maintained. Every
 * number is derived on the call from the rows themselves, through each module's own read API,
 * which means a correction made ten minutes ago is already in it.</p>
 *
 * <h2>Three things the prefill refuses to do</h2>
 *
 * <p><b>It does not add up the day into one number for the supervisor.</b> Received value and
 * consumed value are different things (docs/02): the first is inventory, the second is cost.
 * Total booked and cost incurred are different things (docs/09): a material purchase is costed
 * again at issue, a wage payment settles a wage already costed. The prefill hands over all of
 * them separately and labels which one adds to project cost.</p>
 *
 * <p><b>It does not pretend the labour cost is final.</b> The wage is frozen at verification,
 * so a report prepared the same evening quotes provisional money. The flag says so, and the
 * report screen says so, because a figure that changes by itself overnight destroys trust in
 * every other figure beside it.</p>
 *
 * <p><b>It does not fill in quantities of work done.</b> It suggests the BOQ lines that had
 * labour or material charged to them, and stops. Cement issued against a line proves somebody
 * worked on it; it says nothing whatever about how many cubic metres got built, and a
 * pre-filled measurement is the one thing on this screen that must be somebody's own claim —
 * it becomes an entry in the measurement book when the engineer signs.</p>
 */
@Service
@Transactional(readOnly = true)
public class DprPrefillService {

    private static final String CAVEAT = """
            Labour, material consumed and cost incurred are the three figures that add to what \
            the day cost. Material received is inventory, not cost — it is costed again when \
            the material is issued. Total booked includes material purchases and wage \
            payments, which are both costed elsewhere, so it must not be added to anything.""";

    private final DailyProgressReportRepository reports;
    private final LabourLookup labour;
    private final InventoryLookup inventory;
    private final ExpenseLookup expenses;
    private final BoqLookup boqItems;
    private final SiteLookup sites;
    private final SiteAccessGuard siteAccessGuard;
    private final CurrentUserProvider currentUser;

    public DprPrefillService(DailyProgressReportRepository reports, LabourLookup labour,
                            InventoryLookup inventory, ExpenseLookup expenses, BoqLookup boqItems,
                            SiteLookup sites, SiteAccessGuard siteAccessGuard,
                            CurrentUserProvider currentUser) {
        this.reports = reports;
        this.labour = labour;
        this.inventory = inventory;
        this.expenses = expenses;
        this.boqItems = boqItems;
        this.sites = sites;
        this.siteAccessGuard = siteAccessGuard;
        this.currentUser = currentUser;
    }

    @PreAuthorize("hasAuthority('dpr:draft')")
    public DprPrefill prefill(UUID siteId, LocalDate date) {
        siteAccessGuard.assertCanAccess(siteId);
        SiteLookup.SiteInfo site = sites.require(siteId);

        Rollup rollup = rollup(siteId, date);
        var existing = reports.findBySiteIdAndReportDate(siteId, date);

        return new DprPrefill(siteId, site.name(), date,
                existing.isPresent(),
                existing.map(DailyProgressReport::getId).orElse(null),
                toLabourPrefill(rollup.labour()),
                toOutsourcedPrefill(rollup.outsourced()),
                toMaterialPrefill(rollup.material()),
                toExpensePrefill(rollup.expense()),
                rollup.labour().unverifiedCount() > 0,
                suggestedWorkItems(rollup),
                CAVEAT);
    }

    /**
     * The same three reads the prefill makes, for the write path to freeze onto a report.
     *
     * <p>Shared deliberately: if creating a report computed its snapshot by a different route
     * than the prefill showed, the two would eventually differ, and the criterion that the
     * report matches the records would be true of one code path and not the other.</p>
     */
    Rollup rollup(UUID siteId, LocalDate date) {
        return new Rollup(labour.day(siteId, date), labour.outsourced(siteId, date),
                inventory.day(siteId, date), expenses.day(siteId, date));
    }

    /**
     * The day as the three modules see it. Labour arrives twice because a site can have
     * both kinds at once — our own men on the muster roll and a contractor's gang counted at
     * the gate — and the two must never be added into one head count: one has hours and
     * wages behind it and the other has neither.
     */
    record Rollup(LabourLookup.LabourDay labour, LabourLookup.OutsourcedDay outsourced,
                  InventoryLookup.MaterialDay material, ExpenseLookup.DailySpend expense) {
    }

    // ------------------------------------------------------------------ mapping

    static LabourPrefill toLabourPrefill(LabourLookup.LabourDay day) {
        return new LabourPrefill(day.presentCount(), day.absentCount(), day.regularHours(),
                day.overtimeHours(), day.cost(), day.unverifiedCost(), day.recordCount(),
                day.unverifiedCount(), day.groups().stream()
                        .map(DprPrefillService::toLabourLine).toList());
    }

    static OutsourcedPrefill toOutsourcedPrefill(LabourLookup.OutsourcedDay day) {
        return new OutsourcedPrefill(day.enabled(), day.headCount(), day.groups().stream()
                .map(group -> new OutsourcedLine(group.skillCategoryId(),
                        group.skillCategoryName(), group.labourContractorId(),
                        group.labourContractorName(), group.headCount()))
                .toList());
    }

    static LabourLine toLabourLine(LabourLookup.LabourGroup group) {
        return new LabourLine(group.skillCategoryId(), group.skillCategoryName(),
                group.labourContractorId(), group.labourContractorName(), group.headCount(),
                group.regularHours(), group.overtimeHours(), false);
    }

    private static MaterialPrefill toMaterialPrefill(InventoryLookup.MaterialDay day) {
        return new MaterialPrefill(day.receivedValue(), day.consumedValue(), day.receiptCount(),
                day.issueCount(), day.materials().stream()
                        .map(movement -> new MaterialLine(movement.materialId(),
                                movement.materialCode(), movement.materialName(),
                                movement.baseUnitCode(), movement.receivedQty(),
                                movement.receivedValue(), movement.consumedQty(),
                                movement.consumedValue()))
                        .toList());
    }

    private static ExpensePrefill toExpensePrefill(ExpenseLookup.DailySpend day) {
        return new ExpensePrefill(day.totalBooked(), day.costIncurred(), day.materialPurchases(),
                day.labourDisbursements(), day.expenseCount(), day.unapprovedCount());
    }

    /**
     * The BOQ lines the day already touched, each with the reason it is being offered.
     *
     * <p>The reason matters more than the list. "Material issued" and "labour charged" are
     * different kinds of evidence, and a supervisor who can see which one produced the
     * suggestion can tell a line he genuinely worked on from one a storekeeper mis-tagged.</p>
     */
    private List<SuggestedWorkItem> suggestedWorkItems(Rollup rollup) {
        Set<UUID> fromLabour = new LinkedHashSet<>(rollup.labour().boqItemIds());
        Set<UUID> fromMaterial = new LinkedHashSet<>(rollup.material().boqItemIds());
        Set<UUID> all = new LinkedHashSet<>(fromLabour);
        all.addAll(fromMaterial);
        if (all.isEmpty()) {
            return List.of();
        }

        Map<UUID, BoqLookup.BoqItemInfo> named = boqItems.byIds(all);
        List<SuggestedWorkItem> suggestions = new ArrayList<>();
        for (UUID id : all) {
            BoqLookup.BoqItemInfo item = named.get(id);
            if (item == null) {
                continue;   // deleted since the charge was made; nothing useful to suggest
            }
            boolean byLabour = fromLabour.contains(id);
            boolean byMaterial = fromMaterial.contains(id);
            String because = byLabour && byMaterial ? "labour charged and material issued"
                    : byLabour ? "labour charged" : "material issued";
            suggestions.add(new SuggestedWorkItem(id, item.itemNumber(), item.description(),
                    item.unitId(), because));
        }
        suggestions.sort(Comparator.comparing(SuggestedWorkItem::itemNumber));
        return suggestions;
    }

    UUID orgId() {
        return currentUser.currentOrgId();
    }
}

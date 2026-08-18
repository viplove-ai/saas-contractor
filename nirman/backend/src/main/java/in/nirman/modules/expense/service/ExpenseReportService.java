package in.nirman.modules.expense.service;

import in.nirman.modules.expense.api.dto.ExpenseReportDtos.AdvanceBalanceRow;
import in.nirman.modules.expense.api.dto.ExpenseReportDtos.AdvanceBalancesReport;
import in.nirman.modules.expense.api.dto.ExpenseReportDtos.AgeingRow;
import in.nirman.modules.expense.api.dto.ExpenseReportDtos.ExpenseRegisterReport;
import in.nirman.modules.expense.api.dto.ExpenseReportDtos.PayableAgeingReport;
import in.nirman.modules.expense.api.dto.ExpenseReportDtos.RegisterRow;
import in.nirman.modules.expense.domain.Expense;
import in.nirman.modules.expense.domain.SiteAdvance;
import in.nirman.modules.expense.repository.ExpenseRepository;
import in.nirman.modules.expense.repository.SiteAdvanceRepository;
import in.nirman.modules.identity.repository.UserRepository;
import in.nirman.modules.masterdata.domain.ExpenseCategory;
import in.nirman.modules.masterdata.domain.Vendor;
import in.nirman.modules.masterdata.repository.ExpenseCategoryRepository;
import in.nirman.modules.masterdata.repository.VendorRepository;
import in.nirman.modules.project.service.SiteLookup;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The three cash reports, and the place where docs/09's double-counting guards finally have
 * a caller.
 *
 * <p>The expense register is the first screen in the system where the difference between
 * "money that left the books" and "cost the project incurred" becomes visible, so it is the
 * first place that has to state it. A material purchase becomes inventory and is costed
 * again when the material is issued; money handed to a worker settles a wage already costed
 * through verified attendance. Both are real payments and neither is incremental project
 * cost. Report one total and the project is overstated — at Kausani by most of ₹4,99,528 on
 * the labour side alone.</p>
 */
@Service
@Transactional(readOnly = true)
public class ExpenseReportService {

    private static final String CAVEAT = """
            Total booked is what left the books, not what the project cost. Material \
            purchases become inventory and are costed again when the material is issued; \
            labour disbursements settle wages already costed through verified attendance. \
            A bill from a labour supplier at a site that keeps no muster settles nothing \
            and is counted as cost, because there it is the only record of what the labour \
            cost. Cost incurred is the figure that adds to labour and material consumption \
            without counting anything twice.""";

    private final ExpenseRepository expenses;
    private final SiteAdvanceRepository advances;
    private final ExpenseCategoryRepository categories;
    private final VendorRepository vendors;
    private final UserRepository users;
    private final SiteLookup sites;
    private final SiteAccessGuard siteAccessGuard;
    private final CurrentUserProvider currentUser;

    public ExpenseReportService(ExpenseRepository expenses, SiteAdvanceRepository advances,
                                ExpenseCategoryRepository categories, VendorRepository vendors,
                                UserRepository users, SiteLookup sites,
                                SiteAccessGuard siteAccessGuard, CurrentUserProvider currentUser) {
        this.expenses = expenses;
        this.advances = advances;
        this.categories = categories;
        this.vendors = vendors;
        this.users = users;
        this.sites = sites;
        this.siteAccessGuard = siteAccessGuard;
        this.currentUser = currentUser;
    }

    /**
     * Everything booked at a site over a period, split four ways so nothing is counted twice.
     *
     * <p>Needs {@code report:financial}: this carries amounts, vendors and what is still
     * owed, which the matrix keeps to the administrator and the accountant. The operational
     * roles get quantities, not money — the same split that keeps the wage summary away
     * from the attendance register.</p>
     */
    @PreAuthorize("hasAuthority('report:financial')")
    public ExpenseRegisterReport register(UUID siteId, LocalDate from, LocalDate to) {
        if (siteId != null) {
            siteAccessGuard.assertCanAccess(siteId);
        }
        List<Expense> found = expenses.findForPeriod(orgId(), siteId, from, to);
        Map<UUID, ExpenseCategory> categoryById = categoryIndex(found);
        Map<UUID, String> vendorNames = vendorIndex(found);
        // Asked of the rows rather than of the report's scope: with no site given the
        // register spans the organisation, and two sites in it may be run differently.
        Set<UUID> outsourcedSites = sites.outsourcedLabourSites(found.stream()
                .map(Expense::getSiteId).filter(Objects::nonNull).collect(Collectors.toSet()));

        List<RegisterRow> rows = found.stream()
                .map(expense -> {
                    ExpenseCategory category = resolveCategory(expense, categoryById);
                    return new RegisterRow(expense.getId(), expense.getExpenseNumber(),
                            expense.getExpenseDate(),
                            category == null ? null : category.getName(),
                            // Guarded: most site expenses have no vendor, and Map.of()
                            // throws rather than returning null for a null key.
                            expense.getVendorId() == null
                                    ? null : vendorNames.get(expense.getVendorId()),
                            expense.getDescription(),
                            expense.getBillNumber(), expense.getAmountBeforeTax(),
                            expense.getGstAmount(), expense.getTotalAmount(),
                            expense.getPaidAmount(), expense.payableAmount(),
                            expense.getWorkflowStatus(),
                            category != null && category.isMaterialPurchase(),
                            category != null && category.isLabourPayment()
                                    && !outsourcedSites.contains(expense.getSiteId()));
                })
                .toList();

        BigDecimal totalBooked = sum(rows, RegisterRow::totalAmount);
        BigDecimal materialPurchases = sum(rows.stream()
                .filter(RegisterRow::materialPurchase).toList(), RegisterRow::totalAmount);
        BigDecimal labourDisbursements = sum(rows.stream()
                .filter(RegisterRow::wageSettlement).toList(), RegisterRow::totalAmount);

        return new ExpenseRegisterReport(scopeName(siteId), from, to, rows, totalBooked,
                totalBooked.subtract(materialPurchases).subtract(labourDisbursements),
                materialPurchases, labourDisbursements,
                sum(rows, RegisterRow::paidAmount), sum(rows, RegisterRow::payableAmount),
                CAVEAT);
    }

    /**
     * What is owed, and for how long.
     *
     * <p>Ages from the expense date rather than the bill date, because a bill dated three
     * months ago and booked last week is a week old as far as anybody chasing it is
     * concerned — and the bill date is the field most often left blank.</p>
     */
    @PreAuthorize("hasAuthority('report:financial')")
    public PayableAgeingReport payableAgeing(UUID vendorId, LocalDate asOf) {
        LocalDate today = asOf == null ? LocalDate.now() : asOf;
        Map<UUID, String> vendorNames = vendors.findAll().stream()
                .filter(vendor -> vendor.getOrgId().equals(orgId()))
                .collect(Collectors.toMap(Vendor::getId, Vendor::getName, (a, b) -> a));

        List<AgeingRow> rows = expenses.findOutstanding(orgId(), vendorId).stream()
                .map(expense -> {
                    int days = (int) ChronoUnit.DAYS.between(expense.getExpenseDate(), today);
                    return new AgeingRow(expense.getVendorId(),
                            expense.getVendorId() == null
                                    ? null : vendorNames.get(expense.getVendorId()),
                            expense.getExpenseNumber(),
                            expense.getExpenseDate(), days, bucketFor(days),
                            expense.getTotalAmount(), expense.getPaidAmount(),
                            expense.payableAmount());
                })
                .sorted(Comparator.comparingInt(AgeingRow::daysOutstanding).reversed())
                .toList();

        return new PayableAgeingReport(today, rows,
                sumAgeing(rows, null),
                sumAgeing(rows, "0-30"), sumAgeing(rows, "31-60"),
                sumAgeing(rows, "61-90"), sumAgeing(rows, "90+"));
    }

    /** Floats still in somebody's pocket, oldest first — the list somebody has to chase. */
    @PreAuthorize("hasAuthority('report:financial')")
    public AdvanceBalancesReport advanceBalances(UUID siteId, UUID userId, LocalDate asOf) {
        LocalDate today = asOf == null ? LocalDate.now() : asOf;
        if (siteId != null) {
            siteAccessGuard.assertCanAccess(siteId);
        }
        List<SiteAdvance> open = advances.findOpen(orgId(), siteId, userId);
        Map<UUID, String> holders = open.stream()
                .map(SiteAdvance::getIssuedToUserId)
                .distinct()
                .collect(Collectors.toMap(id -> id,
                        id -> users.findById(id).map(user -> user.getFullName()).orElse(null),
                        (a, b) -> a));

        List<AdvanceBalanceRow> rows = open.stream()
                .map(advance -> new AdvanceBalanceRow(advance.getId(), advance.getAdvanceNumber(),
                        advance.getSiteId(), siteName(advance.getSiteId()),
                        advance.getIssuedToUserId(), holders.get(advance.getIssuedToUserId()),
                        advance.getAdvanceDate(),
                        (int) ChronoUnit.DAYS.between(advance.getAdvanceDate(), today),
                        advance.getAmount(), advance.getAdjustedAmount(),
                        advance.getReturnedAmount(), advance.getBalanceAmount()))
                .sorted(Comparator.comparingInt(AdvanceBalanceRow::daysOutstanding).reversed())
                .toList();

        return new AdvanceBalancesReport(today, rows,
                rows.stream().map(AdvanceBalanceRow::balanceAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    // ------------------------------------------------------------------ internals

    private static String bucketFor(int days) {
        if (days <= 30) {
            return "0-30";
        }
        if (days <= 60) {
            return "31-60";
        }
        return days <= 90 ? "61-90" : "90+";
    }

    private static BigDecimal sumAgeing(List<AgeingRow> rows, String bucket) {
        return rows.stream()
                .filter(row -> bucket == null || bucket.equals(row.bucket()))
                .map(AgeingRow::payableAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sum(List<RegisterRow> rows,
                                  java.util.function.Function<RegisterRow, BigDecimal> field) {
        return rows.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * The flags live on the subcategory where there is one — "Worker Wage Payment" carries
     * {@code is_labour_payment}, its parent "Labour" does not, because not everything under
     * Labour is a disbursement.
     */
    private static ExpenseCategory resolveCategory(Expense expense,
                                                   Map<UUID, ExpenseCategory> index) {
        ExpenseCategory sub = expense.getSubcategoryId() == null
                ? null : index.get(expense.getSubcategoryId());
        if (sub != null) {
            return sub;
        }
        return expense.getCategoryId() == null ? null : index.get(expense.getCategoryId());
    }

    private Map<UUID, ExpenseCategory> categoryIndex(List<Expense> found) {
        Set<UUID> ids = found.stream()
                .flatMap(expense -> java.util.stream.Stream.of(expense.getCategoryId(),
                        expense.getSubcategoryId()))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of()
                : categories.findAllById(ids).stream()
                        .collect(Collectors.toMap(ExpenseCategory::getId, category -> category));
    }

    private Map<UUID, String> vendorIndex(List<Expense> found) {
        Set<UUID> ids = found.stream().map(Expense::getVendorId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of()
                : vendors.findAllById(ids).stream()
                        .collect(Collectors.toMap(Vendor::getId, Vendor::getName, (a, b) -> a));
    }

    private String siteName(UUID siteId) {
        return sites.require(siteId).name();
    }

    private String scopeName(UUID siteId) {
        return siteId == null ? "All sites" : siteName(siteId);
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }
}

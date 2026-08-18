package in.nirman.modules.expense.service;

import in.nirman.modules.expense.domain.Expense;
import in.nirman.modules.expense.domain.ExpenseSettings;
import in.nirman.modules.expense.repository.ExpenseAttachmentRepository;
import in.nirman.modules.expense.repository.ExpenseRepository;
import in.nirman.modules.expense.repository.ExpenseSettingsRepository;
import in.nirman.modules.masterdata.domain.ExpenseCategory;
import in.nirman.modules.masterdata.repository.ExpenseCategoryRepository;
import in.nirman.modules.project.service.SiteLookup;
import in.nirman.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@link ExpenseLookup}. The classification logic is the same one
 * {@link ExpenseReportService} uses for the expense register, and for the same reason: the
 * flags live on the subcategory where there is one — "Worker Wage Payment" carries
 * {@code is_labour_payment}, its parent "Labour" does not, because not everything under
 * Labour is a disbursement.
 *
 * <p>And the labour flag is read against the site, not on its own. It means "settles a wage
 * already costed through verified attendance", which is true wherever there is a muster and
 * false at a site that lets its labour to suppliers: nothing there is costed through
 * attendance, because nothing there is attendance. Excluding the supplier's bill at such a
 * site leaves the men who built it costing nothing at all — the same overstatement docs/09
 * chased out, run backwards.</p>
 */
@Service
@Transactional(readOnly = true)
public class ExpenseLookupService implements ExpenseLookup {

    /** Bill-number placeholders the field writes repeatedly. Mirrors {@link ExpenseService}. */
    private static final Set<String> PLACEHOLDER_BILLS =
            Set.of("-", "--", "NIL", "NA", "N/A", "LOCAL", "CASH", "");

    private final ExpenseRepository expenses;
    private final ExpenseAttachmentRepository billLinks;
    private final ExpenseSettingsRepository settings;
    private final ExpenseCategoryRepository categories;
    private final SiteLookup sites;
    private final CurrentUserProvider currentUser;

    public ExpenseLookupService(ExpenseRepository expenses, ExpenseAttachmentRepository billLinks,
                               ExpenseSettingsRepository settings,
                               ExpenseCategoryRepository categories,
                               SiteLookup sites,
                               CurrentUserProvider currentUser) {
        this.expenses = expenses;
        this.billLinks = billLinks;
        this.settings = settings;
        this.categories = categories;
        this.sites = sites;
        this.currentUser = currentUser;
    }

    @Override
    public DailySpend day(UUID siteId, LocalDate date) {
        List<Expense> found = expenses.findForPeriod(orgId(), siteId, date, date);
        Map<UUID, ExpenseCategory> byCategory = categoryIndex(found);
        Set<UUID> outsourcedSites = outsourcedSites(found);

        BigDecimal booked = BigDecimal.ZERO;
        BigDecimal material = BigDecimal.ZERO;
        BigDecimal labour = BigDecimal.ZERO;
        BigDecimal company = BigDecimal.ZERO;
        int unapproved = 0;

        for (Expense expense : found) {
            booked = booked.add(expense.getTotalAmount());
            ExpenseCategory category = resolveCategory(expense, byCategory);
            if (category != null && category.isMaterialPurchase()) {
                material = material.add(expense.getTotalAmount());
            } else if (settlesCostedWage(expense, category, outsourcedSites)) {
                labour = labour.add(expense.getTotalAmount());
            } else {
                // Only here. A material purchase and a wage payment are already out of cost
                // incurred, and their value belongs to this site's store and this site's
                // muster — which is why the service refuses to charge either to the company.
                // A supplier's bill at an outsourced site arrives here instead, and the same
                // refusal makes its company share zero, so the whole of it lands on the site.
                company = company.add(expense.companyCost());
            }
            if (expense.getWorkflowStatus() != Expense.Workflow.APPROVED) {
                unapproved++;
            }
        }

        return new DailySpend(date, booked,
                booked.subtract(material).subtract(labour).subtract(company),
                company, material, labour, found.size(), unapproved);
    }

    @Override
    public PeriodSpend period(UUID siteId, LocalDate from, LocalDate to) {
        List<Expense> found = expenses.findForPeriod(orgId(), siteId, from, to);
        Map<UUID, ExpenseCategory> byCategory = categoryIndex(found);
        Set<UUID> outsourcedSites = outsourcedSites(found);

        BigDecimal booked = BigDecimal.ZERO;
        BigDecimal material = BigDecimal.ZERO;
        BigDecimal labour = BigDecimal.ZERO;
        BigDecimal company = BigDecimal.ZERO;
        BigDecimal approved = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal payable = BigDecimal.ZERO;
        int awaiting = 0;

        for (Expense expense : found) {
            booked = booked.add(expense.getTotalAmount());
            ExpenseCategory category = resolveCategory(expense, byCategory);
            if (category != null && category.isMaterialPurchase()) {
                material = material.add(expense.getTotalAmount());
            } else if (settlesCostedWage(expense, category, outsourcedSites)) {
                labour = labour.add(expense.getTotalAmount());
            } else {
                company = company.add(expense.companyCost());
            }
            if (expense.getWorkflowStatus() == Expense.Workflow.APPROVED) {
                approved = approved.add(expense.getTotalAmount());
                payable = payable.add(expense.payableAmount());
            }
            if (expense.getWorkflowStatus().isInFlight()) {
                awaiting++;
            }
            paid = paid.add(expense.getPaidAmount());
        }

        return new PeriodSpend(from, to, booked,
                booked.subtract(material).subtract(labour).subtract(company),
                company, material, labour, approved, paid, payable, found.size(), awaiting);
    }

    @Override
    public List<DailyCost> dailyCostIncurred(UUID siteId, LocalDate from, LocalDate to) {
        List<Expense> found = expenses.findForPeriod(orgId(), siteId, from, to);
        Map<UUID, ExpenseCategory> byCategory = categoryIndex(found);
        Set<UUID> outsourcedSites = outsourcedSites(found);

        Map<LocalDate, BigDecimal> byDay = new LinkedHashMap<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            byDay.put(day, BigDecimal.ZERO);
        }
        for (Expense expense : found) {
            ExpenseCategory category = resolveCategory(expense, byCategory);
            // Material purchases and wage settlements are costed elsewhere, so neither belongs
            // on a cost trend. Adding them would draw a line the project never spent — and
            // dropping a labour supplier's bill would flatten a line the site did spend.
            if (category != null && category.isMaterialPurchase()) {
                continue;
            }
            if (settlesCostedWage(expense, category, outsourcedSites)) {
                continue;
            }
            // The site's share, not the total: the half of a diesel bill that ran the office
            // car is on the same trend line otherwise, and the site never spent it.
            byDay.merge(expense.getExpenseDate(), expense.siteCost(), BigDecimal::add);
        }
        return byDay.entrySet().stream()
                .map(entry -> new DailyCost(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * Counted the same way {@code ExpenseService.assertHasEvidence} decides it, placeholders
     * included — a bill box reading "NIL" is not a bill number, and a dashboard that accepted
     * it would report a clean site while the file has nothing in it.
     */
    @Override
    public int missingEvidenceCount(UUID siteId, LocalDate from, LocalDate to) {
        BigDecimal threshold = settings.findByOrgId(orgId())
                .orElseGet(() -> new ExpenseSettings(orgId()))
                .getBillRequiredAbove();
        return (int) expenses.findForPeriod(orgId(), siteId, from, to).stream()
                .filter(expense -> expense.getTotalAmount().compareTo(threshold) > 0)
                .filter(expense -> !hasBillNumber(expense.getBillNumber()))
                .filter(expense -> !billLinks.existsByExpenseId(expense.getId()))
                .count();
    }

    // ------------------------------------------------------------------ internals

    private static boolean hasBillNumber(String billNumber) {
        return billNumber != null && !billNumber.isBlank()
                && !PLACEHOLDER_BILLS.contains(billNumber.trim().toUpperCase());
    }

    /**
     * Whether this row settles a wage the project has already counted, and so must stay out
     * of cost incurred.
     *
     * <p>The head says it is a labour disbursement; the site says whether that means
     * anything. Where a muster roll exists, verified attendance has already costed the wage
     * and paying it a second time into cost would double it. Where the work is let to a
     * supplier there is no muster, nothing was costed, and the bill is the only record of
     * what the labour cost — so it is cost, like any other bill.</p>
     */
    private static boolean settlesCostedWage(Expense expense, ExpenseCategory category,
                                             Set<UUID> outsourcedSites) {
        return category != null && category.isLabourPayment()
                && !outsourcedSites.contains(expense.getSiteId());
    }

    /** One question for the whole page of expenses, however many sites they came from. */
    private Set<UUID> outsourcedSites(List<Expense> found) {
        return sites.outsourcedLabourSites(found.stream()
                .map(Expense::getSiteId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
    }

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
                .flatMap(expense -> Stream.of(expense.getCategoryId(), expense.getSubcategoryId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of()
                : categories.findAllById(ids).stream()
                        .collect(Collectors.toMap(ExpenseCategory::getId, category -> category));
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }
}

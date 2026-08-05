package in.nirman.modules.expense.service;

import in.nirman.modules.expense.domain.Expense;
import in.nirman.modules.expense.domain.ExpenseSettings;
import in.nirman.modules.expense.repository.ExpenseAttachmentRepository;
import in.nirman.modules.expense.repository.ExpenseRepository;
import in.nirman.modules.expense.repository.ExpenseSettingsRepository;
import in.nirman.modules.masterdata.domain.ExpenseCategory;
import in.nirman.modules.masterdata.repository.ExpenseCategoryRepository;
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
    private final CurrentUserProvider currentUser;

    public ExpenseLookupService(ExpenseRepository expenses, ExpenseAttachmentRepository billLinks,
                               ExpenseSettingsRepository settings,
                               ExpenseCategoryRepository categories,
                               CurrentUserProvider currentUser) {
        this.expenses = expenses;
        this.billLinks = billLinks;
        this.settings = settings;
        this.categories = categories;
        this.currentUser = currentUser;
    }

    @Override
    public DailySpend day(UUID siteId, LocalDate date) {
        List<Expense> found = expenses.findForPeriod(orgId(), siteId, date, date);
        Map<UUID, ExpenseCategory> byCategory = categoryIndex(found);

        BigDecimal booked = BigDecimal.ZERO;
        BigDecimal material = BigDecimal.ZERO;
        BigDecimal labour = BigDecimal.ZERO;
        int unapproved = 0;

        for (Expense expense : found) {
            booked = booked.add(expense.getTotalAmount());
            ExpenseCategory category = resolveCategory(expense, byCategory);
            if (category != null && category.isMaterialPurchase()) {
                material = material.add(expense.getTotalAmount());
            } else if (category != null && category.isLabourPayment()) {
                labour = labour.add(expense.getTotalAmount());
            }
            if (expense.getWorkflowStatus() != Expense.Workflow.APPROVED) {
                unapproved++;
            }
        }

        return new DailySpend(date, booked, booked.subtract(material).subtract(labour),
                material, labour, found.size(), unapproved);
    }

    @Override
    public PeriodSpend period(UUID siteId, LocalDate from, LocalDate to) {
        List<Expense> found = expenses.findForPeriod(orgId(), siteId, from, to);
        Map<UUID, ExpenseCategory> byCategory = categoryIndex(found);

        BigDecimal booked = BigDecimal.ZERO;
        BigDecimal material = BigDecimal.ZERO;
        BigDecimal labour = BigDecimal.ZERO;
        BigDecimal approved = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal payable = BigDecimal.ZERO;
        int awaiting = 0;

        for (Expense expense : found) {
            booked = booked.add(expense.getTotalAmount());
            ExpenseCategory category = resolveCategory(expense, byCategory);
            if (category != null && category.isMaterialPurchase()) {
                material = material.add(expense.getTotalAmount());
            } else if (category != null && category.isLabourPayment()) {
                labour = labour.add(expense.getTotalAmount());
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

        return new PeriodSpend(from, to, booked, booked.subtract(material).subtract(labour),
                material, labour, approved, paid, payable, found.size(), awaiting);
    }

    @Override
    public List<DailyCost> dailyCostIncurred(UUID siteId, LocalDate from, LocalDate to) {
        List<Expense> found = expenses.findForPeriod(orgId(), siteId, from, to);
        Map<UUID, ExpenseCategory> byCategory = categoryIndex(found);

        Map<LocalDate, BigDecimal> byDay = new LinkedHashMap<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            byDay.put(day, BigDecimal.ZERO);
        }
        for (Expense expense : found) {
            ExpenseCategory category = resolveCategory(expense, byCategory);
            // Material purchases and wage payments are costed elsewhere, so neither belongs on
            // a cost trend. Adding them would draw a line the project never spent.
            if (category != null && (category.isMaterialPurchase() || category.isLabourPayment())) {
                continue;
            }
            byDay.merge(expense.getExpenseDate(), expense.getTotalAmount(), BigDecimal::add);
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
                .map(ExpenseSettings::getBillRequiredAbove)
                .orElse(BigDecimal.ZERO);
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

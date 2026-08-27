package in.nirman.modules.expense.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.expense.api.dto.DepositDtos.DepositRegister;
import in.nirman.modules.expense.api.dto.DepositDtos.DepositRow;
import in.nirman.modules.expense.api.dto.DepositDtos.RefundResponse;
import in.nirman.modules.expense.api.dto.DepositDtos.SettleDepositRequest;
import in.nirman.modules.expense.domain.Expense;
import in.nirman.modules.expense.domain.ExpenseRefund;
import in.nirman.modules.expense.repository.ExpenseRefundRepository;
import in.nirman.modules.expense.repository.ExpenseRepository;
import in.nirman.modules.masterdata.domain.ExpenseCategory;
import in.nirman.modules.masterdata.repository.ExpenseCategoryRepository;
import in.nirman.modules.masterdata.repository.VendorRepository;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The money we placed and have not got back.
 *
 * <p>A security on an electricity meter, the deposit on a hired mixer, a cylinder, a
 * barricade, a temporary water connection. It goes out on an ordinary bill and part of that
 * bill is not spending at all — it is the company's money sitting with somebody, and it comes
 * back when the meter is surrendered.</p>
 *
 * <p><b>V38 did this for the department's money and this does it for everybody else's.</b>
 * The shape is deliberately the same: a register that records what was actually placed and
 * what actually came back, rather than a rule that recomputes what ought to be there. And the
 * reason is the same one — a few lakh across six sites, tracked in the memory of whoever paid
 * it, discovered missing when the office needs the cash for the next tender.</p>
 *
 * <h2>Why settling is its own act, by its own person</h2>
 *
 * <p>{@code payment:record} rather than a permission of its own. Recording that money came
 * back is the mirror of recording that money went out — the same accountant, reading the same
 * bank statement, on the same afternoon — and an organisation able to grant one without the
 * other would have somebody who may say a deposit returned but not that a cheque was drawn.
 * Saying that part of a bill <i>is</i> a deposit is a different act and stays with
 * {@code expense:create}, because it is part of typing the bill.</p>
 *
 * <h2>What a write-off does not do</h2>
 *
 * <p>It does not become cost here. A deposit that will never come back is a real loss, and the
 * loss belongs to the day somebody decided it rather than to the day the connection was taken
 * — history does not move. So the write-off closes the row and says why, and booking the loss
 * is a fresh expense under a loss head at that date. The register's job is to make sure
 * somebody is looking at it.</p>
 */
@Service
@Transactional
public class ExpenseDepositService {

    private final ExpenseRepository expenses;
    private final ExpenseRefundRepository refunds;
    private final ExpenseCategoryRepository categories;
    private final VendorRepository vendors;
    private final SiteAccessGuard siteAccessGuard;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public ExpenseDepositService(ExpenseRepository expenses, ExpenseRefundRepository refunds,
                                 ExpenseCategoryRepository categories, VendorRepository vendors,
                                 SiteAccessGuard siteAccessGuard,
                                 CurrentUserProvider currentUser, AuditService audit) {
        this.expenses = expenses;
        this.refunds = refunds;
        this.categories = categories;
        this.vendors = vendors;
        this.siteAccessGuard = siteAccessGuard;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reads

    /**
     * Every bill carrying a deposit, and what has come back on each.
     *
     * <p>Not paged. The whole register is a few dozen rows even for an organisation that has
     * been running for years — deposits are rare and long-lived — and a total over the
     * twenty-five somebody happens to be looking at would not be a total.</p>
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('expense:read')")
    public DepositRegister register(UUID siteId, boolean openOnly) {
        if (siteId != null) {
            siteAccessGuard.assertCanAccess(siteId);
        }
        boolean restricted = !currentUser.seesAllSites();
        Collection<UUID> visible = restricted ? currentUser.assignedSiteIds() : List.of();
        if (restricted && visible.isEmpty()) {
            return new DepositRegister(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, List.of());
        }

        List<Expense> found = expenses.findDeposits(orgId(), siteId, openOnly, restricted,
                visible);
        Map<UUID, List<ExpenseRefund>> settlements = settlementsFor(found);
        Map<UUID, ExpenseCategory> categoryById = categoryIndex(found);
        Map<UUID, String> vendorNames = vendorIndex(found);
        LocalDate today = LocalDate.now();

        BigDecimal placed = BigDecimal.ZERO;
        BigDecimal received = BigDecimal.ZERO;
        BigDecimal writtenOff = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;
        int open = 0;

        List<DepositRow> rows = new java.util.ArrayList<>(found.size());
        for (Expense expense : found) {
            BigDecimal left = expense.outstandingDeposit();
            boolean late = left.signum() > 0
                    && expense.getRefundExpectedOn() != null
                    && expense.getRefundExpectedOn().isBefore(today);

            placed = placed.add(expense.getRefundableAmount());
            received = received.add(expense.getRefundedAmount());
            writtenOff = writtenOff.add(expense.getWrittenOffAmount());
            outstanding = outstanding.add(left);
            if (late) {
                overdue = overdue.add(left);
            }
            if (left.signum() > 0) {
                open++;
            }

            ExpenseCategory head = resolveCategory(expense, categoryById);
            rows.add(new DepositRow(expense.getId(), expense.getExpenseNumber(),
                    expense.getSiteId(), expense.getExpenseDate(), expense.getDescription(),
                    head == null ? null : head.getName(), expense.getVendorId(),
                    expense.getVendorId() == null ? null : vendorNames.get(expense.getVendorId()),
                    expense.getTotalAmount(), expense.getRefundableAmount(),
                    expense.getRefundedAmount(), expense.getWrittenOffAmount(), left,
                    expense.getRefundExpectedOn(), late, expense.depositStatus(),
                    expense.getWorkflowStatus(),
                    settlements.getOrDefault(expense.getId(), List.of()).stream()
                            .map(ExpenseDepositService::toResponse).toList()));
        }

        return new DepositRegister(placed, received, writtenOff, outstanding, overdue,
                rows.size(), open, List.copyOf(rows));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('expense:read')")
    public List<RefundResponse> settlements(UUID expenseId) {
        Expense expense = require(expenseId);
        siteAccessGuard.assertCanAccess(expense.getSiteId());
        return refunds.findByExpenseIdOrderBySettledOnAscCreatedAtAsc(expenseId).stream()
                .map(ExpenseDepositService::toResponse)
                .toList();
    }

    // ------------------------------------------------------------------ settling

    /**
     * Records what became of a deposit, and moves the expense's running totals.
     *
     * <p>The refusals are the ones that keep the register honest rather than merely tidy:</p>
     *
     * <ul>
     *   <li><b>Nothing settles that was never placed.</b> An expense with no refundable part
     *       has no deposit to give back, and a refund against one would be an unexplained
     *       credit sitting on a bill.</li>
     *   <li><b>Not more than is outstanding.</b> Money over the deposit is not this deposit
     *       coming back — it is a credit note or an overpayment, and merging the two makes
     *       the register report deposits the company never placed.</li>
     *   <li><b>Not against an unapproved bill</b>, for the reason nothing is paid against
     *       one: the flow is what decides that this expense exists at all.</li>
     * </ul>
     */
    @PreAuthorize("hasAuthority('payment:record')")
    public RefundResponse settle(UUID expenseId, SettleDepositRequest request) {
        Expense expense = require(expenseId);
        siteAccessGuard.assertCanAccess(expense.getSiteId());

        if (expense.getRefundableAmount().signum() == 0) {
            throw new BusinessException("deposit.none-on-expense",
                    "No part of %s is a refundable deposit, so there is nothing to settle "
                            .formatted(expense.getExpenseNumber())
                            + "against it. Money coming in against this bill is a credit note "
                            + "and belongs on its own record.");
        }
        if (expense.getWorkflowStatus() != Expense.Workflow.APPROVED) {
            throw new BusinessException("deposit.expense-not-approved",
                    "Expense %s is %s. Nothing settles against a bill that has not been "
                            .formatted(expense.getExpenseNumber(), spell(expense))
                            + "approved.");
        }
        BigDecimal outstanding = expense.outstandingDeposit();
        if (request.amount().compareTo(outstanding) > 0) {
            throw new BusinessException("deposit.exceeds-outstanding",
                    "Only %s of the deposit on %s is still out there. Anything above that is "
                            .formatted(outstanding.toPlainString(), expense.getExpenseNumber())
                            + "not this deposit coming back — record it on its own.");
        }
        boolean writeOff = request.outcome() == ExpenseRefund.Outcome.WRITTEN_OFF;
        if (writeOff && isBlank(request.reason())) {
            throw new BusinessException("deposit.writeoff-reason-required",
                    "Giving up on a deposit needs a reason. A deposit that vanishes from the "
                            + "register without one cannot be asked about six months later.");
        }

        ExpenseRefund refund = new ExpenseRefund(orgId(), expenseId, request.outcome(),
                request.settledOn(), request.amount());
        refund.setPaymentMode(writeOff ? null : emptyToNull(request.paymentMode()));
        refund.setReferenceNumber(writeOff ? null : emptyToNull(request.referenceNumber()));
        refund.setReason(emptyToNull(request.reason()));
        refund.setRemarks(request.remarks());
        refunds.save(refund);

        expense.settleDeposit(writeOff ? BigDecimal.ZERO : request.amount(),
                writeOff ? request.amount() : BigDecimal.ZERO);

        audit.record(ExpenseService.ENTITY_TYPE, expenseId,
                writeOff ? "DEPOSIT_WRITE_OFF" : "DEPOSIT_REFUND", null,
                Map.of("expenseNumber", expense.getExpenseNumber(),
                        "amount", request.amount(),
                        "outstandingAfter", expense.outstandingDeposit(),
                        "depositStatus", expense.depositStatus().name()),
                request.reason());
        return toResponse(refund);
    }

    // ------------------------------------------------------------------ internals

    private Map<UUID, List<ExpenseRefund>> settlementsFor(List<Expense> found) {
        if (found.isEmpty()) {
            return Map.of();
        }
        return refunds.findByExpenseIdInOrderBySettledOnAsc(
                        found.stream().map(Expense::getId).toList()).stream()
                .collect(Collectors.groupingBy(ExpenseRefund::getExpenseId));
    }

    private static RefundResponse toResponse(ExpenseRefund refund) {
        return new RefundResponse(refund.getId(), refund.getExpenseId(), refund.getOutcome(),
                refund.getSettledOn(), refund.getAmount(), refund.getPaymentMode(),
                refund.getReferenceNumber(), refund.getReason(), refund.getRemarks());
    }

    private static ExpenseCategory resolveCategory(Expense expense,
                                                   Map<UUID, ExpenseCategory> index) {
        ExpenseCategory sub = expense.getSubcategoryId() == null
                ? null : index.get(expense.getSubcategoryId());
        return sub != null ? sub
                : expense.getCategoryId() == null ? null : index.get(expense.getCategoryId());
    }

    private Map<UUID, ExpenseCategory> categoryIndex(List<Expense> found) {
        Set<UUID> ids = found.stream()
                .flatMap(expense -> java.util.stream.Stream.of(expense.getCategoryId(),
                        expense.getSubcategoryId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of()
                : categories.findAllById(ids).stream()
                        .collect(Collectors.toMap(ExpenseCategory::getId, category -> category));
    }

    private Map<UUID, String> vendorIndex(List<Expense> found) {
        Set<UUID> ids = found.stream().map(Expense::getVendorId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of()
                : vendors.findAllById(ids).stream()
                        .collect(Collectors.toMap(vendor -> vendor.getId(),
                                vendor -> vendor.getName()));
    }

    private static String spell(Expense expense) {
        return expense.getWorkflowStatus().name().toLowerCase().replace('_', ' ');
    }

    private Expense require(UUID id) {
        return expenses.findByIdAndOrgId(id, orgId())
                .orElseThrow(() -> BusinessException.notFound("Expense", id));
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String emptyToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }
}

package in.nirman.modules.expense.service;

import in.nirman.common.BusinessException;
import in.nirman.common.DocumentNumberService;
import in.nirman.common.PageResponse;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.expense.api.dto.CashDtos.PaymentResponse;
import in.nirman.modules.expense.api.dto.CashDtos.RecordPaymentRequest;
import in.nirman.modules.expense.api.dto.CashDtos.VendorBalanceRow;
import in.nirman.modules.expense.domain.Expense;
import in.nirman.modules.expense.domain.Payment;
import in.nirman.modules.expense.repository.ExpenseRepository;
import in.nirman.modules.expense.repository.PaymentRepository;
import in.nirman.modules.masterdata.domain.Vendor;
import in.nirman.modules.masterdata.repository.VendorRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cash actually leaving, against expenses somebody approved.
 *
 * <p><b>The identity this service exists to keep true:</b> approved cost, cash paid and
 * payable reconcile. {@code expenses.total_amount} is what was agreed, the sum of this
 * table's rows is what went out, and the difference is what is still owed. Nobody types the
 * third one; it falls out of the first two, which is why {@code paid_amount} on the expense
 * is a running total this service adds to and nothing else writes.</p>
 *
 * <p>Two rules protect it. A payment cannot exceed what is still payable — overpaying a
 * supplier is a real event, but it is an advance to them and not a payment against this
 * bill, and merging the two makes the ageing report lie. And nothing can be paid against an
 * expense that is not approved: {@code ck_expense_paid_only_when_approved} (V8) is the
 * backstop, this is the sentence.</p>
 */
@Service
@Transactional
public class PaymentService {

    private final PaymentRepository payments;
    private final ExpenseRepository expenses;
    private final VendorRepository vendors;
    private final DocumentNumberService documentNumbers;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public PaymentService(PaymentRepository payments, ExpenseRepository expenses,
                          VendorRepository vendors, DocumentNumberService documentNumbers,
                          CurrentUserProvider currentUser, AuditService audit) {
        this.payments = payments;
        this.expenses = expenses;
        this.vendors = vendors;
        this.documentNumbers = documentNumbers;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('expense:read')")
    public PageResponse<PaymentResponse> list(UUID vendorId, UUID expenseId, LocalDate from,
                                              LocalDate to, Pageable pageable) {
        return PageResponse.from(
                payments.search(orgId(), vendorId, expenseId, from, to, pageable),
                this::toResponse);
    }

    /**
     * Records a payment and moves the expense's running total.
     *
     * <p>{@code payment:record} is the accountant's and the administrator's alone — the
     * matrix is deliberate about this. The person who books an expense must not also be the
     * person who pays it.</p>
     */
    @PreAuthorize("hasAuthority('payment:record')")
    public PaymentResponse record(RecordPaymentRequest request) {
        Expense expense = expenses.findByIdAndOrgId(request.expenseId(), orgId())
                .orElseThrow(() -> BusinessException.notFound("Expense", request.expenseId()));

        if (expense.getWorkflowStatus() != Expense.Workflow.APPROVED) {
            throw new BusinessException("payment.expense-not-approved",
                    "Expense %s is %s. Nothing is paid until it is approved."
                            .formatted(expense.getExpenseNumber(),
                                    expense.getWorkflowStatus().name().toLowerCase()
                                            .replace('_', ' ')));
        }
        BigDecimal payable = expense.payableAmount();
        if (request.amount().compareTo(payable) > 0) {
            throw new BusinessException("payment.exceeds-payable",
                    "Only %s is still payable on %s. Record the excess as a separate advance "
                            .formatted(payable.toPlainString(), expense.getExpenseNumber())
                            + "to the vendor rather than against this bill.");
        }

        String number = documentNumbers.next(orgId(), DocumentNumberService.DocType.PAYMENT,
                request.paymentDate());
        Payment payment = new Payment(orgId(), expense.getProjectId(), expense.getSiteId(),
                expense.getId(), expense.getVendorId(), number, request.paymentDate(),
                request.amount(), request.paymentMode());
        payment.setReferenceNumber(request.referenceNumber());
        payment.setBankAccount(request.bankAccount());
        payment.setRemarks(request.remarks());
        payments.save(payment);

        expense.addPayment(request.amount());

        audit.record("PAYMENT", payment.getId(), "CREATE", null,
                Map.of("paymentNumber", number, "expenseNumber", expense.getExpenseNumber(),
                        "amount", request.amount(), "paidToDate", expense.getPaidAmount(),
                        "stillPayable", expense.payableAmount()), request.remarks());
        return toResponse(payment);
    }

    /**
     * What is owed to each vendor, in the three figures that must never be merged.
     *
     * <p>Built from outstanding expenses rather than from a stored balance on the vendor.
     * A balance somebody can type is a balance that stops matching the bills behind it, and
     * the bills are what the vendor will actually argue about.</p>
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('vendor:balance:manage')")
    public List<VendorBalanceRow> vendorBalances(UUID vendorId) {
        Map<UUID, BigDecimal[]> totals = new HashMap<>();
        Map<UUID, Integer> counts = new HashMap<>();
        Map<UUID, LocalDate> oldest = new HashMap<>();

        for (Expense expense : expenses.findOutstanding(orgId(), vendorId)) {
            UUID key = expense.getVendorId();
            if (key == null) {
                continue;   // a cash purchase owes nobody; it has no vendor to age against
            }
            BigDecimal[] cell = totals.computeIfAbsent(key,
                    id -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            cell[0] = cell[0].add(expense.getTotalAmount());
            cell[1] = cell[1].add(expense.getPaidAmount());
            counts.merge(key, 1, Integer::sum);
            oldest.merge(key, expense.getExpenseDate(),
                    (a, b) -> a.isBefore(b) ? a : b);
        }

        return totals.entrySet().stream()
                .map(entry -> {
                    Vendor vendor = vendors.findById(entry.getKey()).orElse(null);
                    BigDecimal approved = entry.getValue()[0];
                    BigDecimal paid = entry.getValue()[1];
                    return new VendorBalanceRow(entry.getKey(),
                            vendor == null ? null : vendor.getCode(),
                            vendor == null ? null : vendor.getName(),
                            approved, paid, approved.subtract(paid),
                            counts.getOrDefault(entry.getKey(), 0),
                            oldest.get(entry.getKey()));
                })
                .sorted(Comparator.comparing(VendorBalanceRow::payableAmount).reversed())
                .toList();
    }

    // ------------------------------------------------------------------ internals

    private PaymentResponse toResponse(Payment payment) {
        String expenseNumber = payment.getExpenseId() == null ? null
                : expenses.findById(payment.getExpenseId())
                        .map(Expense::getExpenseNumber).orElse(null);
        String vendorName = payment.getVendorId() == null ? null
                : vendors.findById(payment.getVendorId()).map(Vendor::getName).orElse(null);
        return new PaymentResponse(payment.getId(), payment.getPaymentNumber(),
                payment.getExpenseId(), expenseNumber, payment.getVendorId(), vendorName,
                payment.getPaymentDate(), payment.getAmount(), payment.getPaymentMode(),
                payment.getReferenceNumber(), payment.getBankAccount(), payment.getRemarks(),
                payment.getReconciledAt(), payment.getVersion());
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }
}

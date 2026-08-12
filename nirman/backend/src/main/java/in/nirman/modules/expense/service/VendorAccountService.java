package in.nirman.modules.expense.service;

import in.nirman.common.BusinessException;
import in.nirman.common.DocumentNumberService;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.expense.api.dto.CashDtos.PaymentResponse;
import in.nirman.modules.expense.api.dto.CashDtos.RecordVendorAdvanceRequest;
import in.nirman.modules.expense.api.dto.CashDtos.VendorAccountResponse;
import in.nirman.modules.expense.api.dto.CashDtos.VendorPurchaseRow;
import in.nirman.modules.expense.domain.Expense;
import in.nirman.modules.expense.domain.Payment;
import in.nirman.modules.expense.repository.ExpenseRepository;
import in.nirman.modules.expense.repository.PaymentRepository;
import in.nirman.modules.inventory.service.InventoryLookup;
import in.nirman.modules.masterdata.domain.Vendor;
import in.nirman.modules.masterdata.repository.VendorRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A supplier's account: what he sent, what he billed, what has gone out to him.
 *
 * <p>Separate from {@link PaymentService} because the two answer different questions.
 * PaymentService is about one bill being settled and keeps the identity that
 * {@code approved − paid = payable} on that bill. This is about the supplier across all his
 * bills, and it has to carry the one figure that belongs to none of them: the advance.</p>
 *
 * <p><b>Nothing here is stored.</b> Every figure is summed from the expenses, the payments
 * and the deliveries on each call. {@code vendors.opening_balance} is the single exception
 * and is exactly what its name says — what he was owed on the day the organisation started
 * using this system, typed once. A running balance somebody can write to is a balance that
 * stops matching the bills behind it, and the bills are what the supplier will argue about.</p>
 */
@Service
@Transactional
public class VendorAccountService {

    private final PaymentRepository payments;
    private final ExpenseRepository expenses;
    private final VendorRepository vendors;
    private final InventoryLookup inventory;
    private final DocumentNumberService documentNumbers;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public VendorAccountService(PaymentRepository payments, ExpenseRepository expenses,
                                VendorRepository vendors, InventoryLookup inventory,
                                DocumentNumberService documentNumbers,
                                CurrentUserProvider currentUser, AuditService audit) {
        this.payments = payments;
        this.expenses = expenses;
        this.vendors = vendors;
        this.inventory = inventory;
        this.documentNumbers = documentNumbers;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    /**
     * Cash to a supplier against no bill of his.
     *
     * <p>Behind {@code payment:record}, the same permission that settles a bill, and for the
     * same reason: it is money leaving. It is deliberately <em>not</em> set off against any
     * particular expense here — an advance is against the supplier, and which of his bills it
     * eventually covers is a question his bills answer as they arrive, not one the accountant
     * guesses on the day the money goes out.</p>
     */
    @PreAuthorize("hasAuthority('payment:record')")
    public PaymentResponse recordAdvance(UUID vendorId, RecordVendorAdvanceRequest request) {
        Vendor vendor = requireVendor(vendorId);
        if (!vendor.isActive()) {
            throw new BusinessException("vendor.inactive",
                    vendor.getName() + " is not an active supplier. Reactivate the vendor "
                            + "before paying anything to them.");
        }

        String number = documentNumbers.next(orgId(), DocumentNumberService.DocType.PAYMENT,
                request.paymentDate());
        // No project and no site: an advance is against the supplier, not against a job. The
        // delivery it eventually funds may go to a site nobody has picked yet.
        Payment payment = new Payment(orgId(), null, null, null, vendorId, number,
                request.paymentDate(), request.amount(), request.paymentMode());
        payment.setReferenceNumber(request.referenceNumber());
        payment.setBankAccount(request.bankAccount());
        payment.setRemarks(request.remarks());
        payments.save(payment);

        audit.record("PAYMENT", payment.getId(), "VENDOR_ADVANCE", null,
                Map.of("paymentNumber", number, "vendorId", vendorId.toString(),
                        "vendorName", vendor.getName(), "amount", request.amount()),
                request.remarks());
        // No expense number on the row, and that absence is the fact: this is the payment
        // that belongs to no bill.
        return new PaymentResponse(payment.getId(), payment.getPaymentNumber(), null, null,
                vendorId, vendor.getName(), payment.getPaymentDate(), payment.getAmount(),
                payment.getPaymentMode(), payment.getReferenceNumber(),
                payment.getBankAccount(), payment.getRemarks(), payment.getReconciledAt(),
                payment.getVersion());
    }

    /**
     * Where the account stands. Five figures, none of them merged — see
     * {@link VendorAccountResponse} for why one balance would be the wrong answer.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('vendor:balance:manage')")
    public VendorAccountResponse account(UUID vendorId) {
        Vendor vendor = requireVendor(vendorId);

        BigDecimal billed = BigDecimal.ZERO;
        BigDecimal paidAgainstBills = BigDecimal.ZERO;
        int openBills = 0;
        LocalDate oldest = null;
        for (Expense expense : expenses.findOutstanding(orgId(), vendorId)) {
            billed = billed.add(expense.getTotalAmount());
            paidAgainstBills = paidAgainstBills.add(expense.getPaidAmount());
            openBills++;
            if (oldest == null || expense.getExpenseDate().isBefore(oldest)) {
                oldest = expense.getExpenseDate();
            }
        }

        // Every payment naming him and no bill. Summed from the rows rather than tracked as
        // a balance, so it cannot drift from the payments that make it up.
        BigDecimal advance = payments.sumVendorAdvances(orgId(), vendorId);

        List<InventoryLookup.VendorPurchase> purchases =
                inventory.vendorPurchases(vendorId, null, null);
        BigDecimal purchasedValue = purchases.stream()
                .map(line -> line.amount() == null ? BigDecimal.ZERO : line.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long deliveries = purchases.stream().map(InventoryLookup.VendorPurchase::receiptId)
                .distinct().count();

        BigDecimal outstanding = billed.subtract(paidAgainstBills);
        return new VendorAccountResponse(vendorId, vendor.getCode(), vendor.getName(),
                vendor.getOpeningBalance(), billed, paidAgainstBills, advance,
                outstanding, outstanding.subtract(advance), openBills, oldest,
                purchasedValue, (int) deliveries);
    }

    /**
     * What he has actually sent, line by line and newest first.
     *
     * <p>Read off the deliveries rather than off anything stored against the vendor. The
     * quantities and rates are on the goods receipts already, and a second copy kept here
     * would be the copy that is wrong the first time a receipt is re-priced.</p>
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('vendor:balance:manage')")
    public List<VendorPurchaseRow> purchases(UUID vendorId, LocalDate from, LocalDate to) {
        requireVendor(vendorId);
        return inventory.vendorPurchases(vendorId, from, to).stream()
                .map(line -> new VendorPurchaseRow(line.receiptId(), line.grnNumber(),
                        line.receiptDate(), line.invoiceNumber(), line.siteId(),
                        line.materialId(), line.materialCode(), line.materialName(),
                        line.unitCode(), line.quantity(), line.rate(), line.amount(),
                        line.received()))
                .toList();
    }

    // ------------------------------------------------------------------ internals

    private Vendor requireVendor(UUID vendorId) {
        return vendors.findById(vendorId)
                .filter(vendor -> vendor.getOrgId().equals(orgId()))
                .orElseThrow(() -> BusinessException.notFound("Vendor", vendorId));
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }
}

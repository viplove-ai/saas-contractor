package in.nirman.modules.expense.service;

import in.nirman.common.BusinessException;
import in.nirman.common.DocumentNumberService;
import in.nirman.common.PageResponse;
import in.nirman.common.PeriodLockGuard;
import in.nirman.modules.attachment.service.AttachmentLookup;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.expense.api.dto.CashDtos.ChargeToFloatRequest;
import in.nirman.modules.expense.api.dto.CashDtos.PaymentAttachmentResponse;
import in.nirman.modules.expense.api.dto.CashDtos.PaymentResponse;
import in.nirman.modules.expense.api.dto.CashDtos.RecordPaymentRequest;
import in.nirman.modules.expense.api.dto.CashDtos.VendorBalanceRow;
import in.nirman.modules.expense.domain.Expense;
import in.nirman.modules.expense.domain.Payment;
import in.nirman.modules.expense.domain.PaymentAttachment;
import in.nirman.modules.expense.domain.SiteAdvance;
import in.nirman.modules.expense.repository.ExpenseRepository;
import in.nirman.modules.expense.repository.PaymentAttachmentRepository;
import in.nirman.modules.expense.repository.PaymentRepository;
import in.nirman.modules.expense.repository.SiteAdvanceRepository;
import in.nirman.modules.masterdata.domain.Vendor;
import in.nirman.modules.masterdata.repository.VendorRepository;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
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
    private final PaymentAttachmentRepository proofs;
    private final ExpenseRepository expenses;
    private final VendorRepository vendors;
    private final SiteAdvanceRepository advances;
    private final DocumentNumberService documentNumbers;
    private final AttachmentLookup attachments;
    private final SiteAccessGuard siteAccessGuard;
    private final PeriodLockGuard periodLockGuard;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public PaymentService(PaymentRepository payments, PaymentAttachmentRepository proofs,
                          ExpenseRepository expenses, VendorRepository vendors,
                          SiteAdvanceRepository advances,
                          DocumentNumberService documentNumbers, AttachmentLookup attachments,
                          SiteAccessGuard siteAccessGuard, PeriodLockGuard periodLockGuard,
                          CurrentUserProvider currentUser, AuditService audit) {
        this.payments = payments;
        this.proofs = proofs;
        this.expenses = expenses;
        this.vendors = vendors;
        this.advances = advances;
        this.documentNumbers = documentNumbers;
        this.attachments = attachments;
        this.siteAccessGuard = siteAccessGuard;
        this.periodLockGuard = periodLockGuard;
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

        /*
          The proof, claimed in the same transaction that creates the payment it proves. If the
          claim fails — the file belongs to another record, or does not exist — the payment
          rolls back with it, which is right: a payment recorded without the screenshot the
          accountant thought he had attached is worse than one he has to record again, because
          only the second of those is visible to him.
        */
        if (request.attachmentId() != null) {
            attachments.claimFor(request.attachmentId(), payment.getId());
            proofs.save(new PaymentAttachment(payment.getId(), request.attachmentId(),
                    request.proofType()));
        }

        audit.record("PAYMENT", payment.getId(), "CREATE", null,
                Map.of("paymentNumber", number, "expenseNumber", expense.getExpenseNumber(),
                        "amount", request.amount(), "paidToDate", expense.getPaidAmount(),
                        "stillPayable", expense.payableAmount(),
                        "proofAttached", request.attachmentId() != null), request.remarks());
        return toResponse(payment);
    }

    /**
     * Settles an approved bill out of the float in somebody's pocket.
     *
     * <p><b>Why this is a payment and not a flag.</b> The supervisor handed the shopkeeper
     * cash an hour after the lorry arrived. The supplier <i>was</i> paid, so
     * {@code paid_amount} moves and the bill leaves the payable queue — recording it any other
     * way would leave the ageing report claiming the company owes a shopkeeper who was settled
     * a fortnight ago, and would leave the man it actually owes, its own supervisor, nowhere on
     * the books at all. What changes is only where the cash came from, which is what
     * {@code payments.site_advance_id} says.</p>
     *
     * <p><b>Whose decision it is.</b> {@code payment:record} — the accountant's and the
     * administrator's, the same permission and the same people as recording a bank transfer,
     * because it is the same question answered the other way: this bill is settled, and here is
     * what settled it. No new permission was minted, for the reason the allocation minted none
     * on the approval: choosing between the two ways of settling a bill is part of settling
     * it.</p>
     *
     * <p><b>It may overdraw the float, and that is the point.</b> A supervisor holding ₹5,000
     * who buys ₹7,000 of steel is owed ₹2,000, and V49 removed the check that made that
     * unrecordable. What is refused is charging a bill to a float at a different site, to a
     * cancelled one, or to a period that is closed.</p>
     */
    @PreAuthorize("hasAuthority('payment:record')")
    public PaymentResponse chargeToFloat(UUID expenseId, ChargeToFloatRequest request) {
        Expense expense = expenses.findByIdAndOrgId(expenseId, orgId())
                .orElseThrow(() -> BusinessException.notFound("Expense", expenseId));
        siteAccessGuard.assertCanAccess(expense.getSiteId());

        if (expense.getWorkflowStatus() != Expense.Workflow.APPROVED) {
            throw new BusinessException("payment.expense-not-approved",
                    "Expense %s is %s. Nothing is settled until it is approved."
                            .formatted(expense.getExpenseNumber(),
                                    expense.getWorkflowStatus().name().toLowerCase()
                                            .replace('_', ' ')));
        }
        BigDecimal payable = expense.payableAmount();
        if (payable.signum() <= 0) {
            throw new BusinessException("payment.nothing-payable",
                    "Nothing is left owing on " + expense.getExpenseNumber() + ".");
        }
        periodLockGuard.assertOpen(expense.getSiteId(), request.paymentDate(),
                PeriodLockGuard.Module.EXPENSE);

        SiteAdvance advance = floatFor(request.holderUserId(), expense.getSiteId());

        String number = documentNumbers.next(orgId(), DocumentNumberService.DocType.PAYMENT,
                request.paymentDate());
        Payment payment = new Payment(orgId(), expense.getProjectId(), expense.getSiteId(),
                expense.getId(), expense.getVendorId(), number, request.paymentDate(),
                payable, "SITE_FLOAT");
        payment.fundedByFloat(advance.getId());
        payment.setRemarks(request.remarks());
        payments.save(payment);

        expense.addPayment(payable);
        expense.setSiteAdvanceId(advance.getId());
        advance.charge(payable, Instant.now());

        audit.record("PAYMENT", payment.getId(), "CHARGE_TO_FLOAT", null,
                Map.of("paymentNumber", number, "expenseNumber", expense.getExpenseNumber(),
                        "advanceNumber", advance.getAdvanceNumber(),
                        "holder", advance.getIssuedToUserId().toString(),
                        "amount", payable, "floatBalance", advance.outstanding()),
                request.remarks());
        return toResponse(payment);
    }

    /**
     * Which of a holder's floats a bill comes out of.
     *
     * <p>Oldest first among those with something left, so the money he has been carrying
     * longest is the money accounted for first — that is what makes the age of an open float
     * mean anything. When every one of them is spent the charge falls to his most recent, which
     * is the case that overdraws it and puts the company on the owing side. That is deliberately
     * not an error: it is the ordinary event of a man buying at a gate with more than he was
     * given, and the only thing worse than recording it is not recording it.</p>
     *
     * <p>Locked as it is read. Two bills charged to one float at the same moment must not both
     * start from the same balance — the same reason {@code applyApprovedSettlement} locks, and
     * it matters more here, because here the balance may go negative and a lost update would
     * simply be wrong rather than refused.</p>
     */
    private SiteAdvance floatFor(UUID holderUserId, UUID siteId) {
        List<SiteAdvance> held = advances.findLive(orgId(), siteId, holderUserId,
                false, List.of());
        SiteAdvance chosen = held.stream()
                .filter(row -> row.outstanding().signum() > 0)
                .findFirst()
                .orElseGet(() -> held.isEmpty() ? null : held.get(held.size() - 1));
        if (chosen == null) {
            throw new BusinessException("float.none",
                    "No float has been handed to that person at this site. Hand one over from "
                            + "the treasury register first, then charge the bill to it.");
        }
        // Re-read under a write lock: the list above was not locked, and the balance this
        // charge is about to move is exactly what another charge could be moving now.
        return advances.findForUpdate(chosen.getId())
                .orElseThrow(() -> BusinessException.notFound("Site advance", chosen.getId()));
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
                payment.getReconciledAt(), payment.getSiteAdvanceId(),
                payment.getSiteAdvanceId() == null ? null
                        : advances.findById(payment.getSiteAdvanceId())
                                .map(SiteAdvance::getAdvanceNumber).orElse(null),
                payment.getVersion(), proofsFor(payment.getId()));
    }

    /**
     * What proves this payment, named so the register can draw it.
     *
     * <p>A link whose file has gone comes back with its name blank rather than being dropped:
     * "there was a receipt and it cannot be found" is a different fact from "there was never
     * one", and the second is the one an accountant would wrongly conclude from a silent
     * omission. The same choice {@code ExpenseResponses} makes about a bill.</p>
     */
    private List<PaymentAttachmentResponse> proofsFor(UUID paymentId) {
        List<PaymentAttachment> links = proofs.findByPaymentId(paymentId);
        if (links.isEmpty()) {
            return List.of();
        }
        return links.stream().map(link -> {
            AttachmentLookup.FileInfo file = fileOrNull(link.getAttachmentId());
            return new PaymentAttachmentResponse(link.getId(), link.getAttachmentId(),
                    link.getDocType(), file == null ? null : file.fileName(),
                    file == null ? null : file.contentType());
        }).toList();
    }

    private AttachmentLookup.FileInfo fileOrNull(UUID attachmentId) {
        try {
            return attachments.require(attachmentId);
        } catch (BusinessException missing) {
            return null;
        }
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }
}

package in.nirman.modules.labour.service;

import in.nirman.common.BusinessException;
import in.nirman.common.DocumentNumberService;
import in.nirman.common.PageResponse;
import in.nirman.common.PeriodLockGuard;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.labour.api.dto.AdvanceDtos.CreatePaymentRequest;
import in.nirman.modules.labour.api.dto.AdvanceDtos.PaymentResponse;
import in.nirman.modules.labour.domain.Worker;
import in.nirman.modules.labour.domain.WorkerAdvance;
import in.nirman.modules.labour.domain.WorkerBalance;
import in.nirman.modules.labour.domain.WorkerPayment;
import in.nirman.modules.labour.repository.WorkerAdvanceRepository;
import in.nirman.modules.labour.repository.WorkerBalanceRepository;
import in.nirman.modules.labour.repository.WorkerPaymentRepository;
import in.nirman.modules.labour.repository.WorkerRepository;
import in.nirman.modules.project.service.SiteLookup;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The payday: wages handed to a worker against what the ledger says he is owed.
 *
 * <p>Two things happen in one transaction. The payment is posted to the ledger, which is the
 * first time anything has written its {@code PAYMENT} entry type; and every open recoverable
 * advance the man's wages have by then covered is marked recovered, oldest first. The ledger
 * had already netted those advances out of his balance the day each was approved — what it
 * could not say was <em>which payday</em> settled them, and the advance row's own
 * {@code recovered_amount} and status sat at zero for ever because nothing recorded one.</p>
 *
 * <p><b>He cannot be paid more than he is owed.</b> A figure past the ledger's net payable is
 * money handed over against wages not yet earned, which is what an advance is, and there is
 * a register for that. Refusing it here keeps the two registers from telling one story in
 * two places.</p>
 *
 * <p>What a payday can recover is what the wages have covered: earned, less deductions, less
 * everything paid out in cash including this payment, less what earlier paydays already
 * recovered. Under the ceiling above that always covers every open advance in full — the
 * arithmetic is kept general rather than collapsed to "close them all" so that a partial
 * recovery is a figure the row can carry if the ceiling ever moves, and so the status on the
 * advance always follows from its own numbers.</p>
 */
@Service
@Transactional
public class WorkerPaymentService {

    private final WorkerPaymentRepository payments;
    private final WorkerAdvanceRepository advances;
    private final WorkerRepository workers;
    private final WorkerBalanceRepository balances;
    private final WorkerLedgerService ledger;
    private final SiteLookup sites;
    private final SiteAccessGuard siteAccessGuard;
    private final PeriodLockGuard periodLockGuard;
    private final DocumentNumberService documentNumbers;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public WorkerPaymentService(WorkerPaymentRepository payments, WorkerAdvanceRepository advances,
                                WorkerRepository workers, WorkerBalanceRepository balances,
                                WorkerLedgerService ledger, SiteLookup sites,
                                SiteAccessGuard siteAccessGuard, PeriodLockGuard periodLockGuard,
                                DocumentNumberService documentNumbers,
                                CurrentUserProvider currentUser, AuditService audit) {
        this.payments = payments;
        this.advances = advances;
        this.workers = workers;
        this.balances = balances;
        this.ledger = ledger;
        this.sites = sites;
        this.siteAccessGuard = siteAccessGuard;
        this.periodLockGuard = periodLockGuard;
        this.documentNumbers = documentNumbers;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('wage:read')")
    public PageResponse<PaymentResponse> list(UUID siteId, UUID workerId, LocalDate from,
                                              LocalDate to, Pageable pageable) {
        if (siteId != null) {
            siteAccessGuard.assertCanAccess(siteId);
        }
        boolean restricted = !currentUser.seesAllSites();
        Collection<UUID> visible = restricted ? currentUser.assignedSiteIds() : List.of();
        if (restricted && visible.isEmpty()) {
            return new PageResponse<>(List.of(), 0, pageable.getPageSize(), 0, 0, true, true);
        }
        return PageResponse.from(
                payments.search(orgId(), siteId, workerId, from, to, restricted, visible, pageable),
                this::toResponse);
    }

    /**
     * Records a payday. Idempotent on the client id: the same hand-over synced twice is one
     * row and one ledger entry, which is the guarantee the advance already gives.
     */
    @PreAuthorize("hasAuthority('payment:record')")
    public PaymentResponse pay(CreatePaymentRequest request) {
        siteAccessGuard.assertCanAccess(request.siteId());
        periodLockGuard.assertOpen(request.siteId(), request.paymentDate(),
                PeriodLockGuard.Module.EXPENSE);

        var existing = payments.findByIdAndOrgId(request.id(), orgId());
        if (existing.isPresent()) {
            return toResponse(existing.get());   // the offline replay
        }

        Worker worker = requireWorker(request.workerId());
        SiteLookup.SiteInfo site = sites.require(request.siteId());

        // Read under the same lock the ledger will take, so two paydays typed at once for
        // one man cannot both be checked against the balance before either has moved it.
        // A man with no balance row has earned nothing, and is refused below on the figure.
        WorkerBalance balance = balances.findForUpdate(worker.getId())
                .orElseGet(() -> new WorkerBalance(orgId(), worker.getId()));
        BigDecimal owed = balance.getNetPayable();
        if (request.amount().compareTo(owed) > 0) {
            throw new BusinessException("payment.exceeds-payable",
                    owed.signum() > 0
                            ? "He is owed ₹" + owed.toPlainString() + ". Anything beyond that is "
                                    + "an advance against wages not yet earned — record it as one."
                            : "Nothing is owed to him yet. Money handed over before the wages "
                                    + "are earned is an advance — record it as one.");
        }

        String number = documentNumbers.next(orgId(),
                DocumentNumberService.DocType.WORKER_PAYMENT, request.paymentDate());
        WorkerPayment payment = new WorkerPayment(request.id(), orgId(), site.projectId(),
                site.id(), worker.getId(), number, request.paymentDate(), request.amount());
        payment.setPaymentMode(request.paymentMode());
        payment.setReferenceNumber(blankToNull(request.referenceNumber()));
        payment.setRemarks(blankToNull(request.remarks()));

        ledger.postPayment(payment.getOrgId(), payment.getProjectId(), payment.getSiteId(),
                payment.getWorkerId(), payment.getPaymentDate(), payment.getAmount(),
                payment.getId(), "Wages paid, " + number);

        // The same managed row the ledger has just moved: it existed, or the figure above
        // would have refused the payment.
        BigDecimal recovered = recoverAdvances(worker.getId(), balance);
        payment.setAdvancesRecovered(recovered);
        payments.save(payment);

        audit.record("WORKER_PAYMENT", payment.getId(), "CREATE", null,
                Map.of("paymentNumber", number, "workerId", worker.getId().toString(),
                        "amount", request.amount(), "advancesRecovered", recovered), null);
        return toResponse(payment);
    }

    /**
     * Closes the man's open advances, oldest first, out of what his wages have covered.
     *
     * @param balance the ledger balance <em>after</em> this payment was posted
     * @return what this payday recovered in total
     */
    private BigDecimal recoverAdvances(UUID workerId, WorkerBalance balance) {
        BigDecimal alreadyRecovered = advances
                .findByWorkerIdAndWorkflowStatusAndRecoverableTrue(workerId, WorkerAdvance.Workflow.APPROVED)
                .stream()
                .map(WorkerAdvance::getRecoveredAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Earnings not handed over as cash and not already used to close an earlier advance.
        BigDecimal coverage = balance.getEarnedAmount()
                .subtract(balance.getDeductionAmount())
                .subtract(balance.getPaidAmount())
                .subtract(alreadyRecovered);

        BigDecimal recovered = BigDecimal.ZERO;
        for (WorkerAdvance advance : advances.findOpenForRecovery(workerId)) {
            if (coverage.signum() <= 0) {
                break;
            }
            BigDecimal portion = advance.outstanding().min(coverage);
            advance.recover(portion);
            advances.save(advance);
            coverage = coverage.subtract(portion);
            recovered = recovered.add(portion);
        }
        return recovered;
    }

    // ------------------------------------------------------------------ internals

    private Worker requireWorker(UUID id) {
        return workers.findByIdAndOrgIdAndDeletedAtIsNull(id, orgId())
                .orElseThrow(() -> BusinessException.notFound("Worker", id));
    }

    private PaymentResponse toResponse(WorkerPayment p) {
        String workerName = workers.findById(p.getWorkerId())
                .map(Worker::getFullName).orElse(null);
        return new PaymentResponse(p.getId(), p.getPaymentNumber(), p.getSiteId(), p.getWorkerId(),
                workerName, p.getPaymentDate(), p.getAmount(), p.getPaymentMode(),
                p.getReferenceNumber(), p.getAdvancesRecovered(), p.getRemarks(), p.getVersion());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }
}

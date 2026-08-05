package in.nirman.modules.labour.service;

import in.nirman.common.BusinessException;
import in.nirman.common.DocumentNumberService;
import in.nirman.common.PageResponse;
import in.nirman.common.PeriodLockGuard;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.labour.api.dto.AdvanceDtos.AdvanceResponse;
import in.nirman.modules.labour.api.dto.AdvanceDtos.ApproveAdvanceRequest;
import in.nirman.modules.labour.api.dto.AdvanceDtos.CreateAdvanceRequest;
import in.nirman.modules.labour.api.dto.AdvanceDtos.LedgerEntryResponse;
import in.nirman.modules.labour.api.dto.AdvanceDtos.SettlementResponse;
import in.nirman.modules.labour.domain.WorkerAdvance;
import in.nirman.modules.labour.domain.WorkerBalance;
import in.nirman.modules.labour.domain.WorkerLedgerEntry;
import in.nirman.modules.labour.domain.Worker;
import in.nirman.modules.labour.repository.WorkerAdvanceRepository;
import in.nirman.modules.labour.repository.WorkerBalanceRepository;
import in.nirman.modules.labour.repository.WorkerLedgerEntryRepository;
import in.nirman.modules.labour.repository.WorkerRepository;
import in.nirman.modules.project.service.SiteLookup;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Advances against wages, and the settlement view they feed.
 *
 * <p>An advance only reaches the ledger when it is <b>approved</b>. Recording one is a
 * statement that money changed hands; approving it is the decision that it comes out of the
 * man's wages. Posting on creation would let an unapproved draft quietly reduce what he is
 * owed.</p>
 */
@Service
@Transactional
public class WorkerAdvanceService {

    private final WorkerAdvanceRepository advances;
    private final WorkerRepository workers;
    private final WorkerBalanceRepository balances;
    private final WorkerLedgerEntryRepository ledgerEntries;
    private final WorkerLedgerService ledger;
    private final SiteLookup sites;
    private final SiteAccessGuard siteAccessGuard;
    private final PeriodLockGuard periodLockGuard;
    private final DocumentNumberService documentNumbers;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public WorkerAdvanceService(WorkerAdvanceRepository advances, WorkerRepository workers,
                                WorkerBalanceRepository balances,
                                WorkerLedgerEntryRepository ledgerEntries,
                                WorkerLedgerService ledger, SiteLookup sites,
                                SiteAccessGuard siteAccessGuard, PeriodLockGuard periodLockGuard,
                                DocumentNumberService documentNumbers,
                                CurrentUserProvider currentUser, AuditService audit) {
        this.advances = advances;
        this.workers = workers;
        this.balances = balances;
        this.ledgerEntries = ledgerEntries;
        this.ledger = ledger;
        this.sites = sites;
        this.siteAccessGuard = siteAccessGuard;
        this.periodLockGuard = periodLockGuard;
        this.documentNumbers = documentNumbers;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('worker:read')")
    public PageResponse<AdvanceResponse> list(UUID siteId, UUID workerId, LocalDate from,
                                              LocalDate to, Pageable pageable) {
        if (siteId != null) {
            siteAccessGuard.assertCanAccess(siteId);
        }
        // With no site asked for, a site-scoped role still sees only their own sites' money.
        boolean restricted = !currentUser.seesAllSites();
        Collection<UUID> visible = restricted ? currentUser.assignedSiteIds() : List.of();
        if (restricted && visible.isEmpty()) {
            return new PageResponse<>(List.of(), 0, pageable.getPageSize(), 0, 0, true, true);
        }
        return PageResponse.from(
                advances.search(orgId(), siteId, workerId, from, to, restricted, visible, pageable),
                this::toResponse);
    }

    /**
     * Records an advance. Idempotent on the client-generated id, so an advance handed over
     * on site and synced three times is one row.
     */
    @PreAuthorize("hasAuthority('advance:issue')")
    public AdvanceResponse create(CreateAdvanceRequest request) {
        siteAccessGuard.assertCanAccess(request.siteId());
        periodLockGuard.assertOpen(request.siteId(), request.advanceDate(),
                PeriodLockGuard.Module.EXPENSE);

        var existing = advances.findByIdAndOrgId(request.id(), orgId());
        if (existing.isPresent()) {
            return toResponse(existing.get());   // the offline replay
        }

        Worker worker = requireWorker(request.workerId());
        SiteLookup.SiteInfo site = sites.require(request.siteId());
        String number = documentNumbers.next(orgId(),
                DocumentNumberService.DocType.WORKER_ADVANCE, request.advanceDate());

        WorkerAdvance advance = new WorkerAdvance(request.id(), orgId(), site.projectId(),
                site.id(), worker.getId(), number, request.advanceDate(), request.amount());
        advance.setPaymentMode(request.paymentMode());
        advance.setPurpose(request.purpose());
        advance.setRecoverable(request.recoverable());
        advance.setRemarks(request.remarks());
        advances.save(advance);

        audit.record("WORKER_ADVANCE", advance.getId(), "CREATE", null,
                Map.of("advanceNumber", number, "workerId", worker.getId().toString(),
                        "amount", request.amount(), "recoverable", request.recoverable()), null);
        return toResponse(advance);
    }

    /**
     * Approving posts the advance to the worker's ledger — but only if it is recoverable.
     * Ration or footwear the contractor has decided to bear is still recorded as having
     * been handed over; it simply is not deducted from what he is owed.
     */
    @PreAuthorize("hasAuthority('advance:settle:approve')")
    public AdvanceResponse decide(UUID id, ApproveAdvanceRequest request) {
        WorkerAdvance advance = advances.findByIdAndOrgId(id, orgId())
                .orElseThrow(() -> BusinessException.notFound("Worker advance", id));
        siteAccessGuard.assertCanAccess(advance.getSiteId());
        periodLockGuard.assertOpen(advance.getSiteId(), advance.getAdvanceDate(),
                PeriodLockGuard.Module.EXPENSE);

        if (advance.getWorkflowStatus() != WorkerAdvance.Workflow.DRAFT
                && advance.getWorkflowStatus() != WorkerAdvance.Workflow.SUBMITTED) {
            throw new BusinessException("advance.already-decided",
                    "Advance " + advance.getAdvanceNumber() + " is already "
                            + advance.getWorkflowStatus().name().toLowerCase() + ".");
        }

        Instant now = Instant.now();
        UUID by = currentUser.currentUserIdOrNull();
        if (request.action() == ApproveAdvanceRequest.Action.REJECT) {
            advance.reject(now, by, request.remarks());
        } else {
            advance.approve(now, by);
            if (advance.isRecoverable()) {
                ledger.postAdvance(advance.getOrgId(), advance.getProjectId(), advance.getSiteId(),
                        advance.getWorkerId(), advance.getAdvanceDate(), advance.getAmount(),
                        advance.getId(), advance.getPurpose());
            }
        }
        audit.record("WORKER_ADVANCE", id, request.action().name(), null,
                Map.of("advanceNumber", advance.getAdvanceNumber(),
                        "postedToLedger", advance.isApproved() && advance.isRecoverable()),
                request.remarks());
        return toResponse(advance);
    }

    /**
     * The settlement sheet for one worker: what he earned, what he drew, what is left.
     * The ledger entries come with it so the figure can be argued with line by line, which
     * is exactly what happens when a man disputes his balance on payday.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('wage:read')")
    public SettlementResponse settlement(UUID workerId, LocalDate from, LocalDate to) {
        Worker worker = requireWorker(workerId);
        WorkerBalance balance = balances.findByWorkerId(workerId)
                .orElseGet(() -> new WorkerBalance(orgId(), workerId));

        List<LedgerEntryResponse> entries = ledgerEntries.findForWorker(workerId, from, to).stream()
                .map(WorkerAdvanceService::toResponse)
                .toList();

        return new SettlementResponse(worker.getId(), worker.getWorkerCode(), worker.getFullName(),
                balance.getEarnedAmount(), balance.getAdvanceAmount(), balance.getPaidAmount(),
                balance.getDeductionAmount(), balance.getNetPayable(), balance.getLastEntryAt(),
                entries);
    }

    // ------------------------------------------------------------------ internals

    private Worker requireWorker(UUID id) {
        return workers.findByIdAndOrgIdAndDeletedAtIsNull(id, orgId())
                .orElseThrow(() -> BusinessException.notFound("Worker", id));
    }

    private AdvanceResponse toResponse(WorkerAdvance a) {
        String workerName = workers.findById(a.getWorkerId())
                .map(Worker::getFullName).orElse(null);
        return new AdvanceResponse(a.getId(), a.getAdvanceNumber(), a.getSiteId(), a.getWorkerId(),
                workerName, a.getAdvanceDate(), a.getAmount(), a.getPaymentMode(), a.getPurpose(),
                a.isRecoverable(), a.getRecoveredAmount(), a.getBalanceAmount(), a.getStatus(),
                a.getWorkflowStatus(), a.getApprovedAt(), a.getRemarks(), a.getVersion());
    }

    private static LedgerEntryResponse toResponse(WorkerLedgerEntry e) {
        return new LedgerEntryResponse(e.getId(), e.getEntryDate(), e.getPeriodYearMonth(),
                e.getEntryType(), e.getDirection(), e.getAmount(), e.getBalanceAfter(),
                e.getSourceType(), e.getSourceId(), e.getReason());
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }
}

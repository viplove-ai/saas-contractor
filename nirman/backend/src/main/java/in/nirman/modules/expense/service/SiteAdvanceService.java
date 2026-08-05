package in.nirman.modules.expense.service;

import in.nirman.common.BusinessException;
import in.nirman.common.DocumentNumberService;
import in.nirman.common.PageResponse;
import in.nirman.common.PeriodLockGuard;
import in.nirman.modules.approval.service.ApprovalEngine;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.expense.api.dto.CashDtos.AdvanceResponse;
import in.nirman.modules.expense.api.dto.CashDtos.IssueAdvanceRequest;
import in.nirman.modules.expense.api.dto.CashDtos.SettlementLineResponse;
import in.nirman.modules.expense.api.dto.CashDtos.SettlementResponse;
import in.nirman.modules.expense.api.dto.CashDtos.SubmitSettlementRequest;
import in.nirman.modules.expense.domain.AdvanceSettlement;
import in.nirman.modules.expense.domain.AdvanceSettlementExpense;
import in.nirman.modules.expense.domain.Expense;
import in.nirman.modules.expense.domain.SiteAdvance;
import in.nirman.modules.expense.repository.AdvanceSettlementExpenseRepository;
import in.nirman.modules.expense.repository.AdvanceSettlementRepository;
import in.nirman.modules.expense.repository.ExpenseRepository;
import in.nirman.modules.expense.repository.SiteAdvanceRepository;
import in.nirman.modules.identity.repository.UserRepository;
import in.nirman.modules.project.service.SiteLookup;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Petty cash issued to staff, and the accounting for it afterwards.
 *
 * <p>A site advance is a float in somebody's pocket. What they owe back is the float less
 * the bills they produce and the cash they hand over, and the whole point of the settlement
 * flow is that the holder cannot clear their own balance by asserting it: they submit, the
 * office approves, and only then does the advance move.</p>
 *
 * <p>The approval goes through the same engine as an expense, because it is the same kind of
 * decision and a second mechanism is what docs/09 open question 2 ruled against. The
 * settlement's status column is written by {@link ExpenseApprovalListener} on the engine's
 * event, and the effect on the advance is applied there too.</p>
 */
@Service
@Transactional
public class SiteAdvanceService {

    /** The document type the approval engine routes settlements by. */
    public static final String SETTLEMENT_ENTITY_TYPE = "ADVANCE_SETTLEMENT";

    private final SiteAdvanceRepository advances;
    private final AdvanceSettlementRepository settlements;
    private final AdvanceSettlementExpenseRepository settlementLines;
    private final ExpenseRepository expenses;
    private final ApprovalEngine approvals;
    private final UserRepository users;
    private final SiteLookup sites;
    private final SiteAccessGuard siteAccessGuard;
    private final PeriodLockGuard periodLockGuard;
    private final DocumentNumberService documentNumbers;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public SiteAdvanceService(SiteAdvanceRepository advances,
                              AdvanceSettlementRepository settlements,
                              AdvanceSettlementExpenseRepository settlementLines,
                              ExpenseRepository expenses, ApprovalEngine approvals,
                              UserRepository users, SiteLookup sites,
                              SiteAccessGuard siteAccessGuard, PeriodLockGuard periodLockGuard,
                              DocumentNumberService documentNumbers,
                              CurrentUserProvider currentUser, AuditService audit) {
        this.advances = advances;
        this.settlements = settlements;
        this.settlementLines = settlementLines;
        this.expenses = expenses;
        this.approvals = approvals;
        this.users = users;
        this.sites = sites;
        this.siteAccessGuard = siteAccessGuard;
        this.periodLockGuard = periodLockGuard;
        this.documentNumbers = documentNumbers;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ advances

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('expense:read')")
    public PageResponse<AdvanceResponse> list(UUID siteId, UUID userId,
                                              SiteAdvance.SettlementStatus status,
                                              Pageable pageable) {
        if (siteId != null) {
            siteAccessGuard.assertCanAccess(siteId);
        }
        boolean restricted = !currentUser.seesAllSites();
        Collection<UUID> visible = restricted ? currentUser.assignedSiteIds() : List.of();
        if (restricted && visible.isEmpty()) {
            return new PageResponse<>(List.of(), 0, pageable.getPageSize(), 0, 0, true, true);
        }
        return PageResponse.from(
                advances.search(orgId(), siteId, userId, status, restricted, visible, pageable),
                this::toResponse);
    }

    /**
     * Hands a float to a member of staff.
     *
     * <p>{@code advance:issue} is the accountant's and the administrator's. A supervisor
     * cannot issue himself petty cash, which is the entire control.</p>
     */
    @PreAuthorize("hasAuthority('advance:issue')")
    public AdvanceResponse issue(IssueAdvanceRequest request) {
        SiteLookup.SiteInfo site = sites.require(request.siteId());
        periodLockGuard.assertOpen(site.id(), request.advanceDate(),
                PeriodLockGuard.Module.EXPENSE);
        users.findByIdAndOrgId(request.issuedToUserId(), orgId())
                .orElseThrow(() -> BusinessException.notFound("User", request.issuedToUserId()));

        String number = documentNumbers.next(orgId(),
                DocumentNumberService.DocType.SITE_ADVANCE, request.advanceDate());
        SiteAdvance advance = new SiteAdvance(orgId(), site.projectId(), site.id(), number,
                request.issuedToUserId(), request.advanceDate(), request.amount(),
                request.paymentMode(), request.purpose());
        advance.setReferenceNumber(request.referenceNumber());
        advance.setRemarks(request.remarks());
        advance.setIssuedBy(currentUser.currentUserIdOrNull());
        advances.save(advance);

        audit.record("SITE_ADVANCE", advance.getId(), "ISSUE", null,
                Map.of("advanceNumber", number, "issuedTo", request.issuedToUserId().toString(),
                        "amount", request.amount()), request.purpose());
        return toResponse(advance);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('expense:read')")
    public AdvanceResponse get(UUID id) {
        SiteAdvance advance = requireAdvance(id);
        siteAccessGuard.assertCanAccess(advance.getSiteId());
        return toResponse(advance);
    }

    /** Floats still outstanding, for the advance-balances report and the settle screen. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('expense:read')")
    public List<AdvanceResponse> openBalances(UUID siteId, UUID userId) {
        if (siteId != null) {
            siteAccessGuard.assertCanAccess(siteId);
        }
        return advances.findOpen(orgId(), siteId, userId).stream()
                .filter(advance -> currentUser.seesAllSites()
                        || siteAccessGuard.canAccess(advance.getSiteId()))
                .map(this::toResponse)
                .toList();
    }

    // ------------------------------------------------------------------ settlements

    /**
     * The holder accounting for the float: these are the bills, this is the cash back.
     *
     * <p>Nothing moves on the advance yet. Submitting is a claim; the office approving it is
     * what makes it true, and reducing the float here would let a supervisor clear his own
     * balance by asserting it.</p>
     */
    @PreAuthorize("hasAuthority('advance:settle:submit')")
    public SettlementResponse submitSettlement(UUID advanceId, SubmitSettlementRequest request) {
        SiteAdvance advance = requireAdvance(advanceId);
        siteAccessGuard.assertCanAccess(advance.getSiteId());
        periodLockGuard.assertOpen(advance.getSiteId(), request.settlementDate(),
                PeriodLockGuard.Module.EXPENSE);

        if (advance.getSettlementStatus() == SiteAdvance.SettlementStatus.SETTLED
                || advance.getSettlementStatus() == SiteAdvance.SettlementStatus.CANCELLED) {
            throw new BusinessException("advance.closed",
                    "Advance " + advance.getAdvanceNumber() + " is already "
                            + advance.getSettlementStatus().name().toLowerCase() + ".");
        }

        List<Expense> attached = resolveExpenses(request.expenseIds(), advance);
        BigDecimal expensesAmount = attached.stream().map(Expense::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal returned = request.returnedAmount() == null
                ? BigDecimal.ZERO : request.returnedAmount();
        BigDecimal cleared = expensesAmount.add(returned);

        if (cleared.signum() == 0) {
            throw new BusinessException("settlement.empty",
                    "A settlement needs bills, cash back, or both.");
        }
        if (cleared.compareTo(advance.outstanding()) > 0) {
            throw new BusinessException("settlement.exceeds-float",
                    "This settlement clears %s against a float of %s still outstanding."
                            .formatted(cleared.toPlainString(),
                                    advance.outstanding().toPlainString()));
        }

        String number = documentNumbers.next(orgId(), DocumentNumberService.DocType.SETTLEMENT,
                request.settlementDate());
        AdvanceSettlement settlement = new AdvanceSettlement(orgId(), advance.getId(), number,
                request.settlementDate(), expensesAmount, returned,
                currentUser.currentUserIdOrNull());
        settlement.setRemarks(request.remarks());
        settlements.save(settlement);

        List<AdvanceSettlementExpense> lines = new ArrayList<>();
        for (Expense expense : attached) {
            lines.add(new AdvanceSettlementExpense(settlement.getId(), expense.getId(),
                    expense.getTotalAmount()));
            expense.setSiteAdvanceId(advance.getId());
        }
        settlementLines.saveAll(lines);

        approvals.submit(new ApprovalEngine.Request(SETTLEMENT_ENTITY_TYPE, settlement.getId(),
                advance.getSiteId(), cleared, AdvanceSettlement.Status.SUBMITTED.name()));

        audit.record("ADVANCE_SETTLEMENT", settlement.getId(), "SUBMIT", null,
                Map.of("settlementNumber", number, "advanceNumber", advance.getAdvanceNumber(),
                        "expensesAmount", expensesAmount, "returnedAmount", returned,
                        "bills", attached.size()), request.remarks());
        return toResponse(settlement);
    }

    /**
     * Applies an approved settlement to the float it clears.
     *
     * <p>Called only by {@link ExpenseApprovalListener}, inside the deciding transaction.
     * The advance row is locked first, because two settlements approved at the same moment
     * against one float must not both read the same starting balance — that is how ₹20,000
     * of petty cash clears ₹30,000 of bills.</p>
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void applyApprovedSettlement(AdvanceSettlement settlement) {
        SiteAdvance advance = advances.findForUpdate(settlement.getAdvanceId())
                .orElseThrow(() -> BusinessException.notFound("Site advance",
                        settlement.getAdvanceId()));
        if (settlement.clearedAmount().compareTo(advance.outstanding()) > 0) {
            throw new BusinessException("settlement.exceeds-float",
                    "The float has moved since this settlement was raised and no longer covers "
                            + "it. Reject it and have a fresh one submitted.");
        }
        advance.settle(settlement.getExpensesAmount(), settlement.getReturnedAmount(),
                Instant.now());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('expense:read')")
    public SettlementResponse getSettlement(UUID id) {
        AdvanceSettlement settlement = settlements.findByIdAndOrgId(id, orgId())
                .orElseThrow(() -> BusinessException.notFound("Settlement", id));
        SiteAdvance advance = requireAdvance(settlement.getAdvanceId());
        siteAccessGuard.assertCanAccess(advance.getSiteId());
        return toResponse(settlement);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('expense:read')")
    public List<SettlementResponse> settlementsFor(UUID advanceId) {
        SiteAdvance advance = requireAdvance(advanceId);
        siteAccessGuard.assertCanAccess(advance.getSiteId());
        return settlements.findByAdvanceIdOrderBySettlementDateAsc(advanceId).stream()
                .map(this::toResponse)
                .toList();
    }

    // ------------------------------------------------------------------ internals

    /**
     * The bills being claimed. Each must be approved, at the advance's own site, and not
     * already claimed against another float — the last of which
     * {@code uq_ase_expense_settled_once} (V8) also enforces, because the same ₹8,000 bill
     * on two settlements clears ₹16,000 of somebody's cash.
     */
    private List<Expense> resolveExpenses(List<UUID> expenseIds, SiteAdvance advance) {
        if (expenseIds == null || expenseIds.isEmpty()) {
            return List.of();
        }
        List<Expense> found = expenses.findByIdInAndOrgId(expenseIds, orgId());
        if (found.size() != Set.copyOf(expenseIds).size()) {
            throw BusinessException.notFound("Expense", "one or more of the ids given");
        }
        for (Expense expense : found) {
            if (expense.getWorkflowStatus() != Expense.Workflow.APPROVED) {
                throw new BusinessException("settlement.expense-not-approved",
                        "Expense %s is %s and cannot be claimed against a float yet."
                                .formatted(expense.getExpenseNumber(),
                                        expense.getWorkflowStatus().name().toLowerCase()));
            }
            if (!expense.getSiteId().equals(advance.getSiteId())) {
                throw new BusinessException("settlement.expense-other-site",
                        "Expense " + expense.getExpenseNumber()
                                + " belongs to a different site from this advance.");
            }
            if (settlementLines.isAlreadySettled(expense.getId())) {
                throw BusinessException.conflict("settlement.expense-already-settled",
                        "Expense " + expense.getExpenseNumber()
                                + " has already been claimed against a float.");
            }
        }
        return found;
    }

    private SiteAdvance requireAdvance(UUID id) {
        return advances.findByIdAndOrgId(id, orgId())
                .orElseThrow(() -> BusinessException.notFound("Site advance", id));
    }

    private AdvanceResponse toResponse(SiteAdvance advance) {
        String holder = users.findById(advance.getIssuedToUserId())
                .map(user -> user.getFullName()).orElse(null);
        return new AdvanceResponse(advance.getId(), advance.getAdvanceNumber(),
                advance.getSiteId(), advance.getIssuedToUserId(), holder,
                advance.getAdvanceDate(), advance.getAmount(), advance.getPaymentMode(),
                advance.getReferenceNumber(), advance.getPurpose(), advance.getAdjustedAmount(),
                advance.getReturnedAmount(), advance.getBalanceAmount(),
                advance.getSettlementStatus(), advance.getClosedAt(), advance.getRemarks(),
                advance.getVersion());
    }

    private SettlementResponse toResponse(AdvanceSettlement settlement) {
        var pending = approvals.findPendingOrNull(SETTLEMENT_ENTITY_TYPE, settlement.getId());
        String advanceNumber = advances.findById(settlement.getAdvanceId())
                .map(SiteAdvance::getAdvanceNumber).orElse(null);
        List<SettlementLineResponse> lines = settlementLines
                .findBySettlement(settlement.getId()).stream()
                .map(line -> expenses.findById(line.getExpenseId())
                        .map(expense -> new SettlementLineResponse(expense.getId(),
                                expense.getExpenseNumber(), expense.getDescription(),
                                expense.getExpenseDate(), line.getAmount()))
                        .orElseGet(() -> new SettlementLineResponse(line.getExpenseId(), null,
                                null, null, line.getAmount())))
                .toList();

        return new SettlementResponse(settlement.getId(), settlement.getSettlementNumber(),
                settlement.getAdvanceId(), advanceNumber, settlement.getSettlementDate(),
                settlement.getExpensesAmount(), settlement.getReturnedAmount(),
                settlement.clearedAmount(), settlement.getStatus(),
                pending == null ? null : pending.getLevel(),
                pending == null ? null : pending.getAssignedRole(),
                settlement.getApprovedAt(), settlement.getRejectionReason(),
                settlement.getRemarks(), settlement.getVersion(), lines);
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }
}

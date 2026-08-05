package in.nirman.modules.expense.service;

import in.nirman.modules.approval.domain.Approval;
import in.nirman.modules.approval.service.ApprovalDecided;
import in.nirman.modules.expense.domain.AdvanceSettlement;
import in.nirman.modules.expense.domain.Expense;
import in.nirman.modules.expense.repository.AdvanceSettlementRepository;
import in.nirman.modules.expense.repository.ExpenseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Translates an approval decision into what it means for an expense or a settlement.
 *
 * <p>This class is the whole of docs/09 open question 2 in practice. That question found two
 * approval systems coexisting — the generic {@code approvals} chain and a scattering of
 * per-module status columns — and ruled that the generic engine is authoritative and the
 * columns become <b>a denormalised cache written by the engine's event, never by a business
 * service</b>. So {@code Expense.applyDecision} is package-private and this is the only
 * caller. No service anywhere sets {@code L1_APPROVED} by hand, which is what stops the two
 * systems drifting the day somebody adds a third level.</p>
 *
 * <p>The mapping is the only place that knows an expense has an intermediate state at all.
 * The engine counted levels; this decides that being past level 1 of 2 is called
 * {@code L1_APPROVED} and that being past the last one is {@code APPROVED}.</p>
 *
 * <p>{@code MANDATORY} propagation, and a plain {@code @EventListener} rather than a
 * transactional one: this must run inside the deciding transaction. An event delivered after
 * commit would leave a window in which the chain says approved and the expense says
 * submitted, and a crash inside that window would make the disagreement permanent.</p>
 */
@Component
public class ExpenseApprovalListener {

    private static final Logger log = LoggerFactory.getLogger(ExpenseApprovalListener.class);

    private final ExpenseRepository expenses;
    private final AdvanceSettlementRepository settlements;
    private final SiteAdvanceService advances;

    public ExpenseApprovalListener(ExpenseRepository expenses,
                                   AdvanceSettlementRepository settlements,
                                   SiteAdvanceService advances) {
        this.expenses = expenses;
        this.settlements = settlements;
        this.advances = advances;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onDecision(ApprovalDecided event) {
        switch (event.entityType()) {
            case ExpenseService.ENTITY_TYPE -> applyToExpense(event);
            case SiteAdvanceService.SETTLEMENT_ENTITY_TYPE -> applyToSettlement(event);
            default -> { /* another module's record; not this listener's business */ }
        }
    }

    private void applyToExpense(ApprovalDecided event) {
        expenses.findById(event.entityId()).ifPresentOrElse(
                expense -> expense.applyDecision(statusFor(event), Instant.now(),
                        event.actionBy(), event.remarks()),
                () -> log.error("Approval decided on expense {} that no longer exists",
                        event.entityId()));
    }

    /**
     * Approved but not through: past one level and waiting on the next. That intermediate
     * state is why {@code expenses.workflow_status} has an {@code L1_APPROVED} at all, and
     * why it cannot simply mirror the approval row's own status.
     */
    private static Expense.Workflow statusFor(ApprovalDecided event) {
        if (event.outcome() == Approval.Status.APPROVED) {
            return event.finalLevel() ? Expense.Workflow.APPROVED : Expense.Workflow.L1_APPROVED;
        }
        return event.outcome() == Approval.Status.RETURNED
                ? Expense.Workflow.RETURNED
                : Expense.Workflow.REJECTED;
    }

    /**
     * A settlement approved all the way through is also the moment the float actually moves.
     * That happens here rather than in the submitting service for the same reason the status
     * does: the holder must not be able to reduce his own balance by claiming it, so the
     * reduction hangs off the decision and not off the claim.
     */
    private void applyToSettlement(ApprovalDecided event) {
        settlements.findById(event.entityId()).ifPresentOrElse(settlement -> {
            AdvanceSettlement.Status status = event.isFullyApproved()
                    ? AdvanceSettlement.Status.APPROVED
                    : event.isApproved()
                            ? AdvanceSettlement.Status.SUBMITTED   // past a level, still in flight
                            : AdvanceSettlement.Status.REJECTED;
            settlement.applyDecision(status, Instant.now(), event.actionBy(), event.remarks());
            if (status == AdvanceSettlement.Status.APPROVED) {
                advances.applyApprovedSettlement(settlement);
            }
        }, () -> log.error("Approval decided on settlement {} that no longer exists",
                event.entityId()));
    }
}

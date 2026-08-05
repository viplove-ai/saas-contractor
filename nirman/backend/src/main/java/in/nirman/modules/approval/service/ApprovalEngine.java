package in.nirman.modules.approval.service;

import in.nirman.modules.approval.domain.Approval;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The one approval engine, and the answer to docs/09 open question 2.
 *
 * <p>That question observed that two approval systems were coexisting: the generic
 * {@code approvals} + {@code approval_rules} pair docs/01 promises, and a scattering of
 * per-module columns — {@code expenses.workflow_status} with its {@code L1_APPROVED},
 * {@code advance_settlements.status}, {@code physical_stock_counts.approved_by}. The
 * recommendation was that <b>the generic engine is authoritative and the per-module columns
 * become a denormalised cache written by the engine's event, never by a business
 * service</b>.</p>
 *
 * <p>That is what this is. A business service asks the engine to raise a chain and then
 * stops thinking about approval; when a decision is made the engine publishes
 * {@link ApprovalDecided} and the module's listener writes its own status column. No
 * business service sets {@code L1_APPROVED} by hand, which is what stops the two systems
 * drifting apart the moment somebody adds a third level.</p>
 *
 * <p>The engine knows nothing about expenses. It knows amounts, levels, roles and sites,
 * and it hands the outcome back with enough context — which level, was it the last — for a
 * module that does understand expenses to decide what the record now says.</p>
 */
public interface ApprovalEngine {

    /**
     * @param amount null for a record with no money on it. A null amount matches every
     *               rule: bounding an approval by an amount that does not exist would skip
     *               it, and a mistake should not fail in the direction of nobody looking.
     */
    record Request(
            String entityType,
            UUID entityId,
            UUID siteId,
            BigDecimal amount,
            String currentStatus) {
    }

    /**
     * @param level        the level now waiting, or 0 when nothing is
     * @param assignedRole who it is waiting on
     * @param levels       how many levels this record will pass through in total
     */
    record Chain(int level, String assignedRole, int levels) {
    }

    /**
     * Raises the approval chain for a record and returns the level now waiting.
     *
     * <p>An organisation that has configured no rules gets one level assigned to ADMIN
     * rather than an automatic pass. Silence in a settings table must never read as
     * consent.</p>
     */
    Chain submit(Request request);

    /**
     * Records a decision on the level currently waiting, and raises the next one if the
     * record has further to go.
     *
     * @throws in.nirman.common.BusinessException 403 when the caller does not hold the role
     *         the level is assigned to, 404 when nothing is waiting
     */
    Approval act(UUID approvalId, Approval.Status outcome, String remarks);

    /** The decision waiting on a record, whichever level it is at. */
    Approval requirePending(String entityType, UUID entityId);

    /**
     * The same, for a screen that wants to show who is sitting on a record and must not
     * throw when the answer is nobody.
     */
    Approval findPendingOrNull(String entityType, UUID entityId);

    /**
     * Closes an open chain because the record went away — voided, withdrawn, cancelled.
     * The rows stay, marked {@code CANCELLED}: what somebody was asked to approve and never
     * got to is part of the history too.
     */
    void cancelChain(String entityType, UUID entityId, String reason);
}

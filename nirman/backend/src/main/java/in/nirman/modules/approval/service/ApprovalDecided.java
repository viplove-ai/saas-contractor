package in.nirman.modules.approval.service;

import in.nirman.modules.approval.domain.Approval;

import java.util.UUID;

/**
 * Published when a level decides, and the only way a business module learns the outcome.
 *
 * <p>This is the mechanism behind docs/09's ruling that per-module status columns are a
 * cache written by the engine's event rather than by a business service. The listener in
 * the owning module translates {@code level 1 of 2, APPROVED} into whatever that means for
 * its own record — {@code L1_APPROVED} for an expense — and the engine stays ignorant of
 * expenses.</p>
 *
 * <p>Listeners run <b>inside the deciding transaction</b>, not after it commits. The status
 * column and the approval row have to move together: an event delivered after commit would
 * leave a window in which the chain says approved and the expense says submitted, and a
 * crash inside that window makes it permanent.</p>
 *
 * @param finalLevel true when no further level follows an approval, so the record is
 *                   through rather than merely past somebody
 */
public record ApprovalDecided(
        String entityType,
        UUID entityId,
        int level,
        Approval.Status outcome,
        boolean finalLevel,
        UUID actionBy,
        String remarks) {

    public boolean isApproved() {
        return outcome == Approval.Status.APPROVED;
    }

    /** Approved at this level, and nothing further to pass. */
    public boolean isFullyApproved() {
        return isApproved() && finalLevel;
    }
}

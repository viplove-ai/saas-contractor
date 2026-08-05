package in.nirman.modules.project.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The project module's write API for measured progress, for the one caller outside it: a
 * verified daily progress report.
 *
 * <p>Why the DPR posts through here rather than moving {@code completed_quantity} itself: the
 * BOQ line is the project module's row, {@code completed_quantity} is a cache of
 * {@code boq_progress_entries}, and one module reaching into another's cache is how the two
 * start disagreeing. It is the same boundary {@link BoqLookup} draws on the read side.</p>
 *
 * <p>Why the DPR is the normal path at all: {@code boq:progress:record} is a separate
 * permission from {@code boq:write} because entering the contract and claiming work against
 * it are different acts, and the second is the one an engineer signs for. A supervisor
 * <i>describes</i> the day's work on a DPR; nothing is claimed against the contract until the
 * engineer verifies it.</p>
 */
public interface BoqProgress {

    /**
     * @param quantity signed. Negative corrects an earlier over-measurement.
     */
    record Claim(UUID boqItemId, BigDecimal quantity, String remarks) {
    }

    /**
     * @param posted      claims that became entries
     * @param skipped     claims this DPR had already posted — a second verification is quiet,
     *                    never a double count
     * @param overClaimed lines whose running total now exceeds what the contract quantified
     */
    record PostResult(int posted, int skipped, List<UUID> overClaimed) {
    }

    /**
     * Posts a verified DPR's measured work to the measurement book.
     *
     * <p><b>Idempotent per DPR and line.</b> The same rule as verifying attendance twice not
     * paying the worker twice: a double click, a retried request or a re-verification must
     * leave the contract claiming exactly what was measured. Enforced here by a check and by
     * {@code uq_boq_entry_dpr_item} underneath it.</p>
     *
     * <p>Carries no permission check of its own — the caller has already passed
     * {@code dpr:verify}, which the matrix grants to exactly the roles that hold
     * {@code boq:progress:record}. Requiring both would mean an engineer who may sign a DPR
     * cannot sign a DPR.</p>
     */
    PostResult postFromDpr(UUID dprId, UUID siteId, LocalDate entryDate, List<Claim> claims);
}

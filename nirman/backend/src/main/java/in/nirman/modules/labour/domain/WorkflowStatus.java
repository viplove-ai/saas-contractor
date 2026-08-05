package in.nirman.modules.labour.domain;

/**
 * The lifecycle of an attendance row.
 *
 * <p>Only {@code VERIFIED} pays the worker, and only the transition into it posts to the
 * wage ledger. {@code LOCKED} is applied by the period lock and is terminal; a mistaken
 * row is {@code CANCELLED} rather than deleted, which is why the uniqueness index on
 * worker, site and date excludes cancelled rows.</p>
 */
public enum WorkflowStatus {
    DRAFT,
    SUBMITTED,
    VERIFIED,
    REJECTED,
    LOCKED,
    CANCELLED;

    /**
     * Whether the supervisor who entered the row may still change it.
     *
     * <p>{@code SUBMITTED} counts. Nothing is frozen and nothing is posted until
     * verification, so a row waiting in the engineer's queue is still just a claim about
     * the day — and the supervisor who spots his own mistake at four o'clock should fix it
     * rather than ask someone else to reject it back to him first.</p>
     *
     * <p>{@code VERIFIED} does not, and neither does {@code LOCKED}. By then the wage is
     * pinned to the row and posted to the worker's ledger, so an edit is a correction with
     * money already moved against it, not a change of mind.</p>
     */
    public boolean isEditable() {
        return this == DRAFT || this == REJECTED || this == SUBMITTED;
    }

    /**
     * Whether the row is still waiting to be sent. Distinct from {@link #isEditable()} on
     * purpose: a submitted row may be edited but must not be submitted a second time, which
     * would stamp a fresh submitted-at over the original and reorder the engineer's queue.
     */
    public boolean isSubmittable() {
        return this == DRAFT || this == REJECTED;
    }
}

package in.nirman.modules.inventory.domain;

/**
 * The states an inventory document moves through before it is allowed to touch the ledger.
 *
 * <p>Shared by goods receipts, issues and counts because the shape is genuinely the same:
 * somebody at site writes it down, somebody with authority agrees, and only then does stock
 * move. Transfers are the exception and carry their own states — a transfer is not
 * approved, it is dispatched and received, and those are physical events rather than
 * decisions.</p>
 *
 * <p>The ledger posting hangs off the terminal accepting state ({@code VERIFIED} for a
 * receipt, {@code APPROVED} for an issue) rather than off creation. A draft nobody has
 * agreed to must not change what the store says it holds.</p>
 */
public enum DocumentWorkflow {
    DRAFT,
    SUBMITTED,
    /** A goods receipt the engineer has checked against the challan. */
    VERIFIED,
    /** An issue or count that has been signed off. */
    APPROVED,
    REJECTED,
    CANCELLED;

    /** Draft and rejected rows are still the author's to change; nothing has moved yet. */
    public boolean isEditable() {
        return this == DRAFT || this == REJECTED;
    }

    /** Whether stock has moved on the strength of this document. */
    public boolean hasPosted() {
        return this == VERIFIED || this == APPROVED;
    }
}

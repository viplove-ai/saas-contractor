package in.nirman.common;

import java.util.UUID;

/**
 * Decides whether a store can be taken off the books.
 *
 * <p>The same rule {@link SiteDeletionGuard} applies one level up, and it exists for a
 * sharper reason here: a store is what the stock ledger posts against. Deleting one that has
 * ever held material would leave {@code stock_transactions} rows pointing at a lockup that
 * no longer exists — an opening balance with nowhere to be — and the balance cache would
 * carry a quantity for a place nobody can open. A store that is finished with is made
 * inactive, which keeps its ledger and stops new documents naming it.</p>
 *
 * <p>Queried by JDBC over the inventory module's tables for the reason the site guard gives:
 * this is a cross-cutting rule, and routing it through the owning module would make the
 * project module depend on inventory.</p>
 */
public interface StoreDeletionGuard {

    /**
     * @throws BusinessException 422 naming what has been recorded against the store, if
     *                           anything has
     */
    void assertDeletable(UUID storeId);
}

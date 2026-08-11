package in.nirman.common;

import java.util.Collection;
import java.util.UUID;

/**
 * Decides whether a site can be taken off the books.
 *
 * <p>Deletion here is for mistakes — a site typed in wrong, a project that never started —
 * and not for work that has finished. That distinction is the whole point: history does not
 * move, and a site carrying verified attendance owns frozen wage rates, a stock ledger and
 * approved expenses that still roll into last month's figures. Hiding its parent would leave
 * those rows real and unreachable, which is worse than leaving the site on the list. A site
 * whose work is over is {@code CLOSED}, and closed sites stay visible on purpose.</p>
 *
 * <p>Queried by JDBC across a dozen modules' tables rather than through their repositories,
 * for the same reason {@link PeriodLockGuard} is: this is a cross-cutting rule, and routing
 * it through the owning modules would make the project module depend on labour, inventory,
 * expense and DPR — the exact cycle the boundaries exist to prevent.</p>
 */
public interface SiteDeletionGuard {

    /**
     * @throws BusinessException 422 naming what is recorded at the site, if anything is
     */
    void assertDeletable(UUID siteId);

    /**
     * The same rule for a whole project. Reports every site that is holding it up in one
     * error rather than one per attempt — an administrator clearing a four-site project
     * should learn on the first try which sites are the problem.
     *
     * @throws BusinessException 422 naming each site that carries records
     */
    void assertProjectDeletable(Collection<UUID> siteIds);
}

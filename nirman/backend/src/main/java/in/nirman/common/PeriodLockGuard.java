package in.nirman.common;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Blocks writes into a closed accounting period. Called by every write path in labour,
 * inventory and expense so no module carries its own copy of the rule.
 *
 * <p>Implemented in Phase 2.</p>
 */
public interface PeriodLockGuard {

    enum Module { ATTENDANCE, INVENTORY, EXPENSE }

    /** @throws BusinessException 422 if the site's period for that module is locked. */
    void assertOpen(UUID siteId, LocalDate businessDate, Module module);
}

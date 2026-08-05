package in.nirman.security;

import java.util.Collection;
import java.util.UUID;

/**
 * The single choke point for site-level authorisation. Every service that reads or writes
 * site-scoped data calls this before touching the repository. This is what prevents a
 * supervisor from reaching another site's records by guessing an id.
 *
 * <p>Implemented in Phase 2. Never replaced by a frontend check.</p>
 */
public interface SiteAccessGuard {

    /** @throws in.nirman.common.BusinessException 403 if the caller has no claim to the site. */
    void assertCanAccess(UUID siteId);

    void assertCanAccessAll(Collection<UUID> siteIds);

    boolean canAccess(UUID siteId);
}

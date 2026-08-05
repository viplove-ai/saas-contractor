package in.nirman.security;

import java.util.Set;
import java.util.UUID;

/**
 * Read access to the authenticated principal. Implemented in Phase 2 against the JWT
 * security context; declared now so audit and guard code can depend on the interface.
 */
public interface CurrentUserProvider {

    UUID currentUserIdOrNull();

    UUID currentOrgId();

    /** Site ids the caller may touch. Empty when {@link #seesAllSites()} is true. */
    Set<UUID> assignedSiteIds();

    /**
     * Role codes the caller holds.
     *
     * <p>Needed by the approval engine, whose queues belong to a job rather than a person:
     * an approval is assigned to ENGINEER, and whoever is doing that job today picks it up.
     * Everything else in the system authorises on permission codes, and should keep doing
     * so — a role is who you are, a permission is what you may do, and only the queue cares
     * about the first.</p>
     */
    Set<String> roles();

    /** True for roles whose visibility is company-wide (admin, accountant) — the ALL sites claim. */
    boolean seesAllSites();

    boolean isAdmin();

    boolean hasPermission(String permissionCode);
}

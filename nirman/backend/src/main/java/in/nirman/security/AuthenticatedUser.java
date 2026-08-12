package in.nirman.security;

import java.util.Set;
import java.util.UUID;

/**
 * The principal carried by the security context for the duration of one request. Built
 * entirely from the access-token claims. Two of them are re-checked against the database,
 * because both can change inside a token's fifteen-minute lifetime: the site list by
 * {@link SiteAccessGuard}, and the session epoch by {@link SessionEpochGuard}.
 *
 * @param allSites true for roles that are not site-scoped (admin, accountant company view);
 *                 such tokens carry the literal claim {@code "ALL"} instead of a site list
 * @param sessionEpoch the value {@code users.session_epoch} held when this token was
 *                     issued, or {@link #NO_SESSION_EPOCH} for a token minted before the
 *                     claim existed — which matches no account and so is refused
 */
public record AuthenticatedUser(
        UUID userId,
        UUID orgId,
        String username,
        Set<String> roles,
        Set<String> permissions,
        Set<UUID> siteIds,
        boolean allSites,
        long sessionEpoch) {

    /** Stands in for an absent claim. Negative, so it can never equal a stored counter. */
    public static final long NO_SESSION_EPOCH = -1L;

    public boolean hasPermission(String permissionCode) {
        return permissions.contains(permissionCode);
    }

    public boolean isAdmin() {
        return roles.contains("ADMIN");
    }
}

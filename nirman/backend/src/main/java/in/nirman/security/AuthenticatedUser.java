package in.nirman.security;

import java.util.Set;
import java.util.UUID;

/**
 * The principal carried by the security context for the duration of one request. Built
 * entirely from the access-token claims — no database access on the hot path. The one
 * place claims are re-checked against the database is {@link SiteAccessGuard}, because
 * site assignments can be revoked inside a token's fifteen-minute lifetime.
 *
 * @param allSites true for roles that are not site-scoped (admin, accountant company view);
 *                 such tokens carry the literal claim {@code "ALL"} instead of a site list
 */
public record AuthenticatedUser(
        UUID userId,
        UUID orgId,
        String username,
        Set<String> roles,
        Set<String> permissions,
        Set<UUID> siteIds,
        boolean allSites) {

    public boolean hasPermission(String permissionCode) {
        return permissions.contains(permissionCode);
    }

    public boolean isAdmin() {
        return roles.contains("ADMIN");
    }
}

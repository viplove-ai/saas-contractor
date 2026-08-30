package in.nirman.modules.identity.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What the project module is allowed to ask of identity when a site's engineer or
 * supervisor changes. The mirror of {@code SiteLookup}, which is how identity's callers
 * reach project.
 *
 * <p>It exists because naming someone on a site and granting them access to it are two
 * different facts owned by two different modules, and the screen that does the first one
 * means the second. Without this call a supervisor named on a site would still see nothing:
 * {@code sites.supervisor_id} is a label, {@code user_site_assignments} is the permission.</p>
 */
public interface SiteStaffing {

    /**
     * One person the caller may hand something to at a site: the name, and the roles that say
     * what he does there.
     *
     * @param roleCodes the roles he holds in the organisation, so a caller can say "engineer"
     *                  beside the name without joining to identity itself
     */
    record SiteMember(UUID userId, String username, String fullName, List<String> roleCodes) {
    }

    /**
     * Everybody posted to a site today, in name order.
     *
     * <p>Exists because handing a man petty cash means naming him, and the only list of users
     * identity publishes is behind {@code user:read} — an administrator's permission. An
     * accountant holding {@code advance:issue} and nothing else could reach the endpoint that
     * issues the float and not the one that says who is standing on the site. This answers
     * that question and only that question: the people the site's own postings already name,
     * with no password, no e-mail and no way to reach anybody the caller is not scoped to.</p>
     */
    List<SiteMember> postedTo(UUID orgId, UUID siteId);

    /**
     * @throws in.nirman.common.BusinessException 422 if the user is unknown to this org, is
     *         deactivated, or does not hold the role the site is naming them for
     */
    void requireStaffMember(UUID orgId, UUID userId, String roleCode);

    /**
     * Opens a site assignment for each granted user and closes it for each revoked one.
     * Idempotent: a user who already has live access to the site is left alone, which
     * matters because the site screen re-sends the same pair on every unrelated edit.
     */
    void updateSiteAccess(UUID orgId, UUID siteId, Set<UUID> granted, Set<UUID> revoked);
}

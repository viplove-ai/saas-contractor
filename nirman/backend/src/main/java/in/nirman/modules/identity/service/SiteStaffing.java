package in.nirman.modules.identity.service;

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

package in.nirman.common;

import java.util.Collection;
import java.util.UUID;

/**
 * Keeps "who is named on a site" and "who may open it" from contradicting each other.
 *
 * <p>They are two different facts and the system needs both. {@code site_staff} says who
 * <b>runs</b> the site — the engineers and the supervisors it is posted, printed on the
 * register and on every report. {@code user_site_assignments} says who may <b>reach</b> it —
 * any number of people, and the only one of the two {@link in.nirman.security.SiteAccessGuard}
 * consults. So the sync between them can only ever run one way: naming somebody on a site
 * grants them access, while granting access to a store keeper does not make him the engineer.</p>
 *
 * <p>What the one-way sync leaves open is the contradiction this guard closes. The members
 * screen edits the assignment rows directly, and until now it would happily withdraw a site
 * from the very engineer named on it — leaving the sites register saying Uttam Rana runs
 * KSN-A and the guard refusing him the door. The register was not wrong and the assignment
 * was not wrong; they simply disagreed, which is the one state neither screen can show.</p>
 *
 * <p>Asked by JDBC for the reason {@link SiteDeletionGuard} is: the identity module would
 * otherwise have to read the project module's sites while the project module already calls
 * identity to staff them, and that cycle is what the module boundaries exist to prevent.</p>
 */
public interface SitePostingGuard {

    /**
     * Refuses to withdraw a site from somebody still named on it.
     *
     * @param userId  whose access is being narrowed
     * @param siteIds the sites being taken away — usually one or two, never the whole register
     * @throws BusinessException 422 naming each site and the post held on it
     */
    void assertNotPosted(UUID userId, Collection<UUID> siteIds);

    /**
     * The sites this member is named on, for a caller that wants to show the fact rather
     * than run into it. Empty for somebody who runs nothing.
     */
    Collection<UUID> postedSites(UUID userId);
}

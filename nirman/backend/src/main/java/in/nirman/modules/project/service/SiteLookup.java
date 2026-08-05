package in.nirman.modules.project.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The project module's public read API for other modules. Labour, inventory and expense all
 * need a handful of facts about a site — which project it belongs to, how long its shift is
 * — and the module boundary says they get them here rather than through
 * {@code SiteRepository}.
 *
 * <p>Kept deliberately narrow. It exposes the fields other modules genuinely need to do
 * arithmetic, not the whole entity.</p>
 */
public interface SiteLookup {

    /**
     * @param projectId          every transaction carries both, and the composite foreign key
     *                           {@code (site_id, project_id)} means they must agree
     * @param standardShiftHours overtime begins after this; 7.00 at Kausani, not the 8.00 default
     * @param monthlyWageDays    the divisor turning a monthly wage into a daily one
     */
    record SiteInfo(
            UUID id,
            UUID projectId,
            UUID orgId,
            String code,
            String name,
            BigDecimal standardShiftHours,
            int monthlyWageDays) {
    }

    /**
     * @throws in.nirman.common.BusinessException 404 if no such live site in the caller's org,
     *                                            403 if it is not assigned to them
     */
    SiteInfo require(UUID siteId);

    /**
     * A store and the site it stands at. Every stock movement names a store, and every
     * permission decision about that movement is made about the site — so inventory needs
     * both together, on every call, and this is what saves it a join it should not be
     * writing.
     */
    record StoreInfo(
            UUID id,
            UUID siteId,
            UUID projectId,
            UUID orgId,
            String code,
            String name) {
    }

    /**
     * @throws in.nirman.common.BusinessException 404 if no such store in the caller's org,
     *                                            403 if its site is not assigned to them
     */
    StoreInfo requireStore(UUID storeId);

    /** Named without the assignment check, for showing the far end of an incoming transfer. */
    Optional<StoreInfo> findStore(UUID storeId);

    /** Every store at the given sites, for narrowing a stock list to what the caller runs. */
    List<StoreInfo> storesAtSites(Collection<UUID> siteIds);

    /**
     * Whether the site is live in the caller's organisation — deliberately <b>without</b>
     * the assignment check.
     *
     * <p>For the one question that is about a site you do not work at: is this a real place
     * I can send a man to? Answering it needs no access to the site, and refusing to answer
     * would make a transfer between two supervisors impossible.</p>
     */
    boolean isLiveInOrg(UUID siteId);
}

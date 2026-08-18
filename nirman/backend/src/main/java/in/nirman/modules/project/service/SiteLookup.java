package in.nirman.modules.project.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
     * @param usesOutsourcedLabour the day here is recorded as head counts per trade rather
     *                             than as a muster roll, because the work is let to labour
     *                             suppliers. The labour module asks before it offers the
     *                             counts screen, and the DPR asks before it prints the
     *                             section.
     */
    record SiteInfo(
            UUID id,
            UUID projectId,
            UUID orgId,
            String code,
            String name,
            BigDecimal standardShiftHours,
            int monthlyWageDays,
            boolean usesOutsourcedLabour) {
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
     *
     * <p>Live excludes closed, which is why the expense module asks this before it lets the
     * office re-decide whose cost an old bill was: a closed site's figures have been reported
     * to the department, and moving one afterwards moves a number somebody has already been
     * paid against.</p>
     */
    boolean isLiveInOrg(UUID siteId);

    /**
     * Which of the given sites let their labour to suppliers rather than keeping a muster
     * roll, as {@code SiteInfo#usesOutsourcedLabour} says of one site.
     *
     * <p>Unguarded, and for the same reason {@link #isLiveInOrg} is: this is a fact about
     * how a place is run, not a record kept there, and the expense module asks it of every
     * site an organisation-wide register touched — which is by definition more sites than
     * the caller is posted to. Nothing here reaches a figure.</p>
     *
     * <p>The expense module needs it because the double-counting guard behind
     * {@code is_labour_payment} rests on a premise that is false at such a site: a payment
     * to a labour supplier settles wages already costed through attendance only where there
     * is attendance. Ids not in the caller's organisation, deleted or simply unknown come
     * back absent, which is the safe answer — the guard stays on.</p>
     */
    Set<UUID> outsourcedLabourSites(Collection<UUID> siteIds);
}

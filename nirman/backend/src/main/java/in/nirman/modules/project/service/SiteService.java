package in.nirman.modules.project.service;

import in.nirman.common.BusinessException;
import in.nirman.common.SiteDeletionGuard;
import in.nirman.common.StoreDeletionGuard;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.identity.service.SiteStaffing;
import in.nirman.modules.project.api.dto.ProjectDtos.CreateSiteRequest;
import in.nirman.modules.project.api.dto.ProjectDtos.CreateStoreRequest;
import in.nirman.modules.project.api.dto.ProjectDtos.SiteDirectoryEntry;
import in.nirman.modules.project.api.dto.ProjectDtos.SiteResponse;
import in.nirman.modules.project.api.dto.ProjectDtos.StoreDirectoryEntry;
import in.nirman.modules.project.api.dto.ProjectDtos.StoreResponse;
import in.nirman.modules.project.api.dto.ProjectDtos.UpdateSiteRequest;
import in.nirman.modules.project.api.dto.ProjectDtos.UpdateStoreRequest;
import in.nirman.modules.project.domain.Site;
import in.nirman.modules.project.domain.SiteStaff;
import in.nirman.modules.project.domain.SiteStaff.Post;
import in.nirman.modules.project.domain.Store;
import in.nirman.modules.project.mapper.ProjectMapper;
import in.nirman.modules.project.repository.ProjectRepository;
import in.nirman.modules.project.repository.SiteRepository;
import in.nirman.modules.project.repository.SiteStaffRepository;
import in.nirman.modules.project.repository.StoreRepository;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Sites and their stores. Listing quietly narrows to the caller's assigned sites; direct
 * access to an unassigned site is a hard 403 from {@link SiteAccessGuard} — the difference
 * matters, because a list should never advertise what the caller cannot open.
 */
@Service
@Transactional
public class SiteService implements SiteLookup {

    private final SiteRepository sites;
    private final SiteStaffRepository siteStaff;
    private final StoreRepository stores;
    private final ProjectRepository projects;
    private final SiteAccessGuard siteAccessGuard;
    private final SiteDeletionGuard deletionGuard;
    private final StoreDeletionGuard storeDeletionGuard;
    private final SiteStaffing staffing;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;
    private final ProjectMapper mapper;

    public SiteService(SiteRepository sites, SiteStaffRepository siteStaff,
                       StoreRepository stores, ProjectRepository projects,
                       SiteAccessGuard siteAccessGuard, SiteDeletionGuard deletionGuard,
                       StoreDeletionGuard storeDeletionGuard,
                       SiteStaffing staffing, CurrentUserProvider currentUser,
                       AuditService audit, ProjectMapper mapper) {
        this.sites = sites;
        this.siteStaff = siteStaff;
        this.stores = stores;
        this.projects = projects;
        this.siteAccessGuard = siteAccessGuard;
        this.deletionGuard = deletionGuard;
        this.storeDeletionGuard = storeDeletionGuard;
        this.staffing = staffing;
        this.currentUser = currentUser;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('site:read')")
    public List<SiteResponse> list(UUID projectId) {
        UUID orgId = currentUser.currentOrgId();
        List<Site> result;
        if (currentUser.seesAllSites()) {
            result = projectId == null
                    ? sites.findByOrgIdAndDeletedAtIsNullOrderByCode(orgId)
                    : sites.findByOrgIdAndProjectIdAndDeletedAtIsNullOrderByCode(orgId, projectId);
        } else {
            result = sites.findByIdInAndDeletedAtIsNullOrderByCode(currentUser.assignedSiteIds())
                    .stream()
                    .filter(s -> projectId == null || s.getProjectId().equals(projectId))
                    .toList();
        }
        return toResponses(result);
    }

    /**
     * The deleted sites, as their own view. Behind {@code site:delete} for the same reason
     * the projects one is: only someone who can put a site back has a use for the list.
     *
     * <p>Not narrowed by assignment, and it does not need to be — deleting a site ends the
     * postings to it, so a narrowed version of this list would always be empty.</p>
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('site:delete')")
    public List<SiteResponse> listDeleted(UUID projectId) {
        UUID orgId = currentUser.currentOrgId();
        List<Site> result = projectId == null
                ? sites.findByOrgIdAndDeletedAtIsNotNullOrderByCode(orgId)
                : sites.findByOrgIdAndProjectIdAndDeletedAtIsNotNullOrderByCode(orgId, projectId);
        return toResponses(result);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('site:read')")
    public SiteResponse get(UUID id) {
        return toResponse(requireSite(id));
    }

    /**
     * Every site in the company by code and name, unnarrowed — the one read that is not
     * fenced by assignment.
     *
     * <p>It exists because a supervisor transferring a man has to be able to name where he
     * is going, and that is by definition a site he does not work at. What it hands over is
     * a destination and nothing else: no address, no staffing, no shift length, and no way
     * to reach a single record at that site. {@link #list} remains the narrowed answer to
     * "where do I work".</p>
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('site:read')")
    public List<SiteDirectoryEntry> directory() {
        return sites.findByOrgIdAndDeletedAtIsNullOrderByCode(currentUser.currentOrgId()).stream()
                .map(site -> new SiteDirectoryEntry(site.getId(), site.getProjectId(),
                        site.getCode(), site.getName(), site.getStatus()))
                .toList();
    }

    @PreAuthorize("hasAuthority('site:write')")
    public SiteResponse create(CreateSiteRequest request) {
        UUID orgId = currentUser.currentOrgId();
        projects.findByIdAndOrgIdAndDeletedAtIsNull(request.projectId(), orgId)
                .orElseThrow(() -> BusinessException.notFound("Project", request.projectId()));
        sites.findByOrgIdAndCode(orgId, request.code()).ifPresent(existing -> {
            // Deleted sites keep their codes reserved so a restore cannot collide.
            if (existing.isDeleted()) {
                throw BusinessException.conflict("site.code-deleted",
                        "Code '" + request.code() + "' belongs to a deleted site. Restore "
                                + "that one from the deleted list, or use another code.");
            }
            throw BusinessException.conflict("site.code-taken",
                    "A site with code '" + request.code() + "' already exists.");
        });
        List<UUID> engineers = requireStaff(orgId, request.siteEngineerIds(), Post.ENGINEER);
        List<UUID> supervisors = requireStaff(orgId, request.supervisorIds(), Post.SUPERVISOR);
        Site site = new Site(orgId, request.projectId(), request.code(), request.name());
        applyMutableFields(site, request.name(), request.address(), request.latitude(),
                request.longitude(),
                request.startDate(), request.standardShiftHours(), request.monthlyWageDays(),
                request.usesOutsourcedLabour());
        sites.save(site);
        createDefaultStore(site);
        replaceStaff(site, engineers, supervisors);
        audit.record("SITE", site.getId(), "CREATE", null,
                Map.of("code", site.getCode(), "name", site.getName()), null);
        return toResponse(site);
    }

    /**
     * Gives a new site the store it is going to need, without asking.
     *
     * <p>Nobody adding a site is making a decision about stores — the shed by the gate is
     * simply there. Leaving it out meant a site could be created on Monday and the first
     * lorry on Tuesday met an empty store picker on the receive screen, with the fix on a
     * page the supervisor cannot reach. It is named after the site so the two read as the
     * same place, and the Stores screen is where an organisation with a cement lockup and a
     * separate steel yard says so.</p>
     */
    private void createDefaultStore(Site site) {
        String code = uniqueStoreCode(site.getOrgId(), Store.SITE_STORE_PREFIX + site.getCode());
        Store store = new Store(site.getOrgId(), site.getId(), code,
                Store.SITE_STORE_PREFIX + site.getName());
        stores.save(store);
        audit.record("STORE", store.getId(), "CREATE", null,
                Map.of("code", store.getCode(), "siteId", site.getId().toString(),
                        "derivedFrom", "site"), null);
    }

    /**
     * The derived code, or the first free variant of it.
     *
     * <p>A site code is unique in the organisation, so the derived store code almost always
     * is too. Almost: somebody may already have typed {@code site-NTL-01} by hand on another
     * site's store, and a site must not fail to be created over the name of a shed.</p>
     */
    private String uniqueStoreCode(UUID orgId, String base) {
        if (!stores.existsByOrgIdAndCode(orgId, base)) {
            return base;
        }
        for (int suffix = 2; suffix < 100; suffix++) {
            String candidate = base + "-" + suffix;
            if (!stores.existsByOrgIdAndCode(orgId, candidate)) {
                return candidate;
            }
        }
        // A hundred stores sharing one derived name is not a collision, it is a mistake
        // somewhere else; a random tail keeps the site creation from failing over it.
        return base + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    @PreAuthorize("hasAuthority('site:write')")
    public SiteResponse update(UUID id, UpdateSiteRequest request) {
        Site site = requireSite(id);
        if (!site.getVersion().equals(request.version())) {
            throw new OptimisticLockingFailureException("Site " + id + " was changed by someone else");
        }
        Map<String, Object> before = Map.of("name", site.getName(),
                "status", site.getStatus().name(),
                "standardShiftHours", site.getStandardShiftHours());
        List<UUID> engineers = requireStaff(site.getOrgId(), request.siteEngineerIds(),
                Post.ENGINEER);
        List<UUID> supervisors = requireStaff(site.getOrgId(), request.supervisorIds(),
                Post.SUPERVISOR);
        applyMutableFields(site, request.name(), request.address(), request.latitude(),
                request.longitude(),
                request.startDate(), request.standardShiftHours(), request.monthlyWageDays(),
                request.usesOutsourcedLabour());
        if (request.status() != null) {
            site.setStatus(request.status());
        }
        replaceStaff(site, engineers, supervisors);
        audit.record("SITE", site.getId(), "UPDATE", before,
                Map.of("name", site.getName(), "status", site.getStatus().name(),
                        "standardShiftHours", site.getStandardShiftHours()), null);
        return toResponse(site);
    }

    /**
     * Takes a site off the books.
     *
     * <p>Refused outright if anything has been recorded there — see {@link SiteDeletionGuard}.
     * What survives the check is a site nobody ever used, so there is nothing to preserve
     * and nothing to recompute.</p>
     *
     * <p>The postings to it end here too. Leaving {@code user_site_assignments} intact would
     * leave a supervisor holding a live claim to a site that no longer appears anywhere, and
     * the JWT they are carrying would still name it.</p>
     */
    @PreAuthorize("hasAuthority('site:delete')")
    public SiteResponse delete(UUID id, String reason) {
        Site site = sites.findByIdAndOrgId(id, currentUser.currentOrgId())
                .orElseThrow(() -> BusinessException.notFound("Site", id));
        // The same IDOR fence the live path has: an id is not a key to a site.
        siteAccessGuard.assertCanAccess(site.getId());
        if (site.isDeleted()) {
            throw BusinessException.conflict("site.already-deleted",
                    "This site has already been deleted.");
        }
        deletionGuard.assertDeletable(site.getId());
        applyDelete(site, Instant.now(), reason);
        return toResponse(site);
    }

    /**
     * Deletes a site as part of its project going down, sharing the project's timestamp.
     *
     * <p>No {@code @PreAuthorize} and no guard call of its own: {@code ProjectService} has
     * already checked the permission and run {@link SiteDeletionGuard} over every site at
     * once, so that an administrator learns about all four blocked sites in one refusal
     * rather than one per attempt.</p>
     */
    void deleteWithProject(Site site, Instant at, String reason) {
        applyDelete(site, at, reason);
    }

    private void applyDelete(Site site, Instant at, String reason) {
        site.delete(at, currentUser.currentUserIdOrNull(), reason);
        // The staff rows stay: they are what a restore puts back, and they are also the
        // record of who ran the site while it existed. Only the access goes.
        Set<UUID> posted = postedUserIds(site.getId());
        if (!posted.isEmpty()) {
            staffing.updateSiteAccess(site.getOrgId(), site.getId(), Set.of(), posted);
        }
        audit.record("SITE", site.getId(), "DELETE",
                Map.of("code", site.getCode(), "name", site.getName()),
                Map.of("deletedAt", at.toString(), "reason", reason), reason);
    }

    /** Puts a site back, with the engineer and supervisor named on it posted to it again. */
    @PreAuthorize("hasAuthority('site:delete')")
    public SiteResponse restore(UUID id) {
        Site site = sites.findByIdAndOrgId(id, currentUser.currentOrgId())
                .orElseThrow(() -> BusinessException.notFound("Site", id));
        if (!site.isDeleted()) {
            throw BusinessException.conflict("site.not-deleted",
                    "This site is not deleted, so there is nothing to restore.");
        }
        requireLiveProject(site.getProjectId());
        applyRestore(site);
        return toResponse(site);
    }

    /** @see #deleteWithProject — the same arrangement in reverse. */
    void restoreWithProject(Site site) {
        applyRestore(site);
    }

    private void applyRestore(Site site) {
        site.restore();
        Set<UUID> posted = postedUserIds(site.getId());
        if (!posted.isEmpty()) {
            staffing.updateSiteAccess(site.getOrgId(), site.getId(), posted, Set.of());
        }
        audit.record("SITE", site.getId(), "RESTORE", null,
                Map.of("code", site.getCode(), "name", site.getName()), null);
    }

    /**
     * A site cannot come back on its own while its project is off the books — it would be a
     * site on no list, reachable only by id.
     */
    private void requireLiveProject(UUID projectId) {
        projects.findByIdAndOrgIdAndDeletedAtIsNull(projectId, currentUser.currentOrgId())
                .orElseThrow(() -> new BusinessException("site.project-deleted",
                        "This site's project has been deleted. Restore the project and its "
                                + "sites come back with it."));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('site:read')")
    public List<StoreResponse> listStores(UUID siteId) {
        requireSite(siteId);
        return stores.findBySiteIdOrderByCode(siteId).stream().map(mapper::toResponse).toList();
    }

    /**
     * Every store the caller can reach, across their sites, with the site named on each.
     *
     * <p>The Stores screen's list. Narrowed by assignment exactly like {@link #list} — a
     * store is a lockup inside a site and inherits the site's fence — and it names the site
     * on the row because a store called "site-NTL-01" means nothing without it.</p>
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('site:read')")
    public List<StoreDirectoryEntry> listAllStores(UUID siteId) {
        if (siteId != null) {
            // One site's stores go through the ordinary fence: an id is not a key to a site.
            Site site = requireSite(siteId);
            return stores.findBySiteIdOrderByCode(siteId).stream()
                    .map(store -> toDirectoryEntry(store, site))
                    .toList();
        }
        List<Site> visible = currentUser.seesAllSites()
                ? sites.findByOrgIdAndDeletedAtIsNullOrderByCode(currentUser.currentOrgId())
                : sites.findByIdInAndDeletedAtIsNullOrderByCode(currentUser.assignedSiteIds());
        Map<UUID, Site> bySiteId = visible.stream()
                .collect(Collectors.toMap(Site::getId, site -> site));
        if (bySiteId.isEmpty()) {
            return List.of();
        }
        return stores.findBySiteIdInOrderByCode(bySiteId.keySet()).stream()
                .map(store -> toDirectoryEntry(store, bySiteId.get(store.getSiteId())))
                .toList();
    }

    @PreAuthorize("hasAuthority('site:write')")
    public StoreResponse createStore(UUID siteId, CreateStoreRequest request) {
        Site site = requireSite(siteId);
        if (stores.existsByOrgIdAndCode(site.getOrgId(), request.code())) {
            throw BusinessException.conflict("store.code-taken",
                    "A store with code '" + request.code() + "' already exists.");
        }
        Store store = new Store(site.getOrgId(), siteId, request.code(), request.name());
        store.setLocation(request.location());
        store.setDefaultStore(request.defaultStore());
        stores.save(store);
        if (store.isDefaultStore()) {
            demoteOtherDefaults(store);
        }
        audit.record("STORE", store.getId(), "CREATE", null,
                Map.of("code", store.getCode(), "siteId", siteId.toString()), null);
        return mapper.toResponse(store);
    }

    @PreAuthorize("hasAuthority('site:write')")
    public StoreResponse updateStore(UUID storeId, UpdateStoreRequest request) {
        Store store = requireStoreForWrite(storeId);
        if (!store.getVersion().equals(request.version())) {
            throw new OptimisticLockingFailureException(
                    "Store " + storeId + " was changed by someone else");
        }
        Map<String, Object> before = Map.of("code", store.getCode(), "name", store.getName(),
                "defaultStore", store.isDefaultStore(), "active", store.isActive());
        // Its own code is not a collision — a store that keeps its name is not renaming.
        stores.findByOrgIdAndCode(store.getOrgId(), request.code())
                .filter(other -> !other.getId().equals(store.getId()))
                .ifPresent(other -> {
                    throw BusinessException.conflict("store.code-taken",
                            "A store with code '" + request.code() + "' already exists.");
                });
        store.setCode(request.code());
        store.setName(request.name());
        store.setLocation(request.location());
        store.setDefaultStore(request.defaultStore());
        store.setActive(request.active());
        if (store.isDefaultStore()) {
            demoteOtherDefaults(store);
        }
        audit.record("STORE", store.getId(), "UPDATE", before,
                Map.of("code", store.getCode(), "name", store.getName(),
                        "defaultStore", store.isDefaultStore(), "active", store.isActive()), null);
        return mapper.toResponse(store);
    }

    /**
     * Removes a store outright, and only ever a store nothing was recorded against.
     *
     * <p>Hard, not soft, and it can afford to be: {@link StoreDeletionGuard} refuses any
     * store the ledger has touched, so what survives the check is a lockup that never held
     * anything. A store whose working life is over is made inactive instead — that keeps its
     * ledger where the balances can still be read and stops new documents naming it.</p>
     */
    @PreAuthorize("hasAuthority('site:delete')")
    public void deleteStore(UUID storeId) {
        Store store = requireStoreForWrite(storeId);
        storeDeletionGuard.assertDeletable(storeId);
        audit.record("STORE", store.getId(), "DELETE",
                Map.of("code", store.getCode(), "name", store.getName(),
                        "siteId", store.getSiteId().toString()),
                null, null);
        stores.delete(store);
    }

    /**
     * One default per site. The flag decides which store the receive and issue screens open
     * on, so two of them at one site is a coin toss the supervisor has to notice and correct.
     */
    private void demoteOtherDefaults(Store chosen) {
        stores.findBySiteIdOrderByCode(chosen.getSiteId()).stream()
                .filter(other -> !other.getId().equals(chosen.getId()))
                .filter(Store::isDefaultStore)
                .forEach(other -> other.setDefaultStore(false));
    }

    /** A store is reached through its site's fence, and has no permission of its own. */
    private Store requireStoreForWrite(UUID storeId) {
        Store store = stores.findById(storeId)
                .filter(candidate -> candidate.getOrgId().equals(currentUser.currentOrgId()))
                .orElseThrow(() -> BusinessException.notFound("Store", storeId));
        requireSite(store.getSiteId());
        return store;
    }

    private StoreDirectoryEntry toDirectoryEntry(Store store, Site site) {
        return new StoreDirectoryEntry(store.getId(), store.getSiteId(),
                site == null ? null : site.getCode(), site == null ? null : site.getName(),
                store.getCode(), store.getName(), store.getLocation(),
                store.isDefaultStore(), store.isActive(), store.getVersion());
    }

    /**
     * {@inheritDoc}
     *
     * <p>No {@code @PreAuthorize}: the caller is another module acting for a user who has
     * already passed its own permission check, and every path here still runs through
     * {@link SiteAccessGuard}. Adding {@code site:read} would mean a supervisor could not
     * record attendance without also holding a project-module permission.</p>
     */
    /**
     * {@inheritDoc}
     *
     * <p>No access guard on purpose — see the interface. This answers "does that site
     * exist", which is what a transfer to someone else's site needs and all it needs.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public boolean isLiveInOrg(UUID siteId) {
        return sites.findByIdAndOrgIdAndDeletedAtIsNull(siteId, currentUser.currentOrgId())
                .filter(site -> site.getStatus() != Site.Status.CLOSED)
                .isPresent();
    }

    /**
     * {@inheritDoc}
     *
     * <p>No access guard on purpose — see the interface. Empty in, empty out: the query
     * would otherwise be asked for an {@code IN ()} that no dialect accepts.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public Set<UUID> outsourcedLabourSites(Collection<UUID> siteIds) {
        if (siteIds == null || siteIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(sites.findOutsourcedLabourSiteIds(currentUser.currentOrgId(),
                Set.copyOf(siteIds)));
    }

    @Override
    @Transactional(readOnly = true)
    public SiteInfo require(UUID siteId) {
        Site site = requireSite(siteId);
        return new SiteInfo(site.getId(), site.getProjectId(), site.getOrgId(), site.getCode(),
                site.getName(), site.getStandardShiftHours(), site.getMonthlyWageDays(),
                site.isUsesOutsourcedLabour());
    }

    /**
     * {@inheritDoc}
     *
     * <p>No access check, deliberately — see the interface. This answers which sites exist on
     * a project; whether the caller may touch the one it picks is decided by that caller
     * against {@link in.nirman.security.SiteAccessGuard}, on the site itself.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public List<SiteInfo> forProject(UUID projectId) {
        return sites.findByOrgIdAndProjectIdAndDeletedAtIsNullOrderByCode(
                        currentUser.currentOrgId(), projectId).stream()
                .map(site -> new SiteInfo(site.getId(), site.getProjectId(), site.getOrgId(),
                        site.getCode(), site.getName(), site.getStandardShiftHours(),
                        site.getMonthlyWageDays(), site.isUsesOutsourcedLabour()))
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The site access check is on the store's site, not on the store. A store is a
     * lockup inside a site and has no separate permission of its own; guessing a store id
     * has to fail for exactly the same reason guessing a site id does.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public StoreInfo requireStore(UUID storeId) {
        Store store = stores.findById(storeId)
                .filter(s -> s.getOrgId().equals(currentUser.currentOrgId()))
                .orElseThrow(() -> BusinessException.notFound("Store", storeId));
        siteAccessGuard.assertCanAccess(store.getSiteId());
        return toStoreInfo(store);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Unguarded, and narrow for it: a transfer has a store at each end, and the person
     * receiving one has to be able to see where it came from — which is by definition a
     * store they do not run. What comes back is a code and a name, and nothing that would
     * let them reach a record there.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<StoreInfo> findStore(UUID storeId) {
        return stores.findById(storeId)
                .filter(s -> s.getOrgId().equals(currentUser.currentOrgId()))
                .map(this::toStoreInfo);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoreInfo> storesAtSites(Collection<UUID> siteIds) {
        if (siteIds.isEmpty()) {
            return List.of();
        }
        return stores.findBySiteIdInOrderByCode(siteIds).stream()
                .filter(store -> store.getOrgId().equals(currentUser.currentOrgId()))
                .map(this::toStoreInfo)
                .toList();
    }

    private StoreInfo toStoreInfo(Store store) {
        UUID projectId = sites.findById(store.getSiteId())
                .map(Site::getProjectId)
                .orElseThrow(() -> BusinessException.notFound("Site", store.getSiteId()));
        return new StoreInfo(store.getId(), store.getSiteId(), projectId, store.getOrgId(),
                store.getCode(), store.getName());
    }

    // ------------------------------------------------------------------ internals

    /**
     * Checks every name the register is about to carry, and hands back the list de-duped.
     *
     * <p>A null list is nobody, which is what a site with no engineer yet looks like and is
     * a real intermediate state — the engineer has to exist before the site he runs can be
     * created. The same id twice is one posting: the screen sends what its switches say and
     * two switches for one man is not two engineers.</p>
     */
    private List<UUID> requireStaff(UUID orgId, List<UUID> userIds, Post post) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<UUID> distinct = userIds.stream().filter(Objects::nonNull).distinct().toList();
        distinct.forEach(userId -> staffing.requireStaffMember(orgId, userId, post.name()));
        return distinct;
    }

    /**
     * Writes the register's view of who runs the site, and keeps the access rows in step
     * with it.
     *
     * <p>The lists arrive whole rather than as a delta, so the rows are replaced outright.
     * What cannot be replaced outright is the access: somebody who merely swapped posts, or
     * who is named twice over as engineer of one shift and supervisor of the other, appears
     * in both the old and the new set and must not lose the site for an instant. So access
     * is granted for the names that are new to the site and withdrawn only for the names
     * that have left it entirely.</p>
     */
    private void replaceStaff(Site site, List<UUID> engineers, List<UUID> supervisors) {
        Set<UUID> previous = postedUserIds(site.getId());
        siteStaff.deleteAll(siteStaff.findBySiteId(site.getId()));
        // Flushed before the inserts: the unique key is (site, user, post), and re-saving a
        // posting that is not going to change would otherwise collide with its own old row.
        siteStaff.flush();
        for (UUID userId : engineers) {
            siteStaff.save(new SiteStaff(site.getOrgId(), site.getId(), userId, Post.ENGINEER));
        }
        for (UUID userId : supervisors) {
            siteStaff.save(new SiteStaff(site.getOrgId(), site.getId(), userId, Post.SUPERVISOR));
        }

        Set<UUID> current = new HashSet<>(engineers);
        current.addAll(supervisors);
        Set<UUID> granted = new HashSet<>(current);
        granted.removeAll(previous);
        Set<UUID> revoked = new HashSet<>(previous);
        revoked.removeAll(current);
        if (!granted.isEmpty() || !revoked.isEmpty()) {
            staffing.updateSiteAccess(site.getOrgId(), site.getId(), granted, revoked);
        }
    }

    /** Everybody named on the site, under either post, each of them once. */
    private Set<UUID> postedUserIds(UUID siteId) {
        return siteStaff.findBySiteId(siteId).stream()
                .map(SiteStaff::getUserId)
                .collect(Collectors.toSet());
    }

    private SiteResponse toResponse(Site site) {
        return withStaff(site, siteStaff.findBySiteId(site.getId()));
    }

    /** A register of sites, with one query for every row's names rather than one per row. */
    private List<SiteResponse> toResponses(List<Site> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<SiteStaff>> bySite = siteStaff
                .findBySiteIdIn(found.stream().map(Site::getId).toList()).stream()
                .collect(Collectors.groupingBy(SiteStaff::getSiteId));
        return found.stream()
                .map(site -> withStaff(site, bySite.getOrDefault(site.getId(), List.of())))
                .toList();
    }

    private SiteResponse withStaff(Site site, List<SiteStaff> posted) {
        return mapper.toResponse(site, namesUnder(posted, Post.ENGINEER),
                namesUnder(posted, Post.SUPERVISOR));
    }

    /** Sorted, so the register and the form both draw the same list in the same order. */
    private static List<UUID> namesUnder(List<SiteStaff> posted, Post post) {
        return posted.stream()
                .filter(row -> row.getPost() == post)
                .map(SiteStaff::getUserId)
                .sorted()
                .toList();
    }

    private Site requireSite(UUID id) {
        Site site = sites.findByIdAndOrgIdAndDeletedAtIsNull(id, currentUser.currentOrgId())
                .orElseThrow(() -> BusinessException.notFound("Site", id));
        // The IDOR fence. A supervisor guessing another site's id stops here.
        siteAccessGuard.assertCanAccess(site.getId());
        return site;
    }

    private static void applyMutableFields(Site site, String name, String address,
                                           java.math.BigDecimal latitude, java.math.BigDecimal longitude,
                                           java.time.LocalDate startDate,
                                           java.math.BigDecimal shiftHours, int wageDays,
                                           boolean usesOutsourcedLabour) {
        site.setName(name);
        site.setAddress(address);
        site.setLatitude(latitude);
        site.setLongitude(longitude);
        site.setStartDate(startDate);
        site.setStandardShiftHours(shiftHours);
        site.setMonthlyWageDays(wageDays);
        site.setUsesOutsourcedLabour(usesOutsourcedLabour);
    }
}

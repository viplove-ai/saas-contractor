package in.nirman.modules.project.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.identity.service.SiteStaffing;
import in.nirman.modules.project.api.dto.ProjectDtos.CreateSiteRequest;
import in.nirman.modules.project.api.dto.ProjectDtos.CreateStoreRequest;
import in.nirman.modules.project.api.dto.ProjectDtos.SiteDirectoryEntry;
import in.nirman.modules.project.api.dto.ProjectDtos.SiteResponse;
import in.nirman.modules.project.api.dto.ProjectDtos.StoreResponse;
import in.nirman.modules.project.api.dto.ProjectDtos.UpdateSiteRequest;
import in.nirman.modules.project.domain.Site;
import in.nirman.modules.project.domain.Store;
import in.nirman.modules.project.mapper.ProjectMapper;
import in.nirman.modules.project.repository.ProjectRepository;
import in.nirman.modules.project.repository.SiteRepository;
import in.nirman.modules.project.repository.StoreRepository;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Sites and their stores. Listing quietly narrows to the caller's assigned sites; direct
 * access to an unassigned site is a hard 403 from {@link SiteAccessGuard} — the difference
 * matters, because a list should never advertise what the caller cannot open.
 */
@Service
@Transactional
public class SiteService implements SiteLookup {

    private final SiteRepository sites;
    private final StoreRepository stores;
    private final ProjectRepository projects;
    private final SiteAccessGuard siteAccessGuard;
    private final SiteStaffing staffing;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;
    private final ProjectMapper mapper;

    public SiteService(SiteRepository sites, StoreRepository stores, ProjectRepository projects,
                       SiteAccessGuard siteAccessGuard, SiteStaffing staffing,
                       CurrentUserProvider currentUser, AuditService audit, ProjectMapper mapper) {
        this.sites = sites;
        this.stores = stores;
        this.projects = projects;
        this.siteAccessGuard = siteAccessGuard;
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
        return result.stream().map(mapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('site:read')")
    public SiteResponse get(UUID id) {
        return mapper.toResponse(requireSite(id));
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
        if (sites.existsByOrgIdAndCode(orgId, request.code())) {
            throw BusinessException.conflict("site.code-taken",
                    "A site with code '" + request.code() + "' already exists.");
        }
        requireStaff(orgId, request.siteEngineerId(), request.supervisorId());
        Site site = new Site(orgId, request.projectId(), request.code(), request.name());
        applyMutableFields(site, request.name(), request.address(), request.latitude(),
                request.longitude(), request.siteEngineerId(), request.supervisorId(),
                request.startDate(), request.standardShiftHours(), request.monthlyWageDays());
        sites.save(site);
        staffing.updateSiteAccess(orgId, site.getId(),
                staffIds(request.siteEngineerId(), request.supervisorId()), Set.of());
        audit.record("SITE", site.getId(), "CREATE", null,
                Map.of("code", site.getCode(), "name", site.getName()), null);
        return mapper.toResponse(site);
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
        requireStaff(site.getOrgId(), request.siteEngineerId(), request.supervisorId());
        Set<UUID> previousStaff = staffIds(site.getSiteEngineerId(), site.getSupervisorId());
        applyMutableFields(site, request.name(), request.address(), request.latitude(),
                request.longitude(), request.siteEngineerId(), request.supervisorId(),
                request.startDate(), request.standardShiftHours(), request.monthlyWageDays());
        if (request.status() != null) {
            site.setStatus(request.status());
        }
        applyStaffChange(site, previousStaff);
        audit.record("SITE", site.getId(), "UPDATE", before,
                Map.of("name", site.getName(), "status", site.getStatus().name(),
                        "standardShiftHours", site.getStandardShiftHours()), null);
        return mapper.toResponse(site);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('site:read')")
    public List<StoreResponse> listStores(UUID siteId) {
        requireSite(siteId);
        return stores.findBySiteIdOrderByCode(siteId).stream().map(mapper::toResponse).toList();
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
        audit.record("STORE", store.getId(), "CREATE", null,
                Map.of("code", store.getCode(), "siteId", siteId.toString()), null);
        return mapper.toResponse(store);
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

    @Override
    @Transactional(readOnly = true)
    public SiteInfo require(UUID siteId) {
        Site site = requireSite(siteId);
        return new SiteInfo(site.getId(), site.getProjectId(), site.getOrgId(), site.getCode(),
                site.getName(), site.getStandardShiftHours(), site.getMonthlyWageDays());
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

    private void requireStaff(UUID orgId, UUID engineerId, UUID supervisorId) {
        if (engineerId != null) {
            staffing.requireStaffMember(orgId, engineerId, "ENGINEER");
        }
        if (supervisorId != null) {
            staffing.requireStaffMember(orgId, supervisorId, "SUPERVISOR");
        }
    }

    /**
     * Keeps site access in step with who is named on the site. Someone dropped from the
     * site loses their assignment to it — that is what removing them was for — while
     * someone who merely swapped seats (engineer to supervisor) appears in both sets and
     * is left untouched.
     */
    private void applyStaffChange(Site site, Set<UUID> previousStaff) {
        Set<UUID> current = staffIds(site.getSiteEngineerId(), site.getSupervisorId());
        Set<UUID> granted = new HashSet<>(current);
        granted.removeAll(previousStaff);
        Set<UUID> revoked = new HashSet<>(previousStaff);
        revoked.removeAll(current);
        if (!granted.isEmpty() || !revoked.isEmpty()) {
            staffing.updateSiteAccess(site.getOrgId(), site.getId(), granted, revoked);
        }
    }

    private static Set<UUID> staffIds(UUID engineerId, UUID supervisorId) {
        return Stream.of(engineerId, supervisorId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
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
                                           UUID engineerId, UUID supervisorId,
                                           java.time.LocalDate startDate,
                                           java.math.BigDecimal shiftHours, int wageDays) {
        site.setName(name);
        site.setAddress(address);
        site.setLatitude(latitude);
        site.setLongitude(longitude);
        site.setSiteEngineerId(engineerId);
        site.setSupervisorId(supervisorId);
        site.setStartDate(startDate);
        site.setStandardShiftHours(shiftHours);
        site.setMonthlyWageDays(wageDays);
    }
}

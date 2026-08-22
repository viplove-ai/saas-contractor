package in.nirman.modules.project.service;

import in.nirman.common.BusinessException;
import in.nirman.common.PageResponse;
import in.nirman.common.SiteDeletionGuard;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.project.api.dto.ProjectDtos.CreateProjectRequest;
import in.nirman.modules.project.api.dto.ProjectDtos.ProjectResponse;
import in.nirman.modules.project.api.dto.ProjectDtos.ProjectSummaryResponse;
import in.nirman.modules.project.api.dto.ProjectDtos.UpdateProjectRequest;
import in.nirman.modules.project.domain.BoqItem;
import in.nirman.modules.project.domain.Project;
import in.nirman.modules.identity.service.SiteStaffing;
import in.nirman.modules.project.domain.Site;
import in.nirman.modules.project.mapper.ProjectMapper;
import in.nirman.modules.project.repository.BoqItemRepository;
import in.nirman.modules.project.repository.ProjectRepository;
import in.nirman.modules.project.repository.SiteRepository;
import in.nirman.modules.project.repository.StoreRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Projects. Reads are site-scoped for engineers and supervisors: they see a project when
 * at least one of its sites is assigned to them. Writes are admin-only by the matrix.
 */
@Service
@Transactional
public class ProjectService implements ProjectProvisioning {

    private final ProjectRepository projects;
    private final SiteRepository sites;
    private final StoreRepository stores;
    private final BoqItemRepository boqItems;
    private final SiteService siteService;
    private final SiteDeletionGuard deletionGuard;
    private final SiteStaffing staffing;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;
    private final ProjectMapper mapper;

    public ProjectService(ProjectRepository projects, SiteRepository sites, StoreRepository stores,
                          BoqItemRepository boqItems, SiteService siteService,
                          SiteDeletionGuard deletionGuard, SiteStaffing staffing,
                          CurrentUserProvider currentUser,
                          AuditService audit, ProjectMapper mapper) {
        this.projects = projects;
        this.sites = sites;
        this.stores = stores;
        this.boqItems = boqItems;
        this.siteService = siteService;
        this.deletionGuard = deletionGuard;
        this.staffing = staffing;
        this.currentUser = currentUser;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('project:read')")
    public PageResponse<ProjectResponse> list(Project.Status status, String q, Pageable pageable) {
        return search(status, q, false, pageable);
    }

    /**
     * The deleted list — a separate view, never rows mixed into the live one.
     *
     * <p>Behind {@code project:delete} rather than {@code project:read}, because someone who
     * cannot undo a deletion has no use for the list of them, and the whole point of the
     * feature is that a deleted project is out of everyone else's way.</p>
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('project:delete')")
    public PageResponse<ProjectResponse> listDeleted(String q, Pageable pageable) {
        return search(null, q, true, pageable);
    }

    private PageResponse<ProjectResponse> search(Project.Status status, String q, boolean deleted,
                                                 Pageable pageable) {
        boolean restricted = !seesAllSites();
        List<UUID> visibleProjectIds = restricted ? visibleProjectIds() : List.of();
        return PageResponse.from(
                projects.search(currentUser.currentOrgId(), status, blankToEmpty(q), deleted,
                        restricted, visibleProjectIds, pageable),
                mapper::toResponse);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('project:read')")
    public ProjectResponse get(UUID id) {
        return mapper.toResponse(requireVisibleProject(id));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('project:read')")
    public ProjectSummaryResponse summary(UUID id) {
        Project project = requireVisibleProject(id);
        List<UUID> siteIds = sites.findByOrgIdAndProjectIdAndDeletedAtIsNullOrderByCode(
                        project.getOrgId(), project.getId()).stream()
                .map(Site::getId)
                .filter(siteId -> seesAllSites() || currentUser.assignedSiteIds().contains(siteId))
                .toList();
        long storeCount = siteIds.isEmpty() ? 0 : stores.countBySiteIdInAndActiveTrue(siteIds);
        return new ProjectSummaryResponse(project.getId(), project.getCode(), project.getName(),
                project.getStatus(), project.getContractValue(), project.getBudgetAmount(),
                project.getStartDate(), project.getExpectedCompletionDate(),
                siteIds.size(), storeCount);
    }

    @PreAuthorize("hasAuthority('project:write')")
    public ProjectResponse create(CreateProjectRequest request) {
        return mapper.toResponse(createProject(request));
    }

    /**
     * {@inheritDoc}
     *
     * <p>One transaction for the project and its schedule. A tender import that half
     * succeeded would leave a project whose BOQ is a truncated guess at the contract, which
     * is worse than no project: the numbers look complete and are not.</p>
     */
    @Override
    @PreAuthorize("hasAuthority('project:write') and hasAuthority('boq:write')")
    public ProvisionResult createWithBoq(ProvisionRequest request) {
        Project project = createProject(request.project());
        if (request.billingOnly()) {
            project.setMode(Project.Mode.BILLING_ONLY);
        }

        Set<String> itemNumbers = new LinkedHashSet<>();
        List<BoqItem> items = new ArrayList<>(request.lines().size());
        BigDecimal value = BigDecimal.ZERO;
        for (ImportedBoqLine line : request.lines()) {
            if (!itemNumbers.add(line.itemNumber())) {
                throw BusinessException.conflict("boq.duplicate-item-number",
                        "Item number '" + line.itemNumber() + "' appears more than once. "
                                + "Each line needs its own number so work can be measured "
                                + "against it.");
            }
            BoqItem item = new BoqItem(project.getOrgId(), project.getId(), line.itemNumber(),
                    line.description(), line.unitId());
            // Never trust a passed-in amount: priceAt is the only path that keeps
            // contract_amount consistent with the quantity and rate beside it.
            item.priceAt(line.quantity(), line.rate());
            item.setWorkPart(line.workPart());
            item.setCategory(line.category());
            item.setSource(request.source());
            item.setSynthetic(line.synthetic());
            item.setSortOrder(line.sortOrder());
            items.add(item);
            value = value.add(item.getContractAmount());
        }
        boqItems.saveAll(items);

        if (request.billingOnly()) {
            provisionBillingSite(project);
        }

        audit.record("PROJECT", project.getId(), "IMPORT_BOQ", null,
                Map.of("code", project.getCode(), "source", request.source(),
                        "lines", items.size(), "value", value), null);
        return new ProvisionResult(mapper.toResponse(project), items.size(), value);
    }

    /**
     * The one site a BILLING_ONLY project gets, and the posting that makes it readable.
     *
     * <p>{@link in.nirman.security.SiteAccessGuard} takes a site id, so a project with no sites
     * has nothing to scope on. The alternative to giving it one is a second access model beside
     * the first, which is how a system ends up with a hole in whichever of the two nobody
     * remembers to update — so it gets a site, and the billing screens never show a picker for
     * it. The same argument {@code createDefaultStore} already makes about stores.</p>
     *
     * <p><b>And the importer is posted to it in the same act.</b> Otherwise he creates a tender
     * he immediately cannot read, and the failure surfaces as a permissions error rather than
     * as the missing assignment it is.</p>
     */
    private void provisionBillingSite(Project project) {
        String code = uniqueSiteCode(project.getOrgId(), project.getCode());
        Site site = new Site(project.getOrgId(), project.getId(), code, project.getName());
        sites.save(site);

        UUID importer = currentUser.currentUserIdOrNull();
        if (importer != null) {
            staffing.updateSiteAccess(project.getOrgId(), site.getId(), Set.of(importer), Set.of());
        }
        audit.record("SITE", site.getId(), "CREATE", null,
                Map.of("code", site.getCode(), "projectId", project.getId().toString(),
                        "derivedFrom", "billing-only import"), null);
    }

    /** Site codes are unique per organisation, and two tenders may share a project code stem. */
    private String uniqueSiteCode(UUID orgId, String projectCode) {
        String base = ("S-" + projectCode);
        if (base.length() > 36) {
            base = base.substring(0, 36);
        }
        String candidate = base;
        int suffix = 2;
        while (sites.findByOrgIdAndCode(orgId, candidate).isPresent()) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private Project createProject(CreateProjectRequest request) {
        UUID orgId = currentUser.currentOrgId();
        projects.findByOrgIdAndCode(orgId, request.code()).ifPresent(existing -> {
            // A deleted project keeps its code, so that restoring it can never collide. Say
            // so rather than reporting a clash with something the user cannot see.
            if (existing.isDeleted()) {
                throw BusinessException.conflict("project.code-deleted",
                        "Code '" + request.code() + "' belongs to a deleted project. Restore "
                                + "that one from the deleted list, or use another code.");
            }
            throw BusinessException.conflict("project.code-taken",
                    "A project with code '" + request.code() + "' already exists.");
        });
        Project project = new Project(orgId, request.code(), request.name());
        project.setClientDepartment(request.clientDepartment());
        project.setAgreementNo(request.agreementNo());
        project.setNitNumber(request.nitNumber());
        project.setTenderReference(request.tenderReference());
        project.setContractValue(request.contractValue());
        project.setQuotedPercent(request.quotedPercent());
        project.setQuotedCost(request.quotedCost());
        project.setBudgetAmount(request.budgetAmount());
        project.setStartDate(request.startDate());
        project.setExpectedCompletionDate(request.expectedCompletionDate());
        applyContractCalendar(project, request.workNature(), request.defectLiabilityMonths());
        project.setProjectManagerId(request.projectManagerId());
        project.setDescription(request.description());
        validateDates(project);
        projects.save(project);
        audit.record("PROJECT", project.getId(), "CREATE", null,
                Map.of("code", project.getCode(), "name", project.getName()), null);
        return project;
    }

    @PreAuthorize("hasAuthority('project:write')")
    public ProjectResponse update(UUID id, UpdateProjectRequest request) {
        Project project = requireVisibleProject(id);
        if (!project.getVersion().equals(request.version())) {
            throw new OptimisticLockingFailureException("Project " + id + " was changed by someone else");
        }
        Map<String, Object> before = Map.of("name", project.getName(),
                "status", project.getStatus().name());
        project.setName(request.name());
        project.setClientDepartment(request.clientDepartment());
        project.setAgreementNo(request.agreementNo());
        project.setNitNumber(request.nitNumber());
        project.setTenderReference(request.tenderReference());
        project.setContractValue(request.contractValue());
        project.setQuotedPercent(request.quotedPercent());
        project.setQuotedCost(request.quotedCost());
        project.setBudgetAmount(request.budgetAmount());
        project.setStartDate(request.startDate());
        project.setExpectedCompletionDate(request.expectedCompletionDate());
        project.setActualCompletionDate(request.actualCompletionDate());
        applyContractCalendar(project, request.workNature(), request.defectLiabilityMonths());
        project.setProjectManagerId(request.projectManagerId());
        if (request.status() != null) {
            project.setStatus(request.status());
        }
        project.setDescription(request.description());
        validateDates(project);
        audit.record("PROJECT", project.getId(), "UPDATE", before,
                Map.of("name", project.getName(), "status", project.getStatus().name()), null);
        return mapper.toResponse(project);
    }

    /**
     * Takes a project and every site under it off the books, in one transaction.
     *
     * <p>The cascade is not a convenience. A site exists only inside a project, so leaving
     * the sites behind would leave rows on the sites screen pointing at a project nobody can
     * open — and making an administrator delete four sites by hand before the project will
     * go is busywork that invites them to leave the job half done.</p>
     *
     * <p>Every site is checked before any of them is touched, so a project held up by two of
     * its four sites says so once instead of failing twice.</p>
     */
    @PreAuthorize("hasAuthority('project:delete') and hasAuthority('site:delete')")
    public ProjectResponse delete(UUID id, String reason) {
        Project project = projects.findByIdAndOrgId(id, currentUser.currentOrgId())
                .orElseThrow(() -> BusinessException.notFound("Project", id));
        if (project.isDeleted()) {
            throw BusinessException.conflict("project.already-deleted",
                    "This project has already been deleted.");
        }
        List<Site> live = sites.findByOrgIdAndProjectIdAndDeletedAtIsNullOrderByCode(
                project.getOrgId(), project.getId());
        deletionGuard.assertProjectDeletable(project.getId(),
                live.stream().map(Site::getId).toList());

        // One instant for the project and its sites: that shared timestamp is what a restore
        // later reads to tell these sites apart from one deleted on its own months ago.
        Instant at = Instant.now();
        project.delete(at, currentUser.currentUserIdOrNull(), reason);
        live.forEach(site -> siteService.deleteWithProject(site, at, reason));

        audit.record("PROJECT", project.getId(), "DELETE",
                Map.of("code", project.getCode(), "name", project.getName()),
                Map.of("deletedAt", at.toString(), "reason", reason, "sites", live.size()),
                reason);
        return mapper.toResponse(project);
    }

    /**
     * Puts a project back, and with it exactly the sites that went down with it.
     *
     * <p>"Exactly" is the timestamp: a site deleted in the same act carries the project's
     * {@code deleted_at} to the microsecond, and one deleted on its own beforehand does not.
     * Restoring the project must not quietly resurrect a site somebody removed last month
     * for their own reasons.</p>
     */
    @PreAuthorize("hasAuthority('project:delete') and hasAuthority('site:delete')")
    public ProjectResponse restore(UUID id) {
        Project project = projects.findByIdAndOrgId(id, currentUser.currentOrgId())
                .orElseThrow(() -> BusinessException.notFound("Project", id));
        if (!project.isDeleted()) {
            throw BusinessException.conflict("project.not-deleted",
                    "This project is not deleted, so there is nothing to restore.");
        }
        Instant deletedAt = project.getDeletedAt();
        List<Site> cascaded = sites.findByOrgIdAndProjectIdOrderByCode(
                        project.getOrgId(), project.getId()).stream()
                .filter(site -> deletedAt.equals(site.getDeletedAt()))
                .toList();

        project.restore();
        cascaded.forEach(siteService::restoreWithProject);

        audit.record("PROJECT", project.getId(), "RESTORE", null,
                Map.of("code", project.getCode(), "name", project.getName(),
                        "sites", cascaded.size()), null);
        return mapper.toResponse(project);
    }

    /** Read access used by other modules (sites of a visible project, etc.). */
    @Transactional(readOnly = true)
    public boolean isVisible(UUID projectId) {
        return projects.findByIdAndOrgIdAndDeletedAtIsNull(projectId, currentUser.currentOrgId())
                .isPresent() && (seesAllSites() || visibleProjectIds().contains(projectId));
    }

    // ------------------------------------------------------------------ internals

    /**
     * What the treasury register's release schedule still reads off the contract itself.
     *
     * <p>V41 took the three letter dates out. Their ordering check went with them — it refused
     * an allotment letter dated before the bids were opened, which was the only way two of
     * these could contradict each other. The dates the schedule now counts from are the start
     * and expected completion, and {@link #validateDates} has always ordered that pair.</p>
     */
    private void applyContractCalendar(Project project, Project.WorkNature workNature,
                                       Integer defectLiabilityMonths) {
        project.setWorkNature(workNature);
        project.setDefectLiabilityMonths(defectLiabilityMonths);
    }

    private Project requireVisibleProject(UUID id) {
        Project project = projects.findByIdAndOrgIdAndDeletedAtIsNull(id, currentUser.currentOrgId())
                .orElseThrow(() -> BusinessException.notFound("Project", id));
        if (!seesAllSites() && !visibleProjectIds().contains(id)) {
            // 404, not 403: whether the project exists is itself scoped information.
            throw BusinessException.notFound("Project", id);
        }
        return project;
    }

    private boolean seesAllSites() {
        return currentUser.seesAllSites();
    }

    private List<UUID> visibleProjectIds() {
        Set<UUID> siteIds = currentUser.assignedSiteIds();
        return siteIds.isEmpty() ? List.of() : sites.findProjectIds(siteIds);
    }

    private static void validateDates(Project project) {
        if (project.getStartDate() != null && project.getExpectedCompletionDate() != null
                && project.getExpectedCompletionDate().isBefore(project.getStartDate())) {
            throw new BusinessException("project.dates",
                    "Expected completion cannot be before the start date.");
        }
    }

    /** No search term travels as an empty string, never null: see {@link ProjectRepository#search}. */
    private static String blankToEmpty(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}

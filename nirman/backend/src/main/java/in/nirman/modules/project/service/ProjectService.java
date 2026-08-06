package in.nirman.modules.project.service;

import in.nirman.common.BusinessException;
import in.nirman.common.PageResponse;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.project.api.dto.ProjectDtos.CreateProjectRequest;
import in.nirman.modules.project.api.dto.ProjectDtos.ProjectResponse;
import in.nirman.modules.project.api.dto.ProjectDtos.ProjectSummaryResponse;
import in.nirman.modules.project.api.dto.ProjectDtos.UpdateProjectRequest;
import in.nirman.modules.project.domain.BoqItem;
import in.nirman.modules.project.domain.Project;
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
    private final CurrentUserProvider currentUser;
    private final AuditService audit;
    private final ProjectMapper mapper;

    public ProjectService(ProjectRepository projects, SiteRepository sites, StoreRepository stores,
                          BoqItemRepository boqItems, CurrentUserProvider currentUser,
                          AuditService audit, ProjectMapper mapper) {
        this.projects = projects;
        this.sites = sites;
        this.stores = stores;
        this.boqItems = boqItems;
        this.currentUser = currentUser;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('project:read')")
    public PageResponse<ProjectResponse> list(Project.Status status, String q, Pageable pageable) {
        boolean restricted = !seesAllSites();
        List<UUID> visibleProjectIds = restricted ? visibleProjectIds() : List.of();
        return PageResponse.from(
                projects.search(currentUser.currentOrgId(), status, blankToEmpty(q),
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

        audit.record("PROJECT", project.getId(), "IMPORT_BOQ", null,
                Map.of("code", project.getCode(), "source", request.source(),
                        "lines", items.size(), "value", value), null);
        return new ProvisionResult(mapper.toResponse(project), items.size(), value);
    }

    private Project createProject(CreateProjectRequest request) {
        UUID orgId = currentUser.currentOrgId();
        if (projects.existsByOrgIdAndCode(orgId, request.code())) {
            throw BusinessException.conflict("project.code-taken",
                    "A project with code '" + request.code() + "' already exists.");
        }
        Project project = new Project(orgId, request.code(), request.name());
        project.setClientDepartment(request.clientDepartment());
        project.setAgreementNo(request.agreementNo());
        project.setNitNumber(request.nitNumber());
        project.setTenderReference(request.tenderReference());
        project.setContractValue(request.contractValue());
        project.setBudgetAmount(request.budgetAmount());
        project.setStartDate(request.startDate());
        project.setExpectedCompletionDate(request.expectedCompletionDate());
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
        project.setBudgetAmount(request.budgetAmount());
        project.setStartDate(request.startDate());
        project.setExpectedCompletionDate(request.expectedCompletionDate());
        project.setActualCompletionDate(request.actualCompletionDate());
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

    /** Read access used by other modules (sites of a visible project, etc.). */
    @Transactional(readOnly = true)
    public boolean isVisible(UUID projectId) {
        return projects.findByIdAndOrgIdAndDeletedAtIsNull(projectId, currentUser.currentOrgId())
                .isPresent() && (seesAllSites() || visibleProjectIds().contains(projectId));
    }

    // ------------------------------------------------------------------ internals

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

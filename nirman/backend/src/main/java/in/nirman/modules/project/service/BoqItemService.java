package in.nirman.modules.project.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.project.api.dto.BoqDtos.BoqItemResponse;
import in.nirman.modules.project.api.dto.BoqDtos.CreateBoqItemRequest;
import in.nirman.modules.project.api.dto.BoqDtos.UpdateBoqItemRequest;
import in.nirman.modules.project.domain.BoqItem;
import in.nirman.modules.project.repository.BoqItemRepository;
import in.nirman.modules.project.repository.ProjectRepository;
import in.nirman.modules.project.repository.SiteRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * BOQ items: the contract's list of work, and the spine every other module charges cost to.
 *
 * <p>Phase 4 needs creation and reading — a material estimate is scoped to a line and a
 * material issue is charged to one. Dated progress entries and the measurement-book
 * structure land in Phase 6; recording progress is a different permission
 * ({@code boq:progress:record}) precisely because entering the contract and claiming work
 * against it are different acts.</p>
 */
@Service
@Transactional
public class BoqItemService implements BoqLookup {

    private final BoqItemRepository items;
    private final ProjectRepository projects;
    private final SiteRepository sites;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public BoqItemService(BoqItemRepository items, ProjectRepository projects,
                          SiteRepository sites, CurrentUserProvider currentUser,
                          AuditService audit) {
        this.items = items;
        this.projects = projects;
        this.sites = sites;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    /**
     * The work items a caller may charge to, narrowed by whatever he named.
     *
     * <p><b>A site names a project.</b> Most lines of a bill of quantities carry no site —
     * the contract is written against the work, not against which block it is built on — so
     * asking by site has to return the project's untagged lines as well, or the picker on
     * every site screen is empty. What it must not return is another contract's untagged
     * lines, which is what it did: the engineer signing a report was offered every item in
     * the organisation and the server refused the one he chose with "belongs to a different
     * project", after he had typed the quantity. So a site is resolved to its project here
     * and the search is narrowed by both.</p>
     *
     * <p>Naming a project as well is allowed and must agree with the site. Two parameters
     * that contradict each other have no true answer — the honest empty list and the
     * project's untagged lines are both defensible, and picking either quietly is how a
     * screen ends up showing the wrong contract again.</p>
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('boq:read')")
    public List<BoqItemResponse> list(UUID projectId, UUID siteId, String category) {
        UUID scope = projectId;
        if (siteId != null) {
            UUID siteProject = sites.findByIdAndOrgIdAndDeletedAtIsNull(siteId, orgId())
                    .orElseThrow(() -> BusinessException.notFound("Site", siteId))
                    .getProjectId();
            if (scope != null && !scope.equals(siteProject)) {
                throw new BusinessException("boq.site-project-mismatch",
                        "That site is not on the project asked for.");
            }
            scope = siteProject;
        }
        return items.search(orgId(), scope, siteId, emptyToNull(category)).stream()
                .map(BoqItemService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('boq:read')")
    public BoqItemResponse get(UUID id) {
        return toResponse(require(id));
    }

    @PreAuthorize("hasAuthority('boq:write')")
    public BoqItemResponse create(CreateBoqItemRequest request) {
        projects.findByIdAndOrgIdAndDeletedAtIsNull(request.projectId(), orgId())
                .orElseThrow(() -> BusinessException.notFound("Project", request.projectId()));
        if (items.existsByProjectIdAndItemNumber(request.projectId(), request.itemNumber())) {
            throw BusinessException.conflict("boq.item-number-taken",
                    "Item " + request.itemNumber() + " already exists on this project.");
        }
        BoqItem item = new BoqItem(orgId(), request.projectId(), request.itemNumber(),
                request.description(), request.unitId());
        apply(item, request.siteId(), request.category(), request.workPart(),
                request.contractQuantity(), request.contractRate(), request.sortOrder());
        items.save(item);
        audit.record("BOQ_ITEM", item.getId(), "CREATE", null,
                Map.of("itemNumber", item.getItemNumber(), "projectId",
                        item.getProjectId().toString()), null);
        return toResponse(item);
    }

    @PreAuthorize("hasAuthority('boq:write')")
    public BoqItemResponse update(UUID id, UpdateBoqItemRequest request) {
        BoqItem item = require(id);
        if (!item.getVersion().equals(request.version())) {
            throw new OptimisticLockingFailureException("BOQ item " + id + " was changed by someone else");
        }
        item.setDescription(request.description());
        apply(item, request.siteId(), request.category(), request.workPart(),
                request.contractQuantity(), request.contractRate(), request.sortOrder());
        if (request.status() != null) {
            item.setStatus(request.status());
        }
        audit.record("BOQ_ITEM", id, "UPDATE", null,
                Map.of("itemNumber", item.getItemNumber(),
                        "contractAmount", item.getContractAmount()), null);
        return toResponse(item);
    }

    // ------------------------------------------------------------------ BoqLookup

    /**
     * {@inheritDoc}
     *
     * <p>No permission check: the caller is another module acting for a user who has
     * already passed its own. Requiring {@code boq:read} here would mean a supervisor
     * cannot charge an issue to the work item he is standing in front of.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public BoqItemInfo requireChargeable(UUID boqItemId) {
        BoqItem item = items.findByIdAndOrgIdAndDeletedAtIsNull(boqItemId, orgId())
                .orElseThrow(() -> BusinessException.notFound("BOQ item", boqItemId));
        if (item.isSynthetic()) {
            throw new BusinessException("boq.synthetic",
                    "Item " + item.getItemNumber() + " is a reconciliation placeholder, not work. "
                            + "Cost cannot be charged to it.");
        }
        return toInfo(item);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, BoqItemInfo> byIds(Collection<UUID> boqItemIds) {
        if (boqItemIds.isEmpty()) {
            return Map.of();
        }
        return items.findByIdInAndOrgIdAndDeletedAtIsNull(boqItemIds, orgId()).stream()
                .collect(Collectors.toMap(BoqItem::getId, BoqItemService::toInfo));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BoqItemInfo> forProject(UUID projectId) {
        return items.search(orgId(), projectId, null, null).stream()
                .map(BoqItemService::toInfo)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProgressSummary progress(UUID projectId, UUID siteId) {
        List<BoqItem> lines = items.search(orgId(), projectId, siteId, null).stream()
                .filter(item -> !item.isSynthetic())
                .toList();

        List<ItemProgress> rows = lines.stream().map(BoqItemService::toProgress).toList();
        BigDecimal contractValue = rows.stream().map(ItemProgress::contractAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal done = rows.stream().map(ItemProgress::valueOfWorkDone)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new ProgressSummary(contractValue, done,
                contractValue.signum() == 0 ? null : done.multiply(BigDecimal.valueOf(100))
                        .divide(contractValue, 2, RoundingMode.HALF_UP),
                rows.size(),
                (int) lines.stream().filter(item -> item.getStatus() == BoqItem.Status.COMPLETED).count(),
                (int) lines.stream().filter(item -> item.getStatus() == BoqItem.Status.IN_PROGRESS).count(),
                (int) lines.stream().filter(item -> item.overClaimedQuantity().signum() > 0).count(),
                rows);
    }

    // ------------------------------------------------------------------ internals

    private BoqItem require(UUID id) {
        return items.findByIdAndOrgIdAndDeletedAtIsNull(id, orgId())
                .orElseThrow(() -> BusinessException.notFound("BOQ item", id));
    }

    private static void apply(BoqItem item, UUID siteId, String category, String workPart,
                              java.math.BigDecimal quantity, java.math.BigDecimal rate,
                              Integer sortOrder) {
        item.setSiteId(siteId);
        item.setCategory(category);
        item.setWorkPart(workPart);
        item.priceAt(quantity, rate);
        if (sortOrder != null) {
            item.setSortOrder(sortOrder);
        }
    }

    /**
     * The value of work done is capped at the contract amount on purpose. An over-measurement
     * is real and is reported as one, but it is not automatically money the client owes — a
     * variation has to be agreed — and letting it through would have the dashboard announce
     * that a project is 110% complete.
     */
    private static ItemProgress toProgress(BoqItem item) {
        BigDecimal earned = item.getCompletedQuantity().multiply(item.getContractRate())
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal capped = earned.min(item.getContractAmount());
        return new ItemProgress(item.getId(), item.getItemNumber(), item.getDescription(),
                item.getContractQuantity(), item.getCompletedQuantity(),
                item.getContractQuantity().signum() == 0 ? null
                        : item.getCompletedQuantity().multiply(BigDecimal.valueOf(100))
                                .divide(item.getContractQuantity(), 2, RoundingMode.HALF_UP),
                item.overClaimedQuantity(), item.getContractAmount(), capped,
                item.getStatus().name());
    }

    private static BoqItemInfo toInfo(BoqItem item) {
        return new BoqItemInfo(item.getId(), item.getProjectId(), item.getSiteId(),
                item.getItemNumber(), item.getDescription(), item.getCategory(),
                item.getWorkPart(), item.getContractQuantity(), item.getUnitId(),
                item.getContractAmount(), item.isSynthetic());
    }

    private static BoqItemResponse toResponse(BoqItem item) {
        return new BoqItemResponse(item.getId(), item.getProjectId(), item.getSiteId(),
                item.getItemNumber(), item.getDescription(), item.getUnitId(),
                item.getContractQuantity(), item.getContractRate(), item.getContractAmount(),
                item.getCompletedQuantity(), item.getStatus(), item.getWorkPart(),
                item.getCategory(), item.isSynthetic(), item.getSortOrder(), item.getVersion());
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

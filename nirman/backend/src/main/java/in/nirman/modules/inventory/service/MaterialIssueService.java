package in.nirman.modules.inventory.service;

import in.nirman.common.BusinessException;
import in.nirman.common.DocumentNumberService;
import in.nirman.common.PageResponse;
import in.nirman.common.PeriodLockGuard;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.inventory.api.dto.InventoryDtos.ApproveIssueRequest;
import in.nirman.modules.inventory.api.dto.InventoryDtos.CreateIssueRequest;
import in.nirman.modules.inventory.api.dto.InventoryDtos.IssueLine;
import in.nirman.modules.inventory.api.dto.InventoryDtos.IssueResponse;
import in.nirman.modules.inventory.domain.DocumentWorkflow;
import in.nirman.modules.inventory.domain.MaterialIssue;
import in.nirman.modules.inventory.domain.MaterialIssueItem;
import in.nirman.modules.inventory.domain.StockTransaction;
import in.nirman.modules.inventory.domain.StockTransaction.SourceType;
import in.nirman.modules.inventory.domain.StockTransaction.TxnType;
import in.nirman.modules.inventory.repository.MaterialIssueItemRepository;
import in.nirman.modules.inventory.repository.MaterialIssueRepository;
import in.nirman.modules.masterdata.service.MaterialLookup;
import in.nirman.modules.project.service.BoqLookup;
import in.nirman.modules.project.service.SiteLookup;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Material leaving the store for the work face.
 *
 * <p>This is the document the consumption half of every dashboard depends on, and the one
 * the field has never filled in. The Kausani register records thirty-two deliveries and not
 * one issue (docs/09 section B): receipts get written down because a vendor wants paying,
 * and nobody chases a storekeeper for an issue slip. That is an adoption problem, not a
 * schema gap, and it shapes the rules here — everything that can be optional is, and the
 * two things that cannot are the two without which the document is worthless.</p>
 *
 * <p><b>Somewhere to charge it.</b> Either a BOQ item or a written purpose. Material that
 * left the store and can be charged to nothing is exactly how "cost incurred against work
 * item X" stops being answerable, which is the one question this system exists to answer.</p>
 *
 * <p><b>A rate frozen at the moment it leaves.</b> The store's moving average is written
 * onto each line when the issue is approved, so a delivery arriving next week cannot
 * retroactively reprice material that is already in the wall.</p>
 */
@Service
@Transactional
public class MaterialIssueService {

    private final MaterialIssueRepository issues;
    private final MaterialIssueItemRepository lines;
    private final StockLedgerService ledger;
    private final MaterialLookup materials;
    private final SiteLookup sites;
    private final BoqLookup boqItems;
    private final SiteAccessGuard siteAccessGuard;
    private final PeriodLockGuard periodLockGuard;
    private final DocumentNumberService documentNumbers;
    private final InventoryResponses responses;
    private final MaterialEvidencePolicy evidence;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public MaterialIssueService(MaterialIssueRepository issues, MaterialIssueItemRepository lines,
                                StockLedgerService ledger, MaterialLookup materials,
                                SiteLookup sites, BoqLookup boqItems,
                                SiteAccessGuard siteAccessGuard, PeriodLockGuard periodLockGuard,
                                DocumentNumberService documentNumbers, InventoryResponses responses,
                                MaterialEvidencePolicy evidence,
                                CurrentUserProvider currentUser, AuditService audit) {
        this.issues = issues;
        this.lines = lines;
        this.ledger = ledger;
        this.materials = materials;
        this.sites = sites;
        this.boqItems = boqItems;
        this.siteAccessGuard = siteAccessGuard;
        this.periodLockGuard = periodLockGuard;
        this.documentNumbers = documentNumbers;
        this.responses = responses;
        this.evidence = evidence;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('inventory:read')")
    public PageResponse<IssueResponse> list(UUID siteId, UUID storeId, UUID boqItemId,
                                            DocumentWorkflow status, LocalDate from, LocalDate to,
                                            Pageable pageable) {
        if (siteId != null) {
            siteAccessGuard.assertCanAccess(siteId);
        }
        boolean restricted = !currentUser.seesAllSites();
        Collection<UUID> visible = restricted ? currentUser.assignedSiteIds() : List.of();
        if (restricted && visible.isEmpty()) {
            return new PageResponse<>(List.of(), 0, pageable.getPageSize(), 0, 0, true, true);
        }
        return PageResponse.from(
                issues.search(orgId(), siteId, storeId, boqItemId, status, from, to,
                        restricted, visible, pageable),
                responses::toIssueResponse);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('inventory:read')")
    public IssueResponse get(UUID id) {
        MaterialIssue issue = require(id);
        siteAccessGuard.assertCanAccess(issue.getSiteId());
        return responses.toIssueResponse(issue);
    }

    /**
     * Records an issue. Idempotent on the client-generated id.
     *
     * <p>Stock does not move yet: it moves on approval, for the same reason a receipt waits
     * for verification. What the check here does is refuse an issue the store plainly
     * cannot cover, so the supervisor finds out at the moment he writes it rather than
     * after the engineer has signed it.</p>
     */
    @PreAuthorize("hasAuthority('inventory:issue')")
    public IssueResponse create(CreateIssueRequest request) {
        SiteLookup.StoreInfo store = sites.requireStore(request.storeId());
        periodLockGuard.assertOpen(store.siteId(), request.issueDate(),
                PeriodLockGuard.Module.INVENTORY);
        requireSomewhereToChargeIt(request);

        var existing = issues.findByIdAndOrgId(request.id(), orgId());
        if (existing.isPresent()) {
            return responses.toIssueResponse(existing.get());   // the offline replay
        }
        if (request.boqItemId() != null) {
            requireSameProject(boqItems.requireChargeable(request.boqItemId()), store);
        }

        String number = documentNumbers.next(orgId(), DocumentNumberService.DocType.MATERIAL_ISSUE,
                request.issueDate());
        MaterialIssue issue = new MaterialIssue(request.id(), orgId(), store.projectId(),
                store.siteId(), store.id(), number, request.issueDate());
        issue.setBoqItemId(request.boqItemId());
        issue.setPurpose(request.purpose());
        issue.setIssuedToName(request.issuedToName());
        issue.setIssuedToSupplierId(request.issuedToSupplierId());
        issue.setWorkLocation(request.workLocation());
        issue.submit();
        // Header before lines: the lines carry a foreign key to it.
        issues.save(issue);

        List<MaterialIssueItem> saved = saveLines(issue, request.lines(), store);
        // The picture, after the header exists to hang it off. See MaterialEvidencePolicy.
        evidence.attachToIssue(issue.getId(), request.materialPhotoId());

        audit.record("MATERIAL_ISSUE", issue.getId(), "CREATE", null,
                Map.of("issueNumber", number, "storeId", store.id().toString(),
                        "lines", saved.size()), null);
        return responses.toIssueResponse(issue);
    }

    /**
     * Sign-off. Approving is what takes the material out of the store: one ledger row per
     * line, valued at the store's average as it stands, and that average frozen onto the
     * line so the month's consumption cost stops moving.
     */
    @PreAuthorize("hasAuthority('inventory:verify')")
    public IssueResponse decide(UUID id, ApproveIssueRequest request) {
        MaterialIssue issue = require(id);
        siteAccessGuard.assertCanAccess(issue.getSiteId());
        periodLockGuard.assertOpen(issue.getSiteId(), issue.getIssueDate(),
                PeriodLockGuard.Module.INVENTORY);

        if (issue.getWorkflowStatus() != DocumentWorkflow.SUBMITTED
                && issue.getWorkflowStatus() != DocumentWorkflow.DRAFT) {
            throw new BusinessException("issue.already-decided",
                    "Issue " + issue.getIssueNumber() + " is already "
                            + issue.getWorkflowStatus().name().toLowerCase() + ".");
        }

        if (request.action() == ApproveIssueRequest.Action.REJECT) {
            issue.reject(currentUser.currentUserIdOrNull(), request.remarks());
            audit.record("MATERIAL_ISSUE", id, "REJECT", null,
                    Map.of("issueNumber", issue.getIssueNumber()), request.remarks());
            return responses.toIssueResponse(issue);
        }

        issue.approve(Instant.now(), currentUser.currentUserIdOrNull());
        BigDecimal totalValue = BigDecimal.ZERO;
        for (MaterialIssueItem line : lines.findByIssueId(issue.getId())) {
            UUID chargeTo = line.getBoqItemId() != null ? line.getBoqItemId() : issue.getBoqItemId();
            StockTransaction posted = ledger.post(new StockLedgerService.Movement(
                    issue.getOrgId(), issue.getProjectId(), issue.getSiteId(), issue.getStoreId(),
                    line.getMaterialId(), TxnType.ISSUE, issue.getIssueDate(),
                    line.getQuantityBase(), null, SourceType.ISSUE, issue.getId(), line.getId(),
                    chargeTo, issue.getPurpose()));
            if (posted != null) {
                line.valueAt(posted.getRateBase());
                totalValue = totalValue.add(line.getValue());
            }
        }

        audit.record("MATERIAL_ISSUE", id, "APPROVE", null,
                Map.of("issueNumber", issue.getIssueNumber(), "value", totalValue),
                request.remarks());
        return responses.toIssueResponse(issue);
    }

    @PreAuthorize("hasAuthority('inventory:issue')")
    public IssueResponse cancel(UUID id, String reason) {
        MaterialIssue issue = require(id);
        siteAccessGuard.assertCanAccess(issue.getSiteId());
        if (issue.getWorkflowStatus().hasPosted()) {
            throw new BusinessException("issue.already-posted",
                    "Issue " + issue.getIssueNumber() + " has already taken material out of the "
                            + "store. Book a return or a stock adjustment instead.");
        }
        issue.cancel(reason);
        audit.record("MATERIAL_ISSUE", id, "CANCEL", null,
                Map.of("issueNumber", issue.getIssueNumber()), reason);
        return responses.toIssueResponse(issue);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Either a work item or a sentence saying what it was for. Not expressible as a field
     * annotation, and not negotiable: the alternative is a store ledger that balances
     * against a cost report that cannot be produced.
     */
    private static void requireSomewhereToChargeIt(CreateIssueRequest request) {
        boolean hasLinePurpose = request.lines().stream()
                .anyMatch(line -> line.boqItemId() != null);
        boolean hasPurpose = request.purpose() != null && !request.purpose().isBlank();
        if (request.boqItemId() == null && !hasLinePurpose && !hasPurpose) {
            throw new BusinessException("issue.no-charge-target",
                    "An issue needs either a work item to charge the material to, or a note "
                            + "saying what it was for.");
        }
    }

    private void requireSameProject(BoqLookup.BoqItemInfo item, SiteLookup.StoreInfo store) {
        if (!item.projectId().equals(store.projectId())) {
            throw new BusinessException("issue.boq-other-project",
                    "Item " + item.itemNumber() + " belongs to a different project from this store.");
        }
    }

    /**
     * Builds the lines, and refuses one the store cannot cover before anything is written.
     *
     * <p>Approval will check again under a row lock — that is the check that actually
     * guarantees the balance — but rejecting here means the supervisor is told while he is
     * still looking at the slip, rather than after his engineer has signed it.</p>
     */
    private List<MaterialIssueItem> saveLines(MaterialIssue issue, List<IssueLine> requested,
                                              SiteLookup.StoreInfo store) {
        List<MaterialIssueItem> built = new ArrayList<>();
        for (IssueLine line : requested) {
            if (line.boqItemId() != null) {
                requireSameProject(boqItems.requireChargeable(line.boqItemId()), store);
            }
            BigDecimal factor = materials.factorToBase(line.materialId(), line.unitId());
            MaterialIssueItem item = new MaterialIssueItem(issue.getId(), line.materialId(),
                    line.unitId(), line.quantity(), factor, line.boqItemId());
            item.setRemarks(line.remarks());
            assertStoreCanCover(issue.getStoreId(), line.materialId(), item.getQuantityBase());
            built.add(item);
        }
        return lines.saveAll(built);
    }

    private void assertStoreCanCover(UUID storeId, UUID materialId, BigDecimal quantityBase) {
        BigDecimal available = ledger.available(storeId, materialId);
        if (available.compareTo(quantityBase) < 0) {
            throw new BusinessException("stock.insufficient",
                    "%s holds %s of %s and this slip takes out %s."
                            .formatted(storeName(storeId), available.toPlainString(),
                                    materials.require(materialId).name(),
                                    quantityBase.toPlainString()));
        }
    }

    private String storeName(UUID storeId) {
        return sites.findStore(storeId).map(SiteLookup.StoreInfo::name).orElse("This store");
    }

    private MaterialIssue require(UUID id) {
        return issues.findByIdAndOrgId(id, orgId())
                .orElseThrow(() -> BusinessException.notFound("Material issue", id));
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }
}

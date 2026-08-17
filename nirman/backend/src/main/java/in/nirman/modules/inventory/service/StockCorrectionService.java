package in.nirman.modules.inventory.service;

import in.nirman.common.BusinessException;
import in.nirman.common.PeriodLockGuard;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.inventory.api.dto.InventoryDtos.AdjustmentRequest;
import in.nirman.modules.inventory.api.dto.InventoryDtos.DecideStockCorrectionRequest;
import in.nirman.modules.inventory.api.dto.InventoryDtos.LedgerRow;
import in.nirman.modules.inventory.api.dto.InventoryDtos.StockCorrectionRequestBody;
import in.nirman.modules.inventory.api.dto.InventoryDtos.StockCorrectionResponse;
import in.nirman.modules.inventory.domain.StockCorrectionRequest;
import in.nirman.modules.inventory.domain.StockCorrectionRequest.Status;
import in.nirman.modules.inventory.repository.StockCorrectionRequestRepository;
import in.nirman.modules.masterdata.service.MaterialLookup;
import in.nirman.modules.project.service.SiteLookup;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A stock figure put right by the two people it takes: the one who can see the shed, and the
 * one who is allowed to move a balance.
 *
 * <p><b>Nothing here writes stock.</b> A request is paperwork; accepting one calls
 * {@link StockAdjustmentService#adjust} and the ledger is entered through the same door as
 * always — same period lock, same refusal to drive a balance below zero, same signed
 * ADJUSTMENT row that anybody reading the store's history will find. That is deliberate:
 * every argument for keeping {@code inventory:adjust} in the office survives this feature
 * intact, because the office still makes every posting.</p>
 *
 * <p>What changes is that the storekeeper has a sentence to say. Twelve bags booked and eleven
 * delivered, an issue typed against the wrong material — he could see it and had no way to
 * report it except the telephone, so mostly the balance stayed wrong until a physical count
 * found it a quarter later. The count is not the correction; it is the request for one.</p>
 *
 * <p><b>The period lock is asked twice, and that is not redundant.</b> Once when the request
 * is raised, so a storekeeper is refused at the moment he can still do something about it
 * rather than a week later through somebody else; and once inside the posting, because the
 * month may have closed in between and the ledger's guard is the one that counts.</p>
 */
@Service
@Transactional
public class StockCorrectionService {

    private final StockCorrectionRequestRepository requests;
    private final StockAdjustmentService adjustments;
    private final SiteLookup sites;
    private final MaterialLookup materials;
    private final SiteAccessGuard siteAccessGuard;
    private final PeriodLockGuard periodLockGuard;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public StockCorrectionService(StockCorrectionRequestRepository requests,
                                  StockAdjustmentService adjustments,
                                  SiteLookup sites, MaterialLookup materials,
                                  SiteAccessGuard siteAccessGuard,
                                  PeriodLockGuard periodLockGuard,
                                  CurrentUserProvider currentUser, AuditService audit) {
        this.requests = requests;
        this.adjustments = adjustments;
        this.sites = sites;
        this.materials = materials;
        this.siteAccessGuard = siteAccessGuard;
        this.periodLockGuard = periodLockGuard;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('inventory:read')")
    public List<StockCorrectionResponse> list(UUID siteId, UUID storeId, Status status) {
        if (siteId != null) {
            siteAccessGuard.assertCanAccess(siteId);
        }
        if (storeId != null) {
            // Resolving the store is the access check for it: requireStore refuses a store
            // whose site the caller is not posted to.
            siteAccessGuard.assertCanAccess(sites.requireStore(storeId).siteId());
        }
        boolean restricted = !currentUser.seesAllSites();
        Collection<UUID> visible = restricted ? currentUser.assignedSiteIds() : List.of();
        if (restricted && visible.isEmpty()) {
            return List.of();
        }
        return toResponses(requests.search(orgId(), siteId, storeId, status, restricted, visible));
    }

    /**
     * Raising one.
     *
     * <p>Idempotent on the client-generated id, like every other entry the field makes: a
     * count typed in a shed with no signal and synced three times is one request, not three
     * corrections waiting in the queue for somebody to accept all of them.</p>
     */
    @PreAuthorize("hasAuthority('inventory:correct')")
    public StockCorrectionResponse raise(StockCorrectionRequestBody request) {
        var replay = requests.findByIdAndOrgId(request.id(), orgId());
        if (replay.isPresent()) {
            return toResponses(List.of(replay.get())).get(0);
        }

        if (request.quantityDelta().signum() == 0) {
            throw new BusinessException("stock-correction.zero",
                    "A correction of nothing is not a correction. Say how much the figure is "
                            + "out by, and which way.");
        }
        SiteLookup.StoreInfo store = sites.requireStore(request.storeId());
        siteAccessGuard.assertCanAccess(store.siteId());
        periodLockGuard.assertOpen(store.siteId(), request.correctionDate(),
                PeriodLockGuard.Module.INVENTORY);
        // Resolving the conversion is the check that the unit belongs to the material: a
        // figure in a unit the material has never been measured in is not a quantity.
        materials.factorToBase(request.materialId(), request.unitId());

        /*
          One open request per store and material. A second one is nearly always the same man
          asking again because nobody has answered the first, and the way that ends is with an
          administrator accepting both and writing the correction on twice.
        */
        if (!requests.findByOrgIdAndStoreIdAndMaterialIdAndStatus(
                orgId(), store.id(), request.materialId(), Status.PENDING).isEmpty()) {
            throw BusinessException.conflict("stock-correction.already-waiting",
                    "A correction for this material at this store is already waiting for the "
                            + "office. Adding a second one would have the figure corrected "
                            + "twice.");
        }

        StockCorrectionRequest raised = new StockCorrectionRequest(request.id(), orgId(),
                store.siteId(), store.id(), request.materialId(), request.unitId(),
                request.quantityDelta(), request.correctionDate(), request.reason().trim());
        requests.save(raised);

        audit.record("STOCK_CORRECTION", raised.getId(), "RAISE", null,
                Map.of("storeId", store.id().toString(),
                        "materialId", request.materialId().toString(),
                        "quantityDelta", request.quantityDelta()),
                request.reason());
        return toResponses(List.of(raised)).get(0);
    }

    /**
     * The office answering it.
     *
     * <p>Accepting posts the adjustment first and records the decision second, so a posting the
     * ledger refuses — a month closed since Tuesday, a write-off that would take the balance
     * below zero — leaves the request exactly where it was rather than marking it accepted
     * against a correction that never happened. The transaction would roll both back anyway;
     * the order is for the person reading this later.</p>
     *
     * <p>Held by {@code inventory:adjust} rather than a permission of its own. Accepting is
     * posting: it produces the same signed ADJUSTMENT an administrator could have typed
     * himself, and a separate approval permission would let an organisation grant the
     * decision to somebody who cannot make the posting it commits them to.</p>
     */
    @PreAuthorize("hasAuthority('inventory:adjust')")
    public StockCorrectionResponse decide(UUID id, DecideStockCorrectionRequest request) {
        StockCorrectionRequest raised = require(id);
        siteAccessGuard.assertCanAccess(raised.getSiteId());
        if (raised.getStatus() != Status.PENDING) {
            throw new BusinessException("stock-correction.already-decided",
                    "That correction has already been "
                            + raised.getStatus().name().toLowerCase() + ".");
        }

        Instant now = Instant.now();
        UUID by = currentUser.currentUserIdOrNull();
        String remarks = trimToNull(request.remarks());

        if (request.action() == DecideStockCorrectionRequest.Action.REJECT) {
            raised.reject(now, by, remarks);
            audit.record("STOCK_CORRECTION", id, "REJECT", null,
                    Map.of("storeId", raised.getStoreId().toString()), remarks);
            return toResponses(List.of(raised)).get(0);
        }

        /*
          Through the ordinary adjustment, and with the requester's own words carried into the
          ledger's reason. A store's history that says "adjustment" and nothing else is the
          history nobody can settle an argument with; "two bags short on the 12th, counted by
          the storekeeper" is.
        */
        LedgerRow posted = adjustments.adjust(new AdjustmentRequest(raised.getStoreId(),
                raised.getMaterialId(), raised.getUnitId(), raised.getQuantityDelta(),
                raised.getCorrectionDate(), raised.getReason()));

        raised.accept(posted.id(), now, by, remarks);
        audit.record("STOCK_CORRECTION", id, "ACCEPT", null,
                Map.of("storeId", raised.getStoreId().toString(),
                        "postedTxnId", posted.id().toString(),
                        "quantityDelta", raised.getQuantityDelta()), remarks);
        return toResponses(List.of(raised)).get(0);
    }

    // ------------------------------------------------------------------ internals

    /** Names resolved in one batch: forty requests would otherwise ask eighty questions. */
    private List<StockCorrectionResponse> toResponses(List<StockCorrectionRequest> rows) {
        Set<UUID> materialIds = rows.stream().map(StockCorrectionRequest::getMaterialId)
                .collect(Collectors.toSet());
        Map<UUID, MaterialLookup.MaterialInfo> materialsById = materials.byIds(materialIds);
        Map<UUID, String> unitCodes = materials.unitCodes(
                rows.stream().map(StockCorrectionRequest::getUnitId).collect(Collectors.toSet()));

        Set<UUID> siteIds = rows.stream().map(StockCorrectionRequest::getSiteId)
                .collect(Collectors.toCollection(HashSet::new));
        Map<UUID, String> storeNames = sites.storesAtSites(siteIds).stream()
                .collect(Collectors.toMap(SiteLookup.StoreInfo::id, SiteLookup.StoreInfo::name,
                        (first, second) -> first));

        return rows.stream()
                .map(row -> new StockCorrectionResponse(row.getId(), row.getSiteId(),
                        row.getStoreId(), storeNames.get(row.getStoreId()), row.getMaterialId(),
                        nameOf(materialsById, row.getMaterialId()), row.getUnitId(),
                        unitCodes.get(row.getUnitId()),
                        row.getQuantityDelta(), row.getCorrectionDate(), row.getReason(),
                        row.getStatus(), row.getPostedTxnId(), row.getDecidedAt(),
                        row.getDecisionRemarks(), row.getCreatedAt(), row.getCreatedBy(),
                        row.getVersion()))
                .toList();
    }

    /**
     * A material the lookup no longer answers for is one somebody deactivated after the count
     * was typed. The request still has to render — it is the office's queue — so the row says
     * so rather than throwing on the way to the screen.
     */
    private static String nameOf(Map<UUID, MaterialLookup.MaterialInfo> byId, UUID materialId) {
        MaterialLookup.MaterialInfo info = byId.get(materialId);
        return info == null ? "Material no longer in the catalogue" : info.name();
    }

    private StockCorrectionRequest require(UUID id) {
        return requests.findByIdAndOrgId(id, orgId())
                .orElseThrow(() -> BusinessException.notFound("Stock correction", id));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }
}

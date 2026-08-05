package in.nirman.modules.inventory.service;

import in.nirman.common.BusinessException;
import in.nirman.common.DocumentNumberService;
import in.nirman.common.PageResponse;
import in.nirman.common.PeriodLockGuard;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.inventory.api.dto.InventoryDtos.CountLine;
import in.nirman.modules.inventory.api.dto.InventoryDtos.CountResponse;
import in.nirman.modules.inventory.api.dto.InventoryDtos.CreateCountRequest;
import in.nirman.modules.inventory.api.dto.InventoryDtos.DecideCountRequest;
import in.nirman.modules.inventory.domain.DocumentWorkflow;
import in.nirman.modules.inventory.domain.PhysicalStockCount;
import in.nirman.modules.inventory.domain.PhysicalStockCountItem;
import in.nirman.modules.inventory.domain.StockTransaction;
import in.nirman.modules.inventory.domain.StockTransaction.SourceType;
import in.nirman.modules.inventory.domain.StockTransaction.TxnType;
import in.nirman.modules.inventory.repository.PhysicalStockCountItemRepository;
import in.nirman.modules.inventory.repository.PhysicalStockCountRepository;
import in.nirman.modules.project.service.SiteLookup;
import in.nirman.security.CurrentUserProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Counting what is actually on the floor, against what the ledger says should be.
 *
 * <p>The count never writes a balance. Approving it posts an {@code ADJUSTMENT} through the
 * ledger for every line that differs, and the balance moves because the ledger moved. That
 * is the whole point of docs/03 rule 3, and this is the place where breaking it would be
 * most tempting: a count is precisely the moment somebody wants to declare a number and
 * have the system agree.</p>
 *
 * <p>Counting and accepting the count are split across two permissions on purpose.
 * {@code inventory:issue} raises the sheet — a storekeeper counts his own store — and
 * {@code inventory:adjust}, which only the administrator holds, accepts it. A count is
 * where stock losses get written off, so the person who loses the material must not be the
 * person who signs off the loss.</p>
 */
@Service
@Transactional
public class StockCountService {

    private final PhysicalStockCountRepository counts;
    private final PhysicalStockCountItemRepository lines;
    private final StockLedgerService ledger;
    private final SiteLookup sites;
    private final PeriodLockGuard periodLockGuard;
    private final DocumentNumberService documentNumbers;
    private final InventoryResponses responses;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public StockCountService(PhysicalStockCountRepository counts,
                             PhysicalStockCountItemRepository lines, StockLedgerService ledger,
                             SiteLookup sites, PeriodLockGuard periodLockGuard,
                             DocumentNumberService documentNumbers, InventoryResponses responses,
                             CurrentUserProvider currentUser, AuditService audit) {
        this.counts = counts;
        this.lines = lines;
        this.ledger = ledger;
        this.sites = sites;
        this.periodLockGuard = periodLockGuard;
        this.documentNumbers = documentNumbers;
        this.responses = responses;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('inventory:read')")
    public PageResponse<CountResponse> list(UUID storeId, Pageable pageable) {
        if (storeId != null) {
            sites.requireStore(storeId);
        }
        boolean restricted = !currentUser.seesAllSites();
        Collection<UUID> visible = restricted ? myStoreIds() : List.of();
        if (restricted && visible.isEmpty()) {
            return new PageResponse<>(List.of(), 0, pageable.getPageSize(), 0, 0, true, true);
        }
        return PageResponse.from(counts.search(orgId(), storeId, restricted, visible, pageable),
                responses::toCountResponse);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('inventory:read')")
    public CountResponse get(UUID id) {
        PhysicalStockCount count = require(id);
        sites.requireStore(count.getStoreId());
        return responses.toCountResponse(count);
    }

    /**
     * Records a count sheet. The system quantity is frozen onto each line as it stands now,
     * so the variance stays reproducible even if the store moves again before anybody
     * approves it — the alternative is a variance that changes while it waits for a
     * signature.
     */
    @PreAuthorize("hasAuthority('inventory:issue')")
    public CountResponse create(CreateCountRequest request) {
        SiteLookup.StoreInfo store = sites.requireStore(request.storeId());
        periodLockGuard.assertOpen(store.siteId(), request.countDate(),
                PeriodLockGuard.Module.INVENTORY);

        String number = documentNumbers.next(orgId(), DocumentNumberService.DocType.STOCK_COUNT,
                request.countDate());
        PhysicalStockCount count = new PhysicalStockCount(orgId(), store.id(), number,
                request.countDate(), currentUser.currentUserIdOrNull());
        count.setRemarks(request.remarks());
        counts.save(count);

        List<PhysicalStockCountItem> built = new ArrayList<>();
        for (CountLine line : request.lines()) {
            BigDecimal system = ledger.available(store.id(), line.materialId());
            built.add(new PhysicalStockCountItem(count.getId(), line.materialId(), system,
                    line.countedQuantityBase(), line.varianceReason()));
        }
        lines.saveAll(built);

        audit.record("STOCK_COUNT", count.getId(), "CREATE", null,
                Map.of("countNumber", number, "storeId", store.id().toString(),
                        "lines", built.size()), request.remarks());
        return responses.toCountResponse(count);
    }

    /**
     * Accepts or refuses the count. Accepting posts one adjustment per differing line,
     * through the ledger like everything else.
     *
     * <p>Lines that agree with the ledger post nothing at all. A count that found exactly
     * what was expected is the normal outcome and should leave no trace in the ledger, or
     * every count would bury the movements that matter under a page of zeroes.</p>
     */
    @PreAuthorize("hasAuthority('inventory:adjust')")
    public CountResponse decide(UUID id, DecideCountRequest request) {
        PhysicalStockCount count = require(id);
        SiteLookup.StoreInfo store = sites.requireStore(count.getStoreId());
        periodLockGuard.assertOpen(store.siteId(), count.getCountDate(),
                PeriodLockGuard.Module.INVENTORY);
        if (count.getStatus() != DocumentWorkflow.SUBMITTED) {
            throw new BusinessException("count.already-decided",
                    "Count " + count.getCountNumber() + " is already "
                            + count.getStatus().name().toLowerCase() + ".");
        }

        if (request.action() == DecideCountRequest.Action.REJECT) {
            count.reject(currentUser.currentUserIdOrNull(), request.remarks());
            audit.record("STOCK_COUNT", id, "REJECT", null,
                    Map.of("countNumber", count.getCountNumber()), request.remarks());
            return responses.toCountResponse(count);
        }

        count.approve(Instant.now(), currentUser.currentUserIdOrNull());
        int adjusted = 0;
        for (PhysicalStockCountItem line : lines.findByCountId(count.getId())) {
            BigDecimal variance = line.variance();
            if (variance.signum() == 0) {
                continue;
            }
            short direction = variance.signum() > 0 ? StockTransaction.IN : StockTransaction.OUT;
            BigDecimal rate = direction == StockTransaction.IN
                    ? ledger.currentRate(store.id(), line.getMaterialId())
                    : null;
            String reason = "Physical count " + count.getCountNumber()
                    + (line.getVarianceReason() == null ? "" : ": " + line.getVarianceReason());
            ledger.post(new StockLedgerService.Movement(count.getOrgId(), store.projectId(),
                    store.siteId(), store.id(), line.getMaterialId(), TxnType.ADJUSTMENT,
                    count.getCountDate(), variance.abs(), rate, SourceType.COUNT, count.getId(),
                    line.getId(), null, reason), direction);
            adjusted++;
        }

        audit.record("STOCK_COUNT", id, "APPROVE", null,
                Map.of("countNumber", count.getCountNumber(), "linesAdjusted", adjusted),
                request.remarks());
        return responses.toCountResponse(count);
    }

    // ------------------------------------------------------------------ internals

    private Collection<UUID> myStoreIds() {
        return sites.storesAtSites(currentUser.assignedSiteIds()).stream()
                .map(SiteLookup.StoreInfo::id)
                .toList();
    }

    private PhysicalStockCount require(UUID id) {
        return counts.findByIdAndOrgId(id, orgId())
                .orElseThrow(() -> BusinessException.notFound("Stock count", id));
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }
}

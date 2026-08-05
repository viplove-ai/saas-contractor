package in.nirman.modules.inventory.service;

import in.nirman.common.BusinessException;
import in.nirman.common.PeriodLockGuard;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.inventory.api.dto.InventoryDtos.AdjustmentRequest;
import in.nirman.modules.inventory.api.dto.InventoryDtos.LedgerRow;
import in.nirman.modules.inventory.api.dto.InventoryDtos.OpeningStockRequest;
import in.nirman.modules.inventory.api.dto.InventoryDtos.WastageRequest;
import in.nirman.modules.inventory.domain.StockTransaction;
import in.nirman.modules.inventory.domain.StockTransaction.SourceType;
import in.nirman.modules.inventory.domain.StockTransaction.TxnType;
import in.nirman.modules.inventory.repository.StockTransactionRepository;
import in.nirman.modules.masterdata.service.MaterialLookup;
import in.nirman.modules.project.service.BoqLookup;
import in.nirman.modules.project.service.SiteLookup;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * The three ways stock moves without a two-party document behind it.
 *
 * <p>Each is deliberately harder to reach than a receipt or an issue, because each is a
 * statement that only one person is making.</p>
 *
 * <ul>
 *   <li><b>Opening stock</b> — "this much was already here when we started counting". The
 *       only ledger row nobody can derive, and therefore once per store and material, for
 *       ever. A second one silently doubles a balance that no document can explain.</li>
 *   <li><b>Wastage and damage</b> — material that left the store and went into nothing.
 *       Always with a reason: stock that disappears without one is indistinguishable from
 *       stock that walked out of the gate.</li>
 *   <li><b>Adjustment</b> — administrator only, and the only movement that can go either
 *       way on request. It is the correction of last resort, which is exactly why it is the
 *       most tightly held.</li>
 * </ul>
 */
@Service
@Transactional
public class StockAdjustmentService {

    private final StockLedgerService ledger;
    private final StockTransactionRepository transactions;
    private final MaterialLookup materials;
    private final SiteLookup sites;
    private final BoqLookup boqItems;
    private final PeriodLockGuard periodLockGuard;
    private final InventoryReads reads;
    private final AuditService audit;

    public StockAdjustmentService(StockLedgerService ledger,
                                  StockTransactionRepository transactions,
                                  MaterialLookup materials, SiteLookup sites, BoqLookup boqItems,
                                  PeriodLockGuard periodLockGuard, InventoryReads reads,
                                  AuditService audit) {
        this.ledger = ledger;
        this.transactions = transactions;
        this.materials = materials;
        this.sites = sites;
        this.boqItems = boqItems;
        this.periodLockGuard = periodLockGuard;
        this.reads = reads;
        this.audit = audit;
    }

    /**
     * Declares what a store already held. Refused a second time for the same store and
     * material — the unique index makes it impossible, and this makes it a sentence.
     */
    @PreAuthorize("hasAuthority('inventory:adjust')")
    public LedgerRow openingStock(OpeningStockRequest request) {
        SiteLookup.StoreInfo store = sites.requireStore(request.storeId());
        periodLockGuard.assertOpen(store.siteId(), request.asOfDate(),
                PeriodLockGuard.Module.INVENTORY);

        if (transactions.existsByStoreIdAndMaterialIdAndTxnType(store.id(), request.materialId(),
                TxnType.OPENING_STOCK)) {
            throw BusinessException.conflict("opening-stock.already-declared",
                    "Opening stock for %s at this store is already on record. Correct it with a "
                            .formatted(materials.require(request.materialId()).name())
                            + "stock adjustment, which leaves a trail.");
        }

        BigDecimal factor = materials.factorToBase(request.materialId(), request.unitId());
        BigDecimal quantityBase = request.quantity().multiply(factor);
        BigDecimal rateBase = request.rate().divide(factor, 4, java.math.RoundingMode.HALF_UP);

        StockTransaction posted = ledger.post(new StockLedgerService.Movement(store.orgId(),
                store.projectId(), store.siteId(), store.id(), request.materialId(),
                TxnType.OPENING_STOCK, request.asOfDate(), quantityBase, rateBase,
                SourceType.OPENING, null, null, null, request.remarks()));

        audit.record("STOCK", store.id(), "OPENING_STOCK", null,
                Map.of("materialId", request.materialId().toString(),
                        "quantityBase", quantityBase, "rateBase", rateBase), request.remarks());
        return reads.toLedgerRow(posted);
    }

    /** Material that went into nothing. The reason is mandatory in the schema and here. */
    @PreAuthorize("hasAuthority('inventory:issue')")
    public LedgerRow wastage(WastageRequest request) {
        SiteLookup.StoreInfo store = sites.requireStore(request.storeId());
        periodLockGuard.assertOpen(store.siteId(), request.wastageDate(),
                PeriodLockGuard.Module.INVENTORY);
        if (request.boqItemId() != null) {
            boqItems.requireChargeable(request.boqItemId());
        }

        BigDecimal factor = materials.factorToBase(request.materialId(), request.unitId());
        BigDecimal quantityBase = request.quantity().multiply(factor);
        TxnType type = request.type() == WastageRequest.Type.DAMAGE ? TxnType.DAMAGE : TxnType.WASTAGE;

        StockTransaction posted = ledger.post(new StockLedgerService.Movement(store.orgId(),
                store.projectId(), store.siteId(), store.id(), request.materialId(), type,
                request.wastageDate(), quantityBase, null, SourceType.WASTAGE, null, null,
                request.boqItemId(), request.reason()));

        audit.record("STOCK", store.id(), type.name(), null,
                Map.of("materialId", request.materialId().toString(),
                        "quantityBase", quantityBase, "value", posted.getValue()),
                request.reason());
        return reads.toLedgerRow(posted);
    }

    /**
     * The correction of last resort. Signed, so it can write stock on as well as off, and
     * administrator-only, because a role that can adjust a balance can hide a loss.
     */
    @PreAuthorize("hasAuthority('inventory:adjust')")
    public LedgerRow adjust(AdjustmentRequest request) {
        if (request.quantityDelta().signum() == 0) {
            throw new BusinessException("adjustment.zero",
                    "An adjustment of nothing is not an adjustment.");
        }
        SiteLookup.StoreInfo store = sites.requireStore(request.storeId());
        periodLockGuard.assertOpen(store.siteId(), request.adjustmentDate(),
                PeriodLockGuard.Module.INVENTORY);

        BigDecimal factor = materials.factorToBase(request.materialId(), request.unitId());
        BigDecimal quantityBase = request.quantityDelta().abs().multiply(factor);
        short direction = request.quantityDelta().signum() > 0
                ? StockTransaction.IN : StockTransaction.OUT;

        // Writing stock on values it at what the store already paid, so an adjustment never
        // becomes a back door for repricing. A store with nothing in it values it at zero,
        // which is honest: nobody knows what it cost, and inventing a number would be worse.
        BigDecimal rate = direction == StockTransaction.IN
                ? ledger.currentRate(store.id(), request.materialId())
                : null;

        StockTransaction posted = ledger.post(new StockLedgerService.Movement(store.orgId(),
                store.projectId(), store.siteId(), store.id(), request.materialId(),
                TxnType.ADJUSTMENT, request.adjustmentDate(), quantityBase, rate,
                SourceType.ADJUSTMENT, null, null, null, request.reason()), direction);

        audit.record("STOCK", store.id(), "ADJUSTMENT", null,
                Map.of("materialId", request.materialId().toString(),
                        "quantityDelta", request.quantityDelta(),
                        "direction", direction), request.reason());
        return reads.toLedgerRow(posted);
    }
}

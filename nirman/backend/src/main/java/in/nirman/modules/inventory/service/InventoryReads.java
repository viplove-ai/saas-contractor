package in.nirman.modules.inventory.service;

import in.nirman.common.PageResponse;
import in.nirman.modules.inventory.api.dto.InventoryDtos.LedgerRow;
import in.nirman.modules.inventory.api.dto.InventoryDtos.StockPosition;
import in.nirman.modules.inventory.api.dto.InventoryDtos.StockRow;
import in.nirman.modules.inventory.domain.StockBalance;
import in.nirman.modules.inventory.domain.StockTransaction;
import in.nirman.modules.inventory.repository.StockBalanceRepository;
import in.nirman.modules.inventory.repository.StockTransactionRepository;
import in.nirman.modules.masterdata.service.MaterialLookup;
import in.nirman.modules.masterdata.service.MaterialLookup.MaterialInfo;
import in.nirman.modules.project.service.SiteLookup;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The two inventory reads everything else is built on: what is in stock now, and how it got
 * there.
 *
 * <p>Both narrow to the caller's sites when they name no store, the same way every other
 * list in the system does: for a site-scoped role, no filter means "mine", never
 * "everyone's" (docs/04, layer 3).</p>
 *
 * <p>The ledger is the answer to the question a balance always provokes. Nobody disputes a
 * stock figure in the abstract; they dispute it standing in the store looking at a
 * different number, and what settles it is the list of movements that produced it.</p>
 */
@Service
@Transactional(readOnly = true)
public class InventoryReads {

    private final StockBalanceRepository balances;
    private final StockTransactionRepository transactions;
    private final MaterialLookup materials;
    private final SiteLookup sites;
    private final SiteAccessGuard siteAccessGuard;
    private final CurrentUserProvider currentUser;

    public InventoryReads(StockBalanceRepository balances, StockTransactionRepository transactions,
                          MaterialLookup materials, SiteLookup sites,
                          SiteAccessGuard siteAccessGuard, CurrentUserProvider currentUser) {
        this.balances = balances;
        this.transactions = transactions;
        this.materials = materials;
        this.sites = sites;
        this.siteAccessGuard = siteAccessGuard;
        this.currentUser = currentUser;
    }

    /**
     * The stock position.
     *
     * @param lowOnly keep only materials below their own reorder level. The threshold is a
     *                property of the material, not one number for the whole store, so this
     *                cannot be a database filter without joining the master — and joining it
     *                to filter thirty rows is not worth the query.
     */
    @PreAuthorize("hasAuthority('inventory:read')")
    public StockPosition stock(UUID storeId, UUID siteId, UUID materialId, boolean lowOnly) {
        if (siteId != null) {
            siteAccessGuard.assertCanAccess(siteId);
        }
        if (storeId != null) {
            sites.requireStore(storeId);
        }

        List<StockBalance> found = siteId != null
                ? balancesAtSite(siteId, materialId)
                : balances.search(orgId(), storeId, materialId, restricted(), scopeStoreIds());

        Map<UUID, MaterialInfo> byMaterial = materials.byIds(found.stream()
                .map(StockBalance::getMaterialId).collect(Collectors.toSet()));
        Map<UUID, SiteLookup.StoreInfo> byStore = storeInfo(found.stream()
                .map(StockBalance::getStoreId).collect(Collectors.toSet()));

        List<StockRow> rows = found.stream()
                .map(balance -> toStockRow(balance, byMaterial.get(balance.getMaterialId()),
                        byStore.get(balance.getStoreId())))
                .filter(row -> !lowOnly || row.low())
                .toList();

        BigDecimal totalValue = rows.stream().map(StockRow::stockValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new StockPosition(rows, totalValue);
    }

    /** Every movement behind a balance, oldest first. */
    @PreAuthorize("hasAuthority('inventory:read')")
    public PageResponse<LedgerRow> ledger(UUID storeId, UUID materialId,
                                          java.time.LocalDate from, java.time.LocalDate to,
                                          Pageable pageable) {
        if (storeId != null) {
            sites.requireStore(storeId);
        }
        boolean restricted = restricted();
        Collection<UUID> visible = restricted ? currentUser.assignedSiteIds() : List.of();
        if (restricted && visible.isEmpty()) {
            return new PageResponse<>(List.of(), 0, pageable.getPageSize(), 0, 0, true, true);
        }
        return PageResponse.from(
                transactions.ledger(orgId(), storeId, materialId, from, to, restricted,
                        visible, pageable),
                this::toLedgerRow);
    }

    /** Shared with the write services, so a posted movement comes back in the read shape. */
    public LedgerRow toLedgerRow(StockTransaction txn) {
        MaterialInfo material = materials.byIds(List.of(txn.getMaterialId()))
                .get(txn.getMaterialId());
        return new LedgerRow(txn.getId(), txn.getTxnDate(), txn.getStoreId(),
                sites.findStore(txn.getStoreId()).map(SiteLookup.StoreInfo::name).orElse(null),
                txn.getMaterialId(),
                material == null ? null : material.code(),
                material == null ? null : material.name(),
                material == null ? null : material.baseUnitCode(),
                txn.getTxnType(), txn.getDirection(), txn.getQuantityBase(), txn.getRateBase(),
                txn.getValue(), txn.getBalanceAfter(), txn.getAvgRateAfter(), txn.getSourceType(),
                txn.getSourceId(), txn.getBoqItemId(), txn.getReason(), txn.getCreatedAt());
    }

    // ------------------------------------------------------------------ internals

    private List<StockBalance> balancesAtSite(UUID siteId, UUID materialId) {
        List<UUID> storeIds = sites.storesAtSites(List.of(siteId)).stream()
                .map(SiteLookup.StoreInfo::id).toList();
        if (storeIds.isEmpty()) {
            return List.of();
        }
        return balances.findByStoreIdIn(storeIds).stream()
                .filter(balance -> materialId == null || balance.getMaterialId().equals(materialId))
                .toList();
    }

    private StockRow toStockRow(StockBalance balance, MaterialInfo material,
                                SiteLookup.StoreInfo store) {
        BigDecimal minStock = material == null ? BigDecimal.ZERO : material.minStockLevel();
        boolean low = minStock.signum() > 0 && balance.getQuantityBase().compareTo(minStock) < 0;
        return new StockRow(balance.getStoreId(),
                store == null ? null : store.code(),
                store == null ? null : store.name(),
                store == null ? null : store.siteId(),
                balance.getMaterialId(),
                material == null ? null : material.code(),
                material == null ? null : material.name(),
                material == null ? null : material.baseUnitCode(),
                balance.getQuantityBase(), balance.getMovingAvgRate(), balance.getStockValue(),
                balance.getInTransitQtyBase(), minStock, low, balance.getLastTxnAt());
    }

    private Map<UUID, SiteLookup.StoreInfo> storeInfo(Collection<UUID> storeIds) {
        return storeIds.stream()
                .map(sites::findStore)
                .flatMap(java.util.Optional::stream)
                .collect(Collectors.toMap(SiteLookup.StoreInfo::id, info -> info));
    }

    /**
     * Store ids the caller may see. Empty for an unrestricted role, in which case the query
     * ignores the list entirely.
     */
    private Collection<UUID> scopeStoreIds() {
        if (!restricted()) {
            return List.of();
        }
        List<UUID> storeIds = sites.storesAtSites(currentUser.assignedSiteIds()).stream()
                .map(SiteLookup.StoreInfo::id).toList();
        // A site-scoped user posted nowhere gets nothing, rather than an IN () that the
        // database would refuse to parse.
        return storeIds.isEmpty() ? List.of(new UUID(0, 0)) : storeIds;
    }

    private boolean restricted() {
        return !currentUser.seesAllSites();
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }
}

package in.nirman.modules.inventory.service;

import in.nirman.modules.inventory.domain.GoodsReceipt;
import in.nirman.modules.inventory.domain.GoodsReceiptItem;
import in.nirman.modules.inventory.domain.StockBalance;
import in.nirman.modules.inventory.domain.StockTransaction;
import in.nirman.modules.inventory.domain.StockTransaction.TxnType;
import in.nirman.modules.inventory.repository.GoodsReceiptItemRepository;
import in.nirman.modules.inventory.repository.GoodsReceiptRepository;
import in.nirman.modules.inventory.repository.StockBalanceRepository;
import in.nirman.modules.inventory.repository.StockTransactionRepository;
import in.nirman.modules.masterdata.service.MaterialLookup;
import in.nirman.modules.masterdata.service.MaterialLookup.MaterialInfo;
import in.nirman.modules.project.service.SiteLookup;
import in.nirman.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link InventoryLookup}, derived from the ledger on every call.
 *
 * <p>The ledger is append-only and authoritative; {@code stock_balances} is its cache. So
 * every figure here except {@link #inventoryValue} is summed from movements, and
 * {@code inventoryValue} reads the cache precisely so that the two can be compared — a
 * dashboard whose opening-plus-received-less-consumed does not land on the cached closing
 * value has found a real bug, and hiding that behind a single derivation would lose the one
 * check that catches it.</p>
 */
@Service
@Transactional(readOnly = true)
public class InventoryLookupService implements InventoryLookup {

    /** Movement out of the project for good. A transfer out is relocation, not consumption. */
    private static final Set<TxnType> CONSUMPTION =
            Set.of(TxnType.ISSUE, TxnType.WASTAGE, TxnType.DAMAGE);
    private static final Set<TxnType> RECEIPT =
            Set.of(TxnType.RECEIPT, TxnType.OPENING_STOCK, TxnType.RETURN);

    private final StockTransactionRepository transactions;
    private final StockBalanceRepository balances;
    private final GoodsReceiptRepository goodsReceipts;
    private final GoodsReceiptItemRepository goodsReceiptLines;
    private final MaterialLookup materials;
    private final SiteLookup sites;
    private final CurrentUserProvider currentUser;
    private final in.nirman.modules.inventory.repository.MaterialConsumptionNormRepository norms;
    private final in.nirman.modules.masterdata.service.UnitLookup units;

    public InventoryLookupService(StockTransactionRepository transactions,
                                 StockBalanceRepository balances,
                                 GoodsReceiptRepository goodsReceipts,
                                 GoodsReceiptItemRepository goodsReceiptLines,
                                 MaterialLookup materials,
                                 in.nirman.modules.inventory.repository.MaterialConsumptionNormRepository norms,
                                 in.nirman.modules.masterdata.service.UnitLookup units,
                                 SiteLookup sites, CurrentUserProvider currentUser) {
        this.norms = norms;
        this.units = units;
        this.transactions = transactions;
        this.balances = balances;
        this.goodsReceipts = goodsReceipts;
        this.goodsReceiptLines = goodsReceiptLines;
        this.materials = materials;
        this.sites = sites;
        this.currentUser = currentUser;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Resolved in bulk. A full catalogue is a few hundred norms over a few dozen materials,
     * and naming them one at a time would be several hundred queries to build one plan.</p>
     */
    @Override
    public List<ConsumptionNormInfo> consumptionNorms() {
        var rows = norms.findByOrgIdAndActiveTrueOrderByWorkCategoryAscWorkSubTypeAsc(orgId());
        Map<UUID, MaterialInfo> byMaterial = materials.byIds(rows.stream()
                .map(in.nirman.modules.inventory.domain.MaterialConsumptionNorm::getMaterialId)
                .toList());
        Map<UUID, String> unitCodes = units.codesByIds(rows.stream()
                .map(in.nirman.modules.inventory.domain.MaterialConsumptionNorm::getWorkUnitId)
                .toList());
        List<ConsumptionNormInfo> infos = new ArrayList<>(rows.size());
        for (var row : rows) {
            MaterialInfo material = byMaterial.get(row.getMaterialId());
            if (material == null) {
                continue;
            }
            infos.add(new ConsumptionNormInfo(row.getWorkCategory(), row.getWorkSubType(),
                    material.code(), material.name(), unitCodes.get(row.getWorkUnitId()),
                    row.getQtyPerWorkUnit(), material.standardRate()));
        }
        return infos;
    }

    @Override
    public MaterialDay day(UUID siteId, LocalDate date) {
        List<StockTransaction> movements =
                transactions.findForSitePeriod(orgId(), siteId, date, date);

        Map<UUID, Accumulator> byMaterial = new LinkedHashMap<>();
        List<UUID> boqItemIds = new ArrayList<>();
        BigDecimal receivedValue = BigDecimal.ZERO;
        BigDecimal consumedValue = BigDecimal.ZERO;
        int receipts = 0;
        int issues = 0;

        for (StockTransaction movement : movements) {
            boolean received = RECEIPT.contains(movement.getTxnType());
            boolean consumed = CONSUMPTION.contains(movement.getTxnType());
            if (!received && !consumed) {
                continue;   // a transfer leg is neither, and a DPR must not report it as either
            }
            Accumulator sum = byMaterial.computeIfAbsent(movement.getMaterialId(),
                    unused -> new Accumulator());
            if (received) {
                receipts++;
                receivedValue = receivedValue.add(movement.getValue());
                sum.receive(movement);
            } else {
                issues++;
                consumedValue = consumedValue.add(movement.getValue());
                sum.consume(movement);
                if (movement.getBoqItemId() != null && !boqItemIds.contains(movement.getBoqItemId())) {
                    boqItemIds.add(movement.getBoqItemId());
                }
            }
        }

        Map<UUID, MaterialInfo> named = materials.byIds(byMaterial.keySet());
        List<MaterialMovement> rows = byMaterial.entrySet().stream()
                .map(entry -> {
                    MaterialInfo material = named.get(entry.getKey());
                    Accumulator sum = entry.getValue();
                    return new MaterialMovement(entry.getKey(),
                            material == null ? null : material.code(),
                            material == null ? null : material.name(),
                            material == null ? null : material.baseUnitCode(),
                            sum.receivedQty, sum.receivedValue, sum.consumedQty, sum.consumedValue);
                })
                .sorted(Comparator.comparing(row -> row.materialCode() == null ? "" : row.materialCode()))
                .toList();

        return new MaterialDay(date, receivedValue, consumedValue, receipts, issues, rows,
                List.copyOf(boqItemIds));
    }

    @Override
    public List<DailyConsumption> dailyConsumption(UUID siteId, LocalDate from, LocalDate to) {
        Map<LocalDate, BigDecimal> byDay = new LinkedHashMap<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            byDay.put(day, BigDecimal.ZERO);
        }
        for (StockTransaction movement : transactions.findForSitePeriod(orgId(), siteId, from, to)) {
            if (CONSUMPTION.contains(movement.getTxnType())) {
                byDay.merge(movement.getTxnDate(), movement.getValue(), BigDecimal::add);
            }
        }
        return byDay.entrySet().stream()
                .map(entry -> new DailyConsumption(entry.getKey(), entry.getValue()))
                .toList();
    }

    @Override
    public StockMovement movement(UUID siteId, LocalDate from, LocalDate to) {
        BigDecimal opening = transactions.signedValueBefore(orgId(), siteId, from);

        BigDecimal received = BigDecimal.ZERO;
        BigDecimal issued = BigDecimal.ZERO;
        BigDecimal wasted = BigDecimal.ZERO;
        BigDecimal transferredOut = BigDecimal.ZERO;
        BigDecimal transferredIn = BigDecimal.ZERO;

        for (StockTransaction movement : transactions.findForSitePeriod(orgId(), siteId, from, to)) {
            BigDecimal value = movement.getValue();
            switch (movement.getTxnType()) {
                case RECEIPT, OPENING_STOCK, RETURN -> received = received.add(value);
                case ISSUE -> issued = issued.add(value);
                case WASTAGE, DAMAGE -> wasted = wasted.add(value);
                case TRANSFER_OUT -> transferredOut = transferredOut.add(value);
                case TRANSFER_IN -> transferredIn = transferredIn.add(value);
                // An adjustment moves either way and carries its own direction, so it is
                // folded into the side it actually went.
                case ADJUSTMENT -> {
                    if (movement.getDirection() == StockTransaction.IN) {
                        received = received.add(value);
                    } else {
                        issued = issued.add(value);
                    }
                }
            }
        }

        BigDecimal consumed = issued.add(wasted);
        BigDecimal closing = opening.add(received).add(transferredIn)
                .subtract(consumed).subtract(transferredOut);
        BigDecimal cached = inventoryValue(siteId);
        // The residual is against the cache, not against the arithmetic — the arithmetic
        // cannot disagree with itself. A non-zero residual means the ledger and its cache
        // have drifted, which is worth seeing rather than papering over.
        BigDecimal residual = closing.subtract(cached);

        return new StockMovement(from, to, opening, received, consumed, issued, wasted,
                transferredOut, transferredIn, cached, residual,
                residual.abs().compareTo(new BigDecimal("0.05")) <= 0);
    }

    @Override
    public BigDecimal inventoryValue(UUID siteId) {
        return balancesFor(siteId).stream()
                .map(StockBalance::getStockValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public int lowStockCount(UUID siteId) {
        List<StockBalance> found = balancesFor(siteId);
        Map<UUID, MaterialInfo> named = materials.byIds(found.stream()
                .map(StockBalance::getMaterialId).collect(Collectors.toSet()));
        return (int) found.stream()
                .filter(balance -> {
                    MaterialInfo material = named.get(balance.getMaterialId());
                    return material != null && material.minStockLevel().signum() > 0
                            && balance.getQuantityBase().compareTo(material.minStockLevel()) < 0;
                })
                .count();
    }

    @Override
    public int consumptionWithoutBoqItem(UUID siteId, LocalDate from, LocalDate to) {
        return (int) transactions.countConsumptionWithoutBoqItem(orgId(), siteId, from, to);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Read off the receipts rather than off a per-vendor table, because the receipts are
     * where it is written down and a second copy is a second version of the truth. Two
     * queries and no third: the receipts, then every line of them at once.</p>
     */
    @Override
    public List<VendorPurchase> vendorPurchases(UUID vendorId, LocalDate from, LocalDate to) {
        List<GoodsReceipt> found = goodsReceipts.findForVendor(orgId(), vendorId, from, to);
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<GoodsReceiptItem>> byReceipt = goodsReceiptLines
                .findByGrnIdIn(found.stream().map(GoodsReceipt::getId).toList()).stream()
                .collect(Collectors.groupingBy(GoodsReceiptItem::getGrnId));
        Set<UUID> materialIds = byReceipt.values().stream().flatMap(List::stream)
                .map(GoodsReceiptItem::getMaterialId).collect(Collectors.toSet());
        Map<UUID, MaterialLookup.MaterialInfo> materialsById = materials.byIds(materialIds);
        Map<UUID, String> unitCodes = unitCodesFor(byReceipt);

        List<VendorPurchase> rows = new ArrayList<>();
        for (GoodsReceipt receipt : found) {
            for (GoodsReceiptItem line : byReceipt.getOrDefault(receipt.getId(), List.of())) {
                MaterialLookup.MaterialInfo material = materialsById.get(line.getMaterialId());
                rows.add(new VendorPurchase(receipt.getId(), receipt.getGrnNumber(),
                        receipt.getReceiptDate(), receipt.getInvoiceNumber(), receipt.getSiteId(),
                        line.getMaterialId(),
                        material == null ? null : material.code(),
                        material == null ? null : material.name(),
                        unitCodes.get(line.getUnitId()),
                        line.getQuantity(), line.getRate(), line.getAmount(),
                        receipt.getWorkflowStatus().hasPosted()));
            }
        }
        return rows;
    }

    private Map<UUID, String> unitCodesFor(Map<UUID, List<GoodsReceiptItem>> byReceipt) {
        Set<UUID> unitIds = byReceipt.values().stream().flatMap(List::stream)
                .map(GoodsReceiptItem::getUnitId).collect(Collectors.toSet());
        return unitIds.isEmpty() ? Map.of() : materials.unitCodes(unitIds);
    }

    // ------------------------------------------------------------------ internals

    private List<StockBalance> balancesFor(UUID siteId) {
        if (siteId == null) {
            return balances.search(orgId(), null, null, false, List.of());
        }
        List<UUID> storeIds = sites.storesAtSites(List.of(siteId)).stream()
                .map(SiteLookup.StoreInfo::id).toList();
        return storeIds.isEmpty() ? List.of() : balances.findByStoreIdIn(storeIds);
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }

    private static final class Accumulator {
        private BigDecimal receivedQty = BigDecimal.ZERO;
        private BigDecimal receivedValue = BigDecimal.ZERO;
        private BigDecimal consumedQty = BigDecimal.ZERO;
        private BigDecimal consumedValue = BigDecimal.ZERO;

        private void receive(StockTransaction movement) {
            receivedQty = receivedQty.add(movement.getQuantityBase());
            receivedValue = receivedValue.add(movement.getValue());
        }

        private void consume(StockTransaction movement) {
            consumedQty = consumedQty.add(movement.getQuantityBase());
            consumedValue = consumedValue.add(movement.getValue());
        }
    }
}

package in.nirman.modules.inventory.service;

import in.nirman.modules.inventory.api.dto.InventoryDtos.StockPosition;
import in.nirman.modules.inventory.api.dto.InventoryDtos.StockRow;
import in.nirman.modules.inventory.api.dto.InventoryReportDtos.ConsumptionReport;
import in.nirman.modules.inventory.api.dto.InventoryReportDtos.ConsumptionRow;
import in.nirman.modules.inventory.api.dto.InventoryReportDtos.LowStockReport;
import in.nirman.modules.inventory.api.dto.InventoryReportDtos.LowStockRow;
import in.nirman.modules.inventory.api.dto.InventoryReportDtos.StockPositionReport;
import in.nirman.modules.inventory.api.dto.InventoryReportDtos.TransferRegisterReport;
import in.nirman.modules.inventory.api.dto.InventoryReportDtos.TransferRegisterRow;
import in.nirman.modules.inventory.api.dto.InventoryReportDtos.WastageReport;
import in.nirman.modules.inventory.api.dto.InventoryReportDtos.WastageRow;
import in.nirman.modules.inventory.domain.StockTransaction;
import in.nirman.modules.inventory.domain.StockTransaction.TxnType;
import in.nirman.modules.inventory.domain.StockTransfer;
import in.nirman.modules.inventory.domain.StockTransferItem;
import in.nirman.modules.inventory.repository.StockTransactionRepository;
import in.nirman.modules.inventory.repository.StockTransferItemRepository;
import in.nirman.modules.inventory.repository.StockTransferRepository;
import in.nirman.modules.masterdata.service.MaterialLookup;
import in.nirman.modules.masterdata.service.MaterialLookup.MaterialInfo;
import in.nirman.modules.project.service.SiteLookup;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The five inventory reports docs/05 names.
 *
 * <p>Every one of them reads the ledger or the balance cache and nothing else. That is what
 * lets a figure on a printed sheet be argued back to the movement that produced it, which
 * is the only reason anybody trusts a stock report at all.</p>
 *
 * <p>The permission checks live here rather than on the controller, the same way the labour
 * reports do: the reporting module renders, it does not decide who may see a valuation.</p>
 */
@Service
@Transactional(readOnly = true)
public class InventoryReportService {

    private static final Set<TxnType> WASTE_TYPES = Set.of(TxnType.WASTAGE, TxnType.DAMAGE);

    private final StockTransactionRepository transactions;
    private final StockTransferRepository transfers;
    private final StockTransferItemRepository transferLines;
    private final InventoryReads reads;
    private final MaterialLookup materials;
    private final SiteLookup sites;
    private final SiteAccessGuard siteAccessGuard;
    private final CurrentUserProvider currentUser;

    public InventoryReportService(StockTransactionRepository transactions,
                                  StockTransferRepository transfers,
                                  StockTransferItemRepository transferLines,
                                  InventoryReads reads, MaterialLookup materials,
                                  SiteLookup sites, SiteAccessGuard siteAccessGuard,
                                  CurrentUserProvider currentUser) {
        this.transactions = transactions;
        this.transfers = transfers;
        this.transferLines = transferLines;
        this.reads = reads;
        this.materials = materials;
        this.sites = sites;
        this.siteAccessGuard = siteAccessGuard;
        this.currentUser = currentUser;
    }

    @PreAuthorize("hasAuthority('report:operational')")
    public StockPositionReport stockPosition(UUID siteId, UUID storeId) {
        StockPosition position = reads.stock(storeId, siteId, null, false);
        return new StockPositionReport(scopeName(siteId, storeId), LocalDate.now(),
                position.rows(), position.totalValue());
    }

    @PreAuthorize("hasAuthority('report:operational')")
    public LowStockReport lowStock(UUID siteId, UUID storeId) {
        StockPosition position = reads.stock(storeId, siteId, null, true);
        List<LowStockRow> rows = position.rows().stream()
                .map(row -> new LowStockRow(row.storeId(), row.storeName(), row.materialId(),
                        row.materialCode(), row.materialName(), row.baseUnitCode(),
                        row.quantityBase(), row.minStockLevel(),
                        row.minStockLevel().subtract(row.quantityBase()),
                        row.inTransitQtyBase()))
                .sorted(Comparator.comparing((LowStockRow row) -> row.shortfall()).reversed())
                .toList();
        return new LowStockReport(scopeName(siteId, storeId), LocalDate.now(), rows);
    }

    /**
     * What a site consumed over a period, per material.
     *
     * <p>Read from the ledger rather than from issue documents, so material written off as
     * wastage and material adjusted away after a count are both in it. An issue register
     * would flatter the site by counting only the material somebody filled in a slip for.</p>
     */
    @PreAuthorize("hasAuthority('report:operational')")
    public ConsumptionReport consumption(UUID siteId, LocalDate from, LocalDate to) {
        siteAccessGuard.assertCanAccess(siteId);
        List<StockTransaction> movements = transactions.findByTypes(orgId(),
                Set.of(TxnType.ISSUE, TxnType.WASTAGE, TxnType.DAMAGE), siteId, from, to);

        Map<UUID, BigDecimal[]> totals = new HashMap<>();
        for (StockTransaction movement : movements) {
            BigDecimal[] cell = totals.computeIfAbsent(movement.getMaterialId(),
                    key -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO,
                            BigDecimal.ZERO, BigDecimal.ZERO});
            boolean waste = WASTE_TYPES.contains(movement.getTxnType());
            cell[waste ? 2 : 0] = cell[waste ? 2 : 0].add(movement.getQuantityBase());
            cell[waste ? 3 : 1] = cell[waste ? 3 : 1].add(movement.getValue());
        }

        Map<UUID, MaterialInfo> byMaterial = materials.byIds(totals.keySet());
        Map<UUID, BigDecimal> closing = closingQuantities(siteId, totals.keySet());

        List<ConsumptionRow> rows = totals.entrySet().stream()
                .map(entry -> {
                    MaterialInfo material = byMaterial.get(entry.getKey());
                    BigDecimal[] cell = entry.getValue();
                    return new ConsumptionRow(entry.getKey(),
                            material == null ? null : material.code(),
                            material == null ? null : material.name(),
                            material == null ? null : material.baseUnitCode(),
                            cell[0], cell[1], cell[2], cell[3],
                            cell[0].add(cell[2]), cell[1].add(cell[3]),
                            closing.getOrDefault(entry.getKey(), BigDecimal.ZERO));
                })
                .sorted(Comparator.comparing(row ->
                        row.materialCode() == null ? "" : row.materialCode()))
                .toList();

        BigDecimal totalValue = rows.stream().map(ConsumptionRow::totalConsumedValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ConsumptionReport(siteName(siteId), from, to, rows, totalValue);
    }

    /**
     * Transfers over a period.
     *
     * <p>The column worth having is the status: everything still in transit is material
     * counted at neither end, and a transfer that has sat there for three weeks is a lorry
     * nobody followed up.</p>
     */
    @PreAuthorize("hasAuthority('report:operational')")
    public TransferRegisterReport transferRegister(LocalDate from, LocalDate to) {
        List<StockTransfer> found = transfers.findForPeriod(orgId(), from, to).stream()
                .filter(this::visible)
                .toList();
        Map<UUID, List<StockTransferItem>> linesByTransfer = transferLines
                .findByTransferIdIn(found.stream().map(StockTransfer::getId).toList()).stream()
                .collect(Collectors.groupingBy(StockTransferItem::getTransferId));

        List<TransferRegisterRow> rows = found.stream()
                .map(transfer -> {
                    List<StockTransferItem> lines =
                            linesByTransfer.getOrDefault(transfer.getId(), List.of());
                    return new TransferRegisterRow(transfer.getId(), transfer.getTransferNumber(),
                            transfer.getTransferDate(),
                            storeName(transfer.getFromStoreId()), storeName(transfer.getToStoreId()),
                            transfer.getStatus(), lines.size(),
                            sum(lines, StockTransferItem::getQuantityBase),
                            sum(lines, StockTransferItem::getReceivedQtyBase),
                            sum(lines, StockTransferItem::getShortageQtyBase),
                            lines.stream()
                                    .filter(line -> line.getRateBase() != null)
                                    .map(line -> line.getQuantityBase().multiply(line.getRateBase()))
                                    .reduce(BigDecimal.ZERO, BigDecimal::add));
                })
                .toList();

        BigDecimal totalShortage = rows.stream().map(TransferRegisterRow::shortageQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TransferRegisterReport(from, to, rows, totalShortage);
    }

    /** Wastage and damage, each with the reason it carried. */
    @PreAuthorize("hasAuthority('report:operational')")
    public WastageReport wastage(UUID siteId, LocalDate from, LocalDate to) {
        siteAccessGuard.assertCanAccess(siteId);
        List<StockTransaction> movements =
                transactions.findByTypes(orgId(), WASTE_TYPES, siteId, from, to);
        Map<UUID, MaterialInfo> byMaterial = materials.byIds(movements.stream()
                .map(StockTransaction::getMaterialId).collect(Collectors.toSet()));

        List<WastageRow> rows = movements.stream()
                .map(movement -> {
                    MaterialInfo material = byMaterial.get(movement.getMaterialId());
                    return new WastageRow(movement.getTxnDate(), movement.getStoreId(),
                            storeName(movement.getStoreId()), movement.getMaterialId(),
                            material == null ? null : material.code(),
                            material == null ? null : material.name(),
                            material == null ? null : material.baseUnitCode(),
                            movement.getTxnType(), movement.getQuantityBase(), movement.getValue(),
                            movement.getBoqItemId(), movement.getReason());
                })
                .toList();

        BigDecimal totalValue = rows.stream().map(WastageRow::value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new WastageReport(siteName(siteId), from, to, rows, totalValue);
    }

    // ------------------------------------------------------------------ internals

    private Map<UUID, BigDecimal> closingQuantities(UUID siteId, Set<UUID> materialIds) {
        if (materialIds.isEmpty()) {
            return Map.of();
        }
        return reads.stock(null, siteId, null, false).rows().stream()
                .filter(row -> materialIds.contains(row.materialId()))
                .collect(Collectors.toMap(StockRow::materialId, StockRow::quantityBase,
                        BigDecimal::add));
    }

    /** A transfer is in the caller's register when they run either end of it. */
    private boolean visible(StockTransfer transfer) {
        if (currentUser.seesAllSites()) {
            return true;
        }
        return atMySite(transfer.getFromStoreId()) || atMySite(transfer.getToStoreId());
    }

    private boolean atMySite(UUID storeId) {
        return sites.findStore(storeId)
                .map(store -> siteAccessGuard.canAccess(store.siteId()))
                .orElse(false);
    }

    private static BigDecimal sum(List<StockTransferItem> lines,
                                  java.util.function.Function<StockTransferItem, BigDecimal> field) {
        return lines.stream().map(field).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String storeName(UUID storeId) {
        return sites.findStore(storeId).map(SiteLookup.StoreInfo::name).orElse(null);
    }

    private String siteName(UUID siteId) {
        return sites.require(siteId).name();
    }

    private String scopeName(UUID siteId, UUID storeId) {
        if (storeId != null) {
            return storeName(storeId);
        }
        if (siteId != null) {
            return siteName(siteId);
        }
        return "All sites";
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }
}

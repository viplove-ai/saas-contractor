package in.nirman.modules.inventory.service;

import in.nirman.modules.inventory.api.dto.InventoryDtos.CountLineResponse;
import in.nirman.modules.inventory.api.dto.InventoryDtos.CountResponse;
import in.nirman.modules.inventory.api.dto.InventoryDtos.IssueLineResponse;
import in.nirman.modules.inventory.api.dto.InventoryDtos.IssueResponse;
import in.nirman.modules.inventory.api.dto.InventoryDtos.MovementPhoto;
import in.nirman.modules.inventory.api.dto.InventoryDtos.ReceiptLineResponse;
import in.nirman.modules.inventory.api.dto.InventoryDtos.ReceiptResponse;
import in.nirman.modules.inventory.api.dto.InventoryDtos.TransferLineResponse;
import in.nirman.modules.inventory.api.dto.InventoryDtos.TransferResponse;
import in.nirman.modules.inventory.domain.GoodsReceipt;
import in.nirman.modules.inventory.domain.GoodsReceiptItem;
import in.nirman.modules.inventory.domain.MaterialIssue;
import in.nirman.modules.inventory.domain.MaterialIssueItem;
import in.nirman.modules.inventory.domain.PhysicalStockCount;
import in.nirman.modules.inventory.domain.PhysicalStockCountItem;
import in.nirman.modules.inventory.domain.StockTransfer;
import in.nirman.modules.inventory.domain.StockTransferItem;
import in.nirman.modules.inventory.repository.GoodsReceiptAttachmentRepository;
import in.nirman.modules.inventory.repository.GoodsReceiptItemRepository;
import in.nirman.modules.inventory.repository.MaterialIssueAttachmentRepository;
import in.nirman.modules.inventory.repository.MaterialIssueItemRepository;
import in.nirman.modules.inventory.repository.PhysicalStockCountItemRepository;
import in.nirman.modules.inventory.repository.StockTransferItemRepository;
import in.nirman.modules.masterdata.service.MaterialLookup;
import in.nirman.modules.masterdata.service.MaterialLookup.MaterialInfo;
import in.nirman.modules.project.service.SiteLookup;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Turns inventory documents into their API shapes.
 *
 * <p>Held apart from the services because every one of them needs the same three lookups —
 * material names, unit codes, store names — and because doing it per line is what produces
 * a forty-line document with a hundred and twenty queries behind it. Each method here reads
 * its lines once and resolves the names in one batch.</p>
 */
@Component
@Transactional(readOnly = true)
public class InventoryResponses {

    private final GoodsReceiptItemRepository receiptLines;
    private final MaterialIssueItemRepository issueLines;
    private final StockTransferItemRepository transferLines;
    private final PhysicalStockCountItemRepository countLines;
    private final GoodsReceiptAttachmentRepository receiptPhotos;
    private final MaterialIssueAttachmentRepository issuePhotos;
    private final MaterialLookup materials;
    private final SiteLookup sites;

    public InventoryResponses(GoodsReceiptItemRepository receiptLines,
                              MaterialIssueItemRepository issueLines,
                              StockTransferItemRepository transferLines,
                              PhysicalStockCountItemRepository countLines,
                              GoodsReceiptAttachmentRepository receiptPhotos,
                              MaterialIssueAttachmentRepository issuePhotos,
                              MaterialLookup materials, SiteLookup sites) {
        this.receiptLines = receiptLines;
        this.issueLines = issueLines;
        this.transferLines = transferLines;
        this.countLines = countLines;
        this.receiptPhotos = receiptPhotos;
        this.issuePhotos = issuePhotos;
        this.materials = materials;
        this.sites = sites;
    }

    public ReceiptResponse toReceiptResponse(GoodsReceipt receipt) {
        List<GoodsReceiptItem> items = receiptLines.findByGrnId(receipt.getId());
        Map<UUID, MaterialInfo> byMaterial = materials.byIds(
                items.stream().map(GoodsReceiptItem::getMaterialId).collect(Collectors.toSet()));
        Map<UUID, String> unitCodes = materials.unitCodes(
                items.stream().map(GoodsReceiptItem::getUnitId).collect(Collectors.toSet()));

        List<ReceiptLineResponse> lines = items.stream().map(item -> {
            MaterialInfo material = byMaterial.get(item.getMaterialId());
            return new ReceiptLineResponse(item.getId(), item.getMaterialId(),
                    code(material), name(material), item.getUnitId(),
                    unitCodes.get(item.getUnitId()), item.getQuantity(), item.getQuantityBase(),
                    baseUnit(material), item.getRate(), item.getRateBase(), item.getGstPercent(),
                    item.getGstAmount(), item.getAmount(), item.getRemarks());
        }).toList();

        return new ReceiptResponse(receipt.getId(), receipt.getGrnNumber(), receipt.getSiteId(),
                receipt.getStoreId(), storeName(receipt.getStoreId()), receipt.getVendorId(),
                receipt.getReceiptDate(), receipt.getInvoiceNumber(), receipt.getInvoiceDate(),
                receipt.getChallanNumber(), receipt.getVehicleNumber(), receipt.getSubTotal(),
                receipt.getGstAmount(), receipt.getTotalAmount(), receipt.getWorkflowStatus(),
                receipt.getVerifiedAt(), receipt.getRejectionReason(), receipt.getRemarks(),
                receipt.getVersion(), lines,
                receiptPhotos.findByGoodsReceiptId(receipt.getId()).stream()
                        .map(photo -> new MovementPhoto(photo.getAttachmentId(),
                                photo.getDocType()))
                        .toList());
    }

    public IssueResponse toIssueResponse(MaterialIssue issue) {
        List<MaterialIssueItem> items = issueLines.findByIssueId(issue.getId());
        Map<UUID, MaterialInfo> byMaterial = materials.byIds(
                items.stream().map(MaterialIssueItem::getMaterialId).collect(Collectors.toSet()));
        Map<UUID, String> unitCodes = materials.unitCodes(
                items.stream().map(MaterialIssueItem::getUnitId).collect(Collectors.toSet()));

        List<IssueLineResponse> lines = items.stream().map(item -> {
            MaterialInfo material = byMaterial.get(item.getMaterialId());
            return new IssueLineResponse(item.getId(), item.getMaterialId(), code(material),
                    name(material), item.getUnitId(), unitCodes.get(item.getUnitId()),
                    item.getQuantity(), item.getQuantityBase(), baseUnit(material),
                    item.getIssuedRate(), item.getValue(), item.getBoqItemId(), item.getRemarks());
        }).toList();

        BigDecimal totalValue = items.stream()
                .map(MaterialIssueItem::getValue)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new IssueResponse(issue.getId(), issue.getIssueNumber(), issue.getSiteId(),
                issue.getStoreId(), storeName(issue.getStoreId()), issue.getIssueDate(),
                issue.getIssuedToName(), issue.getIssuedToSupplierId(), issue.getBoqItemId(),
                issue.getWorkLocation(), issue.getPurpose(), issue.getWorkflowStatus(),
                issue.getApprovedAt(), issue.getRejectionReason(), totalValue,
                issue.getVersion(), lines,
                issuePhotos.findByMaterialIssueId(issue.getId()).stream()
                        .map(photo -> new MovementPhoto(photo.getAttachmentId(),
                                photo.getDocType()))
                        .toList());
    }

    public TransferResponse toTransferResponse(StockTransfer transfer) {
        List<StockTransferItem> items = transferLines.findByTransferId(transfer.getId());
        Map<UUID, MaterialInfo> byMaterial = materials.byIds(
                items.stream().map(StockTransferItem::getMaterialId).collect(Collectors.toSet()));
        Map<UUID, String> unitCodes = materials.unitCodes(
                items.stream().map(StockTransferItem::getUnitId).collect(Collectors.toSet()));

        List<TransferLineResponse> lines = items.stream().map(item -> {
            MaterialInfo material = byMaterial.get(item.getMaterialId());
            return new TransferLineResponse(item.getId(), item.getMaterialId(), code(material),
                    name(material), item.getUnitId(), unitCodes.get(item.getUnitId()),
                    item.getQuantity(), item.getQuantityBase(), baseUnit(material),
                    item.getReceivedQtyBase(), item.getShortageQtyBase(), item.getRateBase(),
                    item.getRemarks());
        }).toList();

        return new TransferResponse(transfer.getId(), transfer.getTransferNumber(),
                transfer.getFromStoreId(), storeName(transfer.getFromStoreId()),
                transfer.getToStoreId(), storeName(transfer.getToStoreId()),
                transfer.getTransferDate(), transfer.getVehicleNumber(), transfer.getStatus(),
                transfer.getDispatchedAt(), transfer.getReceivedAt(), transfer.getRemarks(),
                transfer.getVersion(), lines);
    }

    public CountResponse toCountResponse(PhysicalStockCount count) {
        List<PhysicalStockCountItem> items = countLines.findByCountId(count.getId());
        Map<UUID, MaterialInfo> byMaterial = materials.byIds(items.stream()
                .map(PhysicalStockCountItem::getMaterialId).collect(Collectors.toSet()));

        List<CountLineResponse> lines = items.stream().map(item -> {
            MaterialInfo material = byMaterial.get(item.getMaterialId());
            return new CountLineResponse(item.getId(), item.getMaterialId(), code(material),
                    name(material), baseUnit(material), item.getSystemQtyBase(),
                    item.getCountedQtyBase(), item.getVarianceQtyBase(), item.getVarianceReason());
        }).toList();

        return new CountResponse(count.getId(), count.getCountNumber(), count.getStoreId(),
                storeName(count.getStoreId()), count.getCountDate(), count.getStatus(),
                count.getApprovedAt(), count.getRemarks(), count.getVersion(), lines);
    }

    /** Store names for a set of stores, resolved once. */
    public Map<UUID, String> storeNames(Collection<UUID> storeIds) {
        return Set.copyOf(storeIds).stream()
                .map(sites::findStore)
                .flatMap(java.util.Optional::stream)
                .collect(Collectors.toMap(SiteLookup.StoreInfo::id, SiteLookup.StoreInfo::name));
    }

    /**
     * Unguarded on purpose. A store name is a label on a document the caller has already
     * been allowed to open, and the far end of an incoming transfer is by definition a
     * store they do not run.
     */
    private String storeName(UUID storeId) {
        return sites.findStore(storeId).map(SiteLookup.StoreInfo::name).orElse(null);
    }

    private static String code(MaterialInfo material) {
        return material == null ? null : material.code();
    }

    private static String name(MaterialInfo material) {
        return material == null ? null : material.name();
    }

    private static String baseUnit(MaterialInfo material) {
        return material == null ? null : material.baseUnitCode();
    }
}

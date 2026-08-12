package in.nirman.modules.inventory.service;

import in.nirman.common.BusinessException;
import in.nirman.common.DocumentNumberService;
import in.nirman.common.PageResponse;
import in.nirman.common.PeriodLockGuard;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.inventory.api.dto.InventoryDtos.CreateReceiptRequest;
import in.nirman.modules.inventory.api.dto.InventoryDtos.LinePrice;
import in.nirman.modules.inventory.api.dto.InventoryDtos.PriceReceiptRequest;
import in.nirman.modules.inventory.api.dto.InventoryDtos.ReceiptLine;
import in.nirman.modules.inventory.api.dto.InventoryDtos.ReceiptResponse;
import in.nirman.modules.inventory.api.dto.InventoryDtos.VerifyReceiptRequest;
import in.nirman.modules.inventory.domain.DocumentWorkflow;
import in.nirman.modules.inventory.domain.GoodsReceipt;
import in.nirman.modules.inventory.domain.GoodsReceiptItem;
import in.nirman.modules.inventory.domain.StockTransaction.SourceType;
import in.nirman.modules.inventory.domain.StockTransaction.TxnType;
import in.nirman.modules.inventory.repository.GoodsReceiptItemRepository;
import in.nirman.modules.inventory.repository.GoodsReceiptRepository;
import in.nirman.modules.masterdata.service.MaterialLookup;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Deliveries arriving at a store.
 *
 * <p>Two rules carry the weight here.</p>
 *
 * <p><b>Stock moves at verification, not at entry.</b> The storekeeper books what came off
 * the lorry; the engineer checks it against the challan. Until that second step the store's
 * issuable balance is unchanged, because a delivery note typed at the gate is a claim, not
 * a fact — and issuing material on the strength of an unchecked claim is how a store ends
 * up short.</p>
 *
 * <p><b>The same vendor invoice cannot be booked twice.</b> Checked here with a clear 409
 * before the unique index ever fires, and checked the way the field actually writes bill
 * numbers: the placeholders that recur — "-", "NIL", "Local", a town name — are excluded,
 * because treating those as invoice numbers would reject the second delivery of the day
 * from the same supplier (docs/09).</p>
 */
@Service
@Transactional
public class GoodsReceiptService {

    /**
     * Bill-number placeholders seen repeatedly in the Kausani register. Deliberately the
     * same list as {@code uq_grn_vendor_invoice} in V1: two copies of a rule are one too
     * many, but the alternative is letting the constraint produce a 500 where the service
     * could have produced a sentence.
     */
    private static final Set<String> PLACEHOLDER_INVOICES =
            Set.of("-", "--", "NIL", "NA", "N/A", "LOCAL", "CASH", "");

    private final GoodsReceiptRepository receipts;
    private final GoodsReceiptItemRepository lines;
    private final StockLedgerService ledger;
    private final MaterialLookup materials;
    private final SiteLookup sites;
    private final SiteAccessGuard siteAccessGuard;
    private final PeriodLockGuard periodLockGuard;
    private final DocumentNumberService documentNumbers;
    private final InventoryResponses responses;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public GoodsReceiptService(GoodsReceiptRepository receipts, GoodsReceiptItemRepository lines,
                               StockLedgerService ledger, MaterialLookup materials,
                               SiteLookup sites, SiteAccessGuard siteAccessGuard,
                               PeriodLockGuard periodLockGuard,
                               DocumentNumberService documentNumbers,
                               InventoryResponses responses, CurrentUserProvider currentUser,
                               AuditService audit) {
        this.receipts = receipts;
        this.lines = lines;
        this.ledger = ledger;
        this.materials = materials;
        this.sites = sites;
        this.siteAccessGuard = siteAccessGuard;
        this.periodLockGuard = periodLockGuard;
        this.documentNumbers = documentNumbers;
        this.responses = responses;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('inventory:read')")
    public PageResponse<ReceiptResponse> list(UUID siteId, UUID storeId, UUID vendorId,
                                              DocumentWorkflow status, LocalDate from,
                                              LocalDate to, Pageable pageable) {
        if (siteId != null) {
            siteAccessGuard.assertCanAccess(siteId);
        }
        boolean restricted = !currentUser.seesAllSites();
        Collection<UUID> visible = restricted ? currentUser.assignedSiteIds() : List.of();
        if (restricted && visible.isEmpty()) {
            return new PageResponse<>(List.of(), 0, pageable.getPageSize(), 0, 0, true, true);
        }
        return PageResponse.from(
                receipts.search(orgId(), siteId, storeId, vendorId, status, from, to,
                        restricted, visible, pageable),
                responses::toReceiptResponse);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('inventory:read')")
    public ReceiptResponse get(UUID id) {
        GoodsReceipt receipt = require(id);
        siteAccessGuard.assertCanAccess(receipt.getSiteId());
        return responses.toReceiptResponse(receipt);
    }

    /**
     * Books a delivery. Idempotent on the client-generated id, so a receipt entered at the
     * gate and synced three times is one receipt.
     *
     * @param force skip the duplicate-invoice check. The storekeeper has looked at the
     *              candidates the 409 named and says this really is a second delivery.
     */
    @PreAuthorize("hasAuthority('inventory:receive')")
    public ReceiptResponse create(CreateReceiptRequest request, boolean force) {
        SiteLookup.StoreInfo store = sites.requireStore(request.storeId());
        periodLockGuard.assertOpen(store.siteId(), request.receiptDate(),
                PeriodLockGuard.Module.INVENTORY);

        var existing = receipts.findByIdAndOrgId(request.id(), orgId());
        if (existing.isPresent()) {
            return responses.toReceiptResponse(existing.get());   // the offline replay
        }
        if (!force) {
            assertNotDuplicate(request.vendorId(), request.invoiceNumber());
        }
        assertMayPriceWhatItPriced(request.lines());

        String number = documentNumbers.next(orgId(), DocumentNumberService.DocType.GOODS_RECEIPT,
                request.receiptDate());
        GoodsReceipt receipt = new GoodsReceipt(request.id(), orgId(), store.projectId(),
                store.siteId(), store.id(), number, request.receiptDate());
        receipt.setVendorId(request.vendorId());
        receipt.setPoId(request.poId());
        receipt.setInvoiceNumber(request.invoiceNumber());
        receipt.setInvoiceDate(request.invoiceDate());
        receipt.setChallanNumber(request.challanNumber());
        receipt.setVehicleNumber(request.vehicleNumber());
        receipt.setRemarks(request.remarks());
        receipt.setReceivedBy(currentUser.currentUserIdOrNull());
        receipt.submit();
        // The header goes in first: the lines carry a foreign key to it, and inserting a
        // child before its parent is a constraint violation rather than a business answer.
        receipts.save(receipt);

        List<GoodsReceiptItem> saved = saveLines(receipt.getId(), request.lines());
        applyTotals(receipt, saved);

        audit.record("GOODS_RECEIPT", receipt.getId(), "CREATE", null,
                Map.of("grnNumber", number, "storeId", store.id().toString(),
                        "lines", saved.size(), "totalAmount", receipt.getTotalAmount()), null);
        return responses.toReceiptResponse(receipt);
    }

    /**
     * What the office says the delivery cost, line by line.
     *
     * <p>Separate from booking it, because the two are separate people's answers: the
     * storekeeper has a challan, which is a delivery note and frequently carries no price
     * at all, and the invoice reaches the office days later. Asking him for a rate got a
     * guess, and a guessed rate does not stay on the document — it goes into the moving
     * average and mis-values every issue of that material for months.</p>
     *
     * <p>Only before the material has moved. Once a receipt is verified its rate is in the
     * ledger, and the way to correct a valuation from there is an adjustment, which leaves
     * the trail that silently re-pricing a posted document would erase.</p>
     */
    @PreAuthorize("hasAuthority('inventory:price')")
    public ReceiptResponse price(UUID id, PriceReceiptRequest request) {
        GoodsReceipt receipt = require(id);
        siteAccessGuard.assertCanAccess(receipt.getSiteId());
        periodLockGuard.assertOpen(receipt.getSiteId(), receipt.getReceiptDate(),
                PeriodLockGuard.Module.INVENTORY);
        if (receipt.getWorkflowStatus().hasPosted()) {
            throw new BusinessException("receipt.already-posted",
                    "Receipt " + receipt.getGrnNumber() + " has already put material into the "
                            + "store at the rate it carried. Correct the value with a stock "
                            + "adjustment instead.");
        }

        Map<UUID, GoodsReceiptItem> byId = lines.findByGrnId(id).stream()
                .collect(Collectors.toMap(GoodsReceiptItem::getId, line -> line));
        for (LinePrice priced : request.lines()) {
            GoodsReceiptItem line = byId.get(priced.lineId());
            if (line == null) {
                throw new BusinessException("receipt.unknown-line",
                        "That line is not on receipt " + receipt.getGrnNumber() + ".");
            }
            // Re-derived rather than trusted from the request: the entered unit may be
            // tonnes and the ledger works in kilograms, and the conversion is the material's
            // to state, not the pricer's.
            BigDecimal factor = materials.factorToBase(line.getMaterialId(), line.getUnitId());
            line.price(priced.rate(), priced.gstPercent(), factor);
        }
        List<GoodsReceiptItem> all = lines.saveAll(byId.values());
        applyTotals(receipt, all);

        audit.record("GOODS_RECEIPT", id, "PRICE", null,
                Map.of("grnNumber", receipt.getGrnNumber(), "linesPriced", request.lines().size(),
                        "totalAmount", receipt.getTotalAmount()), null);
        return responses.toReceiptResponse(receipt);
    }

    /**
     * The engineer's check against the challan. Verifying is what puts the material into
     * the store: one ledger row per line, at the rate on the invoice excluding tax.
     *
     * <p>Which is why it refuses a receipt with a line nobody has priced. Verification is
     * the moment a claim becomes stock, and stock carries a value — a line posted at no rate
     * would enter the moving average as free material and drag the valuation of everything
     * already in the store down with it.</p>
     *
     * <p>Posting is idempotent at the line level, so a double click or a retried request
     * cannot deliver the same lorry twice.</p>
     */
    @PreAuthorize("hasAuthority('inventory:verify')")
    public ReceiptResponse decide(UUID id, VerifyReceiptRequest request) {
        GoodsReceipt receipt = require(id);
        siteAccessGuard.assertCanAccess(receipt.getSiteId());
        periodLockGuard.assertOpen(receipt.getSiteId(), receipt.getReceiptDate(),
                PeriodLockGuard.Module.INVENTORY);

        if (receipt.getWorkflowStatus() != DocumentWorkflow.SUBMITTED
                && receipt.getWorkflowStatus() != DocumentWorkflow.DRAFT) {
            throw new BusinessException("receipt.already-decided",
                    "Receipt " + receipt.getGrnNumber() + " is already "
                            + receipt.getWorkflowStatus().name().toLowerCase() + ".");
        }

        if (request.action() == VerifyReceiptRequest.Action.REJECT) {
            receipt.reject(currentUser.currentUserIdOrNull(), request.remarks());
            audit.record("GOODS_RECEIPT", id, "REJECT", null,
                    Map.of("grnNumber", receipt.getGrnNumber()), request.remarks());
            return responses.toReceiptResponse(receipt);
        }

        List<GoodsReceiptItem> receiptLines = lines.findByGrnId(receipt.getId());
        assertEveryLinePriced(receipt, receiptLines);

        receipt.verify(Instant.now(), currentUser.currentUserIdOrNull());
        int posted = 0;
        for (GoodsReceiptItem line : receiptLines) {
            var movement = new StockLedgerService.Movement(receipt.getOrgId(),
                    receipt.getProjectId(), receipt.getSiteId(), receipt.getStoreId(),
                    line.getMaterialId(), TxnType.RECEIPT, receipt.getReceiptDate(),
                    line.getQuantityBase(), line.getRateBase(), SourceType.GOODS_RECEIPT,
                    receipt.getId(), line.getId(), null, null);
            if (ledger.post(movement) != null) {
                posted++;
            }
        }

        audit.record("GOODS_RECEIPT", id, "VERIFY", null,
                Map.of("grnNumber", receipt.getGrnNumber(), "linesPosted", posted), request.remarks());
        return responses.toReceiptResponse(receipt);
    }

    /**
     * Voids a receipt that has not yet moved stock. A verified one is never cancelled — the
     * material is in the store and the way back out is an adjustment with a reason, which
     * leaves a trail a cancellation would erase.
     */
    @PreAuthorize("hasAuthority('inventory:receive')")
    public ReceiptResponse cancel(UUID id, String reason) {
        GoodsReceipt receipt = require(id);
        siteAccessGuard.assertCanAccess(receipt.getSiteId());
        if (receipt.getWorkflowStatus().hasPosted()) {
            throw new BusinessException("receipt.already-posted",
                    "Receipt " + receipt.getGrnNumber() + " has already put material into the "
                            + "store. Correct it with a stock adjustment instead.");
        }
        receipt.cancel(reason);
        audit.record("GOODS_RECEIPT", id, "CANCEL", null,
                Map.of("grnNumber", receipt.getGrnNumber()), reason);
        return responses.toReceiptResponse(receipt);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Rejects a second booking of the same vendor invoice, naming the receipts it clashed
     * with. Placeholders are skipped: the field writes "Local" in the bill-number box for
     * every cash purchase, and three of those in a week are three deliveries.
     */
    private void assertNotDuplicate(UUID vendorId, String invoiceNumber) {
        if (vendorId == null || invoiceNumber == null) {
            return;
        }
        String normalised = invoiceNumber.trim().toUpperCase();
        if (PLACEHOLDER_INVOICES.contains(normalised)) {
            return;
        }
        List<GoodsReceipt> clashes = receipts.findSameInvoice(orgId(), vendorId, invoiceNumber);
        if (!clashes.isEmpty()) {
            throw BusinessException.conflict("receipt.duplicate-invoice",
                    "Invoice %s from this vendor is already booked as %s. Send it again with "
                            .formatted(invoiceNumber, clashes.getFirst().getGrnNumber())
                            + "force=true if this really is a second delivery.");
        }
    }

    /**
     * Refuses a rate from somebody who may book a delivery but not price one.
     *
     * <p>A refusal rather than a quiet drop. The number would be wrong, and the storekeeper
     * who typed it has to find out from the screen that it is not his to type — dropping it
     * silently teaches him the field works and leaves him believing the receipt says what he
     * entered.</p>
     */
    private void assertMayPriceWhatItPriced(List<ReceiptLine> requested) {
        boolean pricedSomething = requested.stream().anyMatch(line -> line.rate() != null);
        if (pricedSomething && !currentUser.hasPermission("inventory:price")) {
            throw BusinessException.forbidden(
                    "You can book the delivery, but the rate is set by the office against the "
                            + "invoice. Send the quantities and leave the rate out.");
        }
    }

    /**
     * Names the materials still waiting on a price, rather than saying the receipt is not
     * ready: on an eight-line delivery, which line is the whole question.
     */
    private static void assertEveryLinePriced(GoodsReceipt receipt, List<GoodsReceiptItem> items) {
        List<GoodsReceiptItem> unpriced = items.stream()
                .filter(line -> !line.isPriced())
                .toList();
        if (unpriced.isEmpty()) {
            return;
        }
        throw new BusinessException("receipt.unpriced",
                "Receipt " + receipt.getGrnNumber() + " has " + unpriced.size()
                        + " line(s) with no rate yet. Verifying is what puts the material into "
                        + "the store, and stock cannot be carried at no value — price them "
                        + "against the invoice first.");
    }

    private List<GoodsReceiptItem> saveLines(UUID grnId, List<ReceiptLine> requested) {
        List<GoodsReceiptItem> built = new ArrayList<>();
        for (ReceiptLine line : requested) {
            BigDecimal factor = materials.factorToBase(line.materialId(), line.unitId());
            GoodsReceiptItem item = new GoodsReceiptItem(grnId, line.materialId(), line.unitId(),
                    line.quantity(), line.rate(), line.gstPercent(), factor);
            item.setRemarks(line.remarks());
            built.add(item);
        }
        return lines.saveAll(built);
    }

    private static void applyTotals(GoodsReceipt receipt, List<GoodsReceiptItem> items) {
        BigDecimal subTotal = items.stream().map(GoodsReceiptItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal gst = items.stream().map(GoodsReceiptItem::getGstAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        receipt.applyTotals(subTotal, gst);
    }

    private GoodsReceipt require(UUID id) {
        return receipts.findByIdAndOrgId(id, orgId())
                .orElseThrow(() -> BusinessException.notFound("Goods receipt", id));
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }
}

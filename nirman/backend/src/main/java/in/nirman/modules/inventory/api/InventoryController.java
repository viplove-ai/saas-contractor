package in.nirman.modules.inventory.api;

import in.nirman.common.PageResponse;
import in.nirman.modules.inventory.api.dto.InventoryDtos.AdjustmentRequest;
import in.nirman.modules.inventory.api.dto.InventoryDtos.ApproveIssueRequest;
import in.nirman.modules.inventory.api.dto.InventoryDtos.CountResponse;
import in.nirman.modules.inventory.api.dto.InventoryDtos.CreateCountRequest;
import in.nirman.modules.inventory.api.dto.InventoryDtos.CreateIssueRequest;
import in.nirman.modules.inventory.api.dto.InventoryDtos.CreateReceiptRequest;
import in.nirman.modules.inventory.api.dto.InventoryDtos.CreateTransferRequest;
import in.nirman.modules.inventory.api.dto.InventoryDtos.DecideCountRequest;
import in.nirman.modules.inventory.api.dto.InventoryDtos.IssueResponse;
import in.nirman.modules.inventory.api.dto.InventoryDtos.LedgerRow;
import in.nirman.modules.inventory.api.dto.InventoryDtos.OpeningStockRequest;
import in.nirman.modules.inventory.api.dto.InventoryDtos.ReceiptResponse;
import in.nirman.modules.inventory.api.dto.InventoryDtos.ReceiveTransferRequest;
import in.nirman.modules.inventory.api.dto.InventoryDtos.StockPosition;
import in.nirman.modules.inventory.api.dto.InventoryDtos.TransferResponse;
import in.nirman.modules.inventory.api.dto.InventoryDtos.VerifyReceiptRequest;
import in.nirman.modules.inventory.api.dto.InventoryDtos.WastageRequest;
import in.nirman.modules.inventory.domain.DocumentWorkflow;
import in.nirman.modules.inventory.domain.StockTransfer;
import in.nirman.modules.inventory.service.GoodsReceiptService;
import in.nirman.modules.inventory.service.InventoryReads;
import in.nirman.modules.inventory.service.MaterialIssueService;
import in.nirman.modules.inventory.service.StockAdjustmentService;
import in.nirman.modules.inventory.service.StockCountService;
import in.nirman.modules.inventory.service.StockTransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Every inventory document and read, on the paths docs/05 specifies.
 *
 * <p>One controller because the endpoints share a prefix and a mental model — a storekeeper
 * thinks of all of this as "the store" — while the rules live in six separate services
 * behind it.</p>
 */
@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory", description = "Receipts, issues, transfers, counts, stock and the ledger")
public class InventoryController {

    private final GoodsReceiptService receipts;
    private final MaterialIssueService issues;
    private final StockTransferService transfers;
    private final StockCountService counts;
    private final StockAdjustmentService adjustments;
    private final InventoryReads reads;

    public InventoryController(GoodsReceiptService receipts, MaterialIssueService issues,
                               StockTransferService transfers, StockCountService counts,
                               StockAdjustmentService adjustments, InventoryReads reads) {
        this.receipts = receipts;
        this.issues = issues;
        this.transfers = transfers;
        this.counts = counts;
        this.adjustments = adjustments;
        this.reads = reads;
    }

    // ------------------------------------------------------------------ goods receipts

    @GetMapping("/goods-receipts")
    @Operation(summary = "Deliveries, narrowed to the caller's sites when no site is named")
    public PageResponse<ReceiptResponse> listReceipts(
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) UUID vendorId,
            @RequestParam(required = false) DocumentWorkflow status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return receipts.list(siteId, storeId, vendorId, status, from, to,
                PageRequest.of(page, Math.min(size, 200),
                        Sort.by(Sort.Direction.DESC, "receiptDate")));
    }

    @PostMapping("/goods-receipts")
    @Operation(summary = "Book a delivery. Idempotent on the client id; 409 on a repeated vendor invoice.")
    public ResponseEntity<ReceiptResponse> createReceipt(
            @Valid @RequestBody CreateReceiptRequest request,
            @RequestParam(defaultValue = "false") boolean force) {
        ReceiptResponse created = receipts.create(request, force);
        return ResponseEntity.created(
                URI.create("/api/v1/inventory/goods-receipts/" + created.id())).body(created);
    }

    @GetMapping("/goods-receipts/{id}")
    public ReceiptResponse getReceipt(@PathVariable UUID id) {
        return receipts.get(id);
    }

    @PostMapping("/goods-receipts/{id}/verify")
    @Operation(summary = "Check against the challan. Verifying is what puts material into the store.")
    public ReceiptResponse verifyReceipt(@PathVariable UUID id,
                                         @Valid @RequestBody VerifyReceiptRequest request) {
        return receipts.decide(id, request);
    }

    @PostMapping("/goods-receipts/{id}/cancel")
    @Operation(summary = "Void a receipt that has not yet moved stock")
    public ReceiptResponse cancelReceipt(@PathVariable UUID id,
                                         @RequestParam(required = false) String reason) {
        return receipts.cancel(id, reason);
    }

    // ------------------------------------------------------------------ issues

    @GetMapping("/issues")
    public PageResponse<IssueResponse> listIssues(
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) UUID boqItemId,
            @RequestParam(required = false) DocumentWorkflow status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return issues.list(siteId, storeId, boqItemId, status, from, to,
                PageRequest.of(page, Math.min(size, 200),
                        Sort.by(Sort.Direction.DESC, "issueDate")));
    }

    @PostMapping("/issues")
    @Operation(summary = "Issue material. Needs a work item to charge it to, or a stated purpose.")
    public ResponseEntity<IssueResponse> createIssue(
            @Valid @RequestBody CreateIssueRequest request) {
        IssueResponse created = issues.create(request);
        return ResponseEntity.created(
                URI.create("/api/v1/inventory/issues/" + created.id())).body(created);
    }

    @GetMapping("/issues/{id}")
    public IssueResponse getIssue(@PathVariable UUID id) {
        return issues.get(id);
    }

    @PostMapping("/issues/{id}/approve")
    @Operation(summary = "Approving takes the stock out and freezes the store's average onto the lines")
    public IssueResponse approveIssue(@PathVariable UUID id,
                                      @Valid @RequestBody ApproveIssueRequest request) {
        return issues.decide(id, request);
    }

    @PostMapping("/issues/{id}/cancel")
    public IssueResponse cancelIssue(@PathVariable UUID id,
                                     @RequestParam(required = false) String reason) {
        return issues.cancel(id, reason);
    }

    // ------------------------------------------------------------------ transfers

    @GetMapping("/transfers")
    @Operation(summary = "Transfers at either end of which the caller runs a store")
    public PageResponse<TransferResponse> listTransfers(
            @RequestParam(required = false) UUID fromStoreId,
            @RequestParam(required = false) UUID toStoreId,
            @RequestParam(required = false) StockTransfer.Status status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return transfers.list(fromStoreId, toStoreId, status, from, to,
                PageRequest.of(page, Math.min(size, 200),
                        Sort.by(Sort.Direction.DESC, "transferDate")));
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> createTransfer(
            @Valid @RequestBody CreateTransferRequest request) {
        TransferResponse created = transfers.create(request);
        return ResponseEntity.created(
                URI.create("/api/v1/inventory/transfers/" + created.id())).body(created);
    }

    @GetMapping("/transfers/{id}")
    public TransferResponse getTransfer(@PathVariable UUID id) {
        return transfers.get(id);
    }

    @PostMapping("/transfers/{id}/dispatch")
    @Operation(summary = "The lorry leaves. Out of the sending store, counted at neither end.")
    public TransferResponse dispatchTransfer(@PathVariable UUID id) {
        return transfers.dispatch(id);
    }

    @PostMapping("/transfers/{id}/receive")
    @Operation(summary = "The lorry arrives. Credits the receiving store with what actually turned up.")
    public TransferResponse receiveTransfer(@PathVariable UUID id,
                                            @Valid @RequestBody ReceiveTransferRequest request) {
        return transfers.receive(id, request);
    }

    @PostMapping("/transfers/{id}/cancel")
    public TransferResponse cancelTransfer(@PathVariable UUID id,
                                           @RequestParam(required = false) String reason) {
        return transfers.cancel(id, reason);
    }

    // ------------------------------------------------------------------ counts

    @GetMapping("/counts")
    public PageResponse<CountResponse> listCounts(
            @RequestParam(required = false) UUID storeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return counts.list(storeId, PageRequest.of(page, Math.min(size, 200),
                Sort.by(Sort.Direction.DESC, "countDate")));
    }

    @PostMapping("/counts")
    @Operation(summary = "Record a physical count. Variance against the ledger is computed server-side.")
    public ResponseEntity<CountResponse> createCount(
            @Valid @RequestBody CreateCountRequest request) {
        CountResponse created = counts.create(request);
        return ResponseEntity.created(
                URI.create("/api/v1/inventory/counts/" + created.id())).body(created);
    }

    @GetMapping("/counts/{id}")
    public CountResponse getCount(@PathVariable UUID id) {
        return counts.get(id);
    }

    @PostMapping("/counts/{id}/decision")
    @Operation(summary = "Accept the count. Differing lines post adjustments through the ledger.")
    public CountResponse decideCount(@PathVariable UUID id,
                                     @Valid @RequestBody DecideCountRequest request) {
        return counts.decide(id, request);
    }

    // ------------------------------------------------------------------ direct movements

    @PostMapping("/opening-stock")
    @Operation(summary = "Declare what a store already held. Once per store and material.")
    public LedgerRow openingStock(@Valid @RequestBody OpeningStockRequest request) {
        return adjustments.openingStock(request);
    }

    @PostMapping("/wastage")
    @Operation(summary = "Material that went into nothing. The reason is mandatory.")
    public LedgerRow wastage(@Valid @RequestBody WastageRequest request) {
        return adjustments.wastage(request);
    }

    @PostMapping("/adjustments")
    @Operation(summary = "Administrator-only correction of last resort. Signed; reason mandatory.")
    public LedgerRow adjust(@Valid @RequestBody AdjustmentRequest request) {
        return adjustments.adjust(request);
    }

    // ------------------------------------------------------------------ reads

    @GetMapping("/stock")
    @Operation(summary = "Current quantity, moving average and value; lowOnly keeps what is below reorder")
    public StockPosition stock(@RequestParam(required = false) UUID storeId,
                               @RequestParam(required = false) UUID siteId,
                               @RequestParam(required = false) UUID materialId,
                               @RequestParam(defaultValue = "false") boolean lowOnly) {
        return reads.stock(storeId, siteId, materialId, lowOnly);
    }

    @GetMapping("/ledger")
    @Operation(summary = "Every movement behind a balance, oldest first")
    public PageResponse<LedgerRow> ledger(
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) UUID materialId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return reads.ledger(storeId, materialId, from, to,
                PageRequest.of(page, Math.min(size, 500)));
    }
}

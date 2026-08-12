package in.nirman.modules.expense.api;

import in.nirman.common.PageResponse;
import in.nirman.modules.approval.api.dto.ApprovalDtos.ActionRequest;
import in.nirman.modules.approval.domain.Approval;
import in.nirman.modules.approval.service.ApprovalEngine;
import in.nirman.modules.expense.api.dto.CashDtos.AdvanceResponse;
import in.nirman.modules.expense.api.dto.CashDtos.IssueAdvanceRequest;
import in.nirman.modules.expense.api.dto.CashDtos.PaymentResponse;
import in.nirman.modules.expense.api.dto.CashDtos.RecordPaymentRequest;
import in.nirman.modules.expense.api.dto.CashDtos.RecordVendorAdvanceRequest;
import in.nirman.modules.expense.api.dto.CashDtos.SettlementResponse;
import in.nirman.modules.expense.api.dto.CashDtos.SubmitSettlementRequest;
import in.nirman.modules.expense.api.dto.CashDtos.VendorAccountResponse;
import in.nirman.modules.expense.api.dto.CashDtos.VendorBalanceRow;
import in.nirman.modules.expense.api.dto.CashDtos.VendorPurchaseRow;
import in.nirman.modules.expense.domain.SiteAdvance;
import in.nirman.modules.expense.service.PaymentService;
import in.nirman.modules.expense.service.SiteAdvanceService;
import in.nirman.modules.expense.service.VendorAccountService;
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
import java.util.List;
import java.util.UUID;

/**
 * Payments, vendor balances and site floats — the cash half of Phase 5.
 *
 * <p>One controller because they are one story: money goes out against approved bills, or it
 * goes out as a float and comes back as bills. The permissions differ sharply between them,
 * and those live on the services.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Cash", description = "Payments, vendor balances, site advances and settlements")
public class CashController {

    private final PaymentService payments;
    private final VendorAccountService vendorAccounts;
    private final SiteAdvanceService advances;
    private final ApprovalEngine approvals;

    public CashController(PaymentService payments, VendorAccountService vendorAccounts,
                          SiteAdvanceService advances, ApprovalEngine approvals) {
        this.payments = payments;
        this.vendorAccounts = vendorAccounts;
        this.advances = advances;
        this.approvals = approvals;
    }

    // ------------------------------------------------------------------ payments

    @GetMapping("/payments")
    public PageResponse<PaymentResponse> listPayments(
            @RequestParam(required = false) UUID vendorId,
            @RequestParam(required = false) UUID expenseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return payments.list(vendorId, expenseId, from, to,
                PageRequest.of(page, Math.min(size, 200),
                        Sort.by(Sort.Direction.DESC, "paymentDate")));
    }

    @PostMapping("/payments")
    @Operation(summary = "Record cash against an approved expense. Partial payments are the normal case.")
    public ResponseEntity<PaymentResponse> record(
            @Valid @RequestBody RecordPaymentRequest request) {
        PaymentResponse created = payments.record(request);
        return ResponseEntity.created(URI.create("/api/v1/payments/" + created.id())).body(created);
    }

    /**
     * Cash to a supplier before any bill of his exists. Its own endpoint rather than a
     * payment with a null expense, because the two are different acts and the second is a
     * mistake far more often than it is an advance.
     */
    @PostMapping("/vendors/{id}/advances")
    @Operation(summary = "Pay a supplier on account, against no particular bill")
    public ResponseEntity<PaymentResponse> recordVendorAdvance(
            @PathVariable UUID id, @Valid @RequestBody RecordVendorAdvanceRequest request) {
        PaymentResponse created = vendorAccounts.recordAdvance(id, request);
        return ResponseEntity.created(URI.create("/api/v1/payments/" + created.id())).body(created);
    }

    @GetMapping("/vendors/{id}/account")
    @Operation(summary = "Where one supplier's account stands: billed, paid, advanced, "
            + "outstanding — five figures, never merged into a balance")
    public VendorAccountResponse vendorAccount(@PathVariable UUID id) {
        return vendorAccounts.account(id);
    }

    @GetMapping("/vendors/{id}/purchases")
    @Operation(summary = "What the supplier has delivered, line by line, with quantity and rate")
    public List<VendorPurchaseRow> vendorPurchases(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return vendorAccounts.purchases(id, from, to);
    }

    @GetMapping("/vendors/balances")
    @Operation(summary = "Approved cost, cash paid and payable per vendor — the three figures, never merged")
    public List<VendorBalanceRow> vendorBalances(@RequestParam(required = false) UUID vendorId) {
        return payments.vendorBalances(vendorId);
    }

    // ------------------------------------------------------------------ site advances

    @GetMapping("/advances")
    public PageResponse<AdvanceResponse> listAdvances(
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) SiteAdvance.SettlementStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return advances.list(siteId, userId, status,
                PageRequest.of(page, Math.min(size, 200),
                        Sort.by(Sort.Direction.DESC, "advanceDate")));
    }

    @PostMapping("/advances")
    @Operation(summary = "Hand a float to a member of staff. Accountant and administrator only.")
    public ResponseEntity<AdvanceResponse> issueAdvance(
            @Valid @RequestBody IssueAdvanceRequest request) {
        AdvanceResponse created = advances.issue(request);
        return ResponseEntity.created(URI.create("/api/v1/advances/" + created.id())).body(created);
    }

    @GetMapping("/advances/{id}")
    public AdvanceResponse getAdvance(@PathVariable UUID id) {
        return advances.get(id);
    }

    @GetMapping("/advances/balances")
    @Operation(summary = "Floats still in somebody's pocket")
    public List<AdvanceResponse> advanceBalances(@RequestParam(required = false) UUID siteId,
                                                  @RequestParam(required = false) UUID userId) {
        return advances.openBalances(siteId, userId);
    }

    // ------------------------------------------------------------------ settlements

    @GetMapping("/advances/{id}/settlements")
    public List<SettlementResponse> settlements(@PathVariable UUID id) {
        return advances.settlementsFor(id);
    }

    @PostMapping("/advances/{id}/settlements")
    @Operation(summary = "Account for a float: these are the bills, this is the cash back. Nothing moves until approved.")
    public ResponseEntity<SettlementResponse> submitSettlement(
            @PathVariable UUID id, @Valid @RequestBody SubmitSettlementRequest request) {
        SettlementResponse created = advances.submitSettlement(id, request);
        return ResponseEntity.created(
                URI.create("/api/v1/settlements/" + created.id())).body(created);
    }

    @PostMapping("/settlements/{id}/decision")
    @Operation(summary = "Approve or reject a settlement. Approving is what moves the float.")
    public SettlementResponse decideSettlement(@PathVariable UUID id,
                                               @Valid @RequestBody ActionRequest request) {
        Approval pending = approvals.requirePending(
                SiteAdvanceService.SETTLEMENT_ENTITY_TYPE, id);
        approvals.act(pending.getId(), request.action().toStatus(), request.remarks());
        return advances.getSettlement(id);
    }
}

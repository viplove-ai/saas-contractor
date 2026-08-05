package in.nirman.modules.expense.api;

import in.nirman.common.PageResponse;
import in.nirman.modules.approval.api.dto.ApprovalDtos.ActionRequest;
import in.nirman.modules.expense.api.dto.ExpenseDtos.AttachBillRequest;
import in.nirman.modules.expense.api.dto.ExpenseDtos.CreateExpenseRequest;
import in.nirman.modules.expense.api.dto.ExpenseDtos.ExpenseResponse;
import in.nirman.modules.expense.api.dto.ExpenseDtos.UpdateExpenseRequest;
import in.nirman.modules.expense.api.dto.ExpenseDtos.VoidExpenseRequest;
import in.nirman.modules.expense.domain.Expense;
import in.nirman.modules.expense.service.ExpenseService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/expenses")
@Tag(name = "Expenses", description = "Site spending: entry, evidence, approval and voiding")
public class ExpenseController {

    private final ExpenseService expenses;

    public ExpenseController(ExpenseService expenses) {
        this.expenses = expenses;
    }

    @GetMapping
    @Operation(summary = "Expenses, narrowed to the caller's sites — and to their own rows for a supervisor")
    public PageResponse<ExpenseResponse> list(
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) UUID vendorId,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) Expense.Workflow status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return expenses.list(siteId, vendorId, categoryId, status, from, to,
                PageRequest.of(page, Math.min(size, 200),
                        Sort.by(Sort.Direction.DESC, "expenseDate")));
    }

    @PostMapping
    @Operation(summary = "Book an expense. 409 with candidates when it looks like one already on file.")
    public ResponseEntity<ExpenseResponse> create(
            @Valid @RequestBody CreateExpenseRequest request,
            @RequestParam(defaultValue = "false") boolean force) {
        ExpenseResponse created = expenses.create(request, force);
        return ResponseEntity.created(URI.create("/api/v1/expenses/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public ExpenseResponse get(@PathVariable UUID id) {
        return expenses.get(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit. Draft, returned and rejected rows only — an approved expense is voided instead.")
    public ExpenseResponse update(@PathVariable UUID id,
                                  @Valid @RequestBody UpdateExpenseRequest request) {
        return expenses.update(id, request);
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Send for approval. Needs a bill, a photograph or a written reason above the threshold.")
    public ExpenseResponse submit(@PathVariable UUID id) {
        return expenses.submit(id);
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve, reject or return. Routes to the same engine as the approvals queue.")
    public ExpenseResponse decide(@PathVariable UUID id,
                                  @Valid @RequestBody ActionRequest request) {
        return expenses.decide(id, request.action().toStatus(), request.remarks());
    }

    @PostMapping("/{id}/void")
    @Operation(summary = "Void. Never a hard delete: an approved expense is part of the record.")
    public ExpenseResponse voidExpense(@PathVariable UUID id,
                                       @Valid @RequestBody VoidExpenseRequest request) {
        return expenses.voidExpense(id, request.reason());
    }

    @PostMapping("/{id}/attachments")
    @Operation(summary = "Link an uploaded bill photograph to the expense it evidences")
    public ExpenseResponse attach(@PathVariable UUID id,
                                  @Valid @RequestBody AttachBillRequest request) {
        return expenses.attachBill(id, request);
    }
}

package in.nirman.modules.expense.api;

import in.nirman.modules.expense.api.dto.DepositDtos.DepositRegister;
import in.nirman.modules.expense.api.dto.DepositDtos.RefundResponse;
import in.nirman.modules.expense.api.dto.DepositDtos.SettleDepositRequest;
import in.nirman.modules.expense.service.ExpenseDepositService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The refundable part of a bill, and what became of it.
 *
 * <p>Its own path rather than under {@code /expenses}, because the question is asked months
 * after the expense and usually by somebody else: "what are we holding out with the
 * electricity board" is a treasury question that happens to be answered out of the expense
 * register.</p>
 */
@RestController
@RequestMapping("/api/v1/deposits")
@Tag(name = "Deposits", description = "Refundable deposits placed on ordinary bills, and their settlement")
public class DepositController {

    private final ExpenseDepositService deposits;

    public DepositController(ExpenseDepositService deposits) {
        this.deposits = deposits;
    }

    @GetMapping
    @Operation(summary = "Every bill carrying a deposit, with what has come back on each")
    public DepositRegister register(
            @RequestParam(required = false) UUID siteId,
            @RequestParam(defaultValue = "true") boolean openOnly) {
        return deposits.register(siteId, openOnly);
    }

    @GetMapping("/{expenseId}/settlements")
    @Operation(summary = "What has been settled against one deposit")
    public List<RefundResponse> settlements(@PathVariable UUID expenseId) {
        return deposits.settlements(expenseId);
    }

    @PostMapping("/{expenseId}/settlements")
    @Operation(summary = "Record money back, or give up on it. Never more than is outstanding.")
    public RefundResponse settle(@PathVariable UUID expenseId,
                                 @Valid @RequestBody SettleDepositRequest request) {
        return deposits.settle(expenseId, request);
    }
}

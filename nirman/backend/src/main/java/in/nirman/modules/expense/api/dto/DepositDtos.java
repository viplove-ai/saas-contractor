package in.nirman.modules.expense.api.dto;

import in.nirman.modules.expense.domain.Expense;
import in.nirman.modules.expense.domain.ExpenseRefund;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The refundable part of a bill, and what became of it.
 *
 * <p>Held apart from {@link ExpenseDtos} because these are a different screen's shapes: the
 * expense form asks how much of the bill is a deposit, and this register asks what has come
 * back — months later, usually to a different person.</p>
 */
public final class DepositDtos {

    private DepositDtos() {
    }

    /**
     * Money settled against a deposit.
     *
     * <p>{@code amount} is stated rather than assumed to be the whole outstanding balance,
     * for the reason a payment states its own: the electricity board adjusts half of a meter
     * security against the final bill and refunds the rest by cheque two months later, and
     * both halves are real events.</p>
     *
     * @param reason why it is not coming back. Required on a {@code WRITTEN_OFF} and
     *               meaningless on a receipt — a deposit that disappears from the register
     *               without a sentence cannot be asked about six months later.
     */
    public record SettleDepositRequest(
            @NotNull ExpenseRefund.Outcome outcome,
            @NotNull LocalDate settledOn,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
            @Size(max = 20) String paymentMode,
            @Size(max = 80) String referenceNumber,
            @Size(max = 500) String reason,
            String remarks) {
    }

    public record RefundResponse(
            UUID id,
            UUID expenseId,
            ExpenseRefund.Outcome outcome,
            LocalDate settledOn,
            BigDecimal amount,
            String paymentMode,
            String referenceNumber,
            String reason,
            String remarks) {
    }

    /**
     * One deposit on the register, with the bill it came in on.
     *
     * <p>The expense's own description and vendor travel with it because the question the
     * office asks of this screen is "whose money are we holding out with whom", and an
     * expense number alone sends somebody to a second screen to find out.</p>
     */
    public record DepositRow(
            UUID expenseId,
            String expenseNumber,
            UUID siteId,
            LocalDate expenseDate,
            String description,
            String categoryName,
            UUID vendorId,
            String vendorName,
            /** The whole bill, of which the deposit is a part. */
            BigDecimal totalAmount,
            BigDecimal refundableAmount,
            BigDecimal refundedAmount,
            BigDecimal writtenOffAmount,
            BigDecimal outstandingAmount,
            LocalDate refundExpectedOn,
            /** True once the expected date has gone by with money still out there. */
            boolean overdue,
            Expense.DepositStatus status,
            Expense.Workflow workflowStatus,
            List<RefundResponse> settlements) {
    }

    /**
     * What the register adds up to.
     *
     * <p>{@code outstanding} is the figure the whole screen exists for — the few lakh a
     * contractor running six sites has sitting with the electricity board and three plant
     * hirers, which used to be recorded in the memory of whoever paid it.</p>
     */
    public record DepositRegister(
            BigDecimal placed,
            BigDecimal received,
            BigDecimal writtenOff,
            BigDecimal outstanding,
            /** Of the outstanding, how much is past the date somebody expected it back. */
            BigDecimal overdue,
            int depositCount,
            int openCount,
            List<DepositRow> rows) {
    }
}

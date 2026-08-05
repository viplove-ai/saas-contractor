package in.nirman.modules.expense.api.dto;

import in.nirman.modules.expense.domain.AdvanceSettlement;
import in.nirman.modules.expense.domain.SiteAdvance;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Payments, site advances and their settlements. */
public final class CashDtos {

    private CashDtos() {
    }

    // ------------------------------------------------------------------ payments

    /**
     * Cash against one expense. Partial is the normal case, so the amount is stated rather
     * than assumed to be the balance — paying a supplier ₹20,000 on account against a
     * ₹53,000 bill is one payment, not a rounding error.
     */
    public record RecordPaymentRequest(
            @NotNull UUID expenseId,
            @NotNull LocalDate paymentDate,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
            @NotBlank @Size(max = 20) String paymentMode,
            @Size(max = 80) String referenceNumber,
            @Size(max = 60) String bankAccount,
            String remarks) {
    }

    public record PaymentResponse(
            UUID id,
            String paymentNumber,
            UUID expenseId,
            String expenseNumber,
            UUID vendorId,
            String vendorName,
            LocalDate paymentDate,
            BigDecimal amount,
            String paymentMode,
            String referenceNumber,
            String bankAccount,
            String remarks,
            Instant reconciledAt,
            Long version) {
    }

    /**
     * What is owed to one vendor, in the three figures that must never be merged: approved
     * cost, cash paid, and the difference.
     */
    public record VendorBalanceRow(
            UUID vendorId,
            String vendorCode,
            String vendorName,
            BigDecimal approvedAmount,
            BigDecimal paidAmount,
            BigDecimal payableAmount,
            int openBills,
            LocalDate oldestUnpaidDate) {
    }

    // ------------------------------------------------------------------ site advances

    public record IssueAdvanceRequest(
            @NotNull UUID siteId,
            @NotNull UUID issuedToUserId,
            @NotNull LocalDate advanceDate,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
            @NotBlank @Size(max = 20) String paymentMode,
            @Size(max = 80) String referenceNumber,
            @NotBlank String purpose,
            String remarks) {
    }

    public record AdvanceResponse(
            UUID id,
            String advanceNumber,
            UUID siteId,
            UUID issuedToUserId,
            String issuedToName,
            LocalDate advanceDate,
            BigDecimal amount,
            String paymentMode,
            String referenceNumber,
            String purpose,
            BigDecimal adjustedAmount,
            BigDecimal returnedAmount,
            /** Issued less cleared — what is still in the holder's pocket. */
            BigDecimal balanceAmount,
            SiteAdvance.SettlementStatus settlementStatus,
            Instant closedAt,
            String remarks,
            Long version) {
    }

    // ------------------------------------------------------------------ settlements

    /**
     * A holder accounting for his float: these are the bills, this is the cash back.
     *
     * <p>{@code expenseIds} must be approved expenses at the same site, each unclaimed
     * against any other float. Nothing moves on the advance until this is approved.</p>
     */
    public record SubmitSettlementRequest(
            @NotNull LocalDate settlementDate,
            List<UUID> expenseIds,
            @DecimalMin("0") BigDecimal returnedAmount,
            String remarks) {
    }

    public record SettlementLineResponse(
            UUID expenseId,
            String expenseNumber,
            String description,
            LocalDate expenseDate,
            BigDecimal amount) {
    }

    public record SettlementResponse(
            UUID id,
            String settlementNumber,
            UUID advanceId,
            String advanceNumber,
            LocalDate settlementDate,
            BigDecimal expensesAmount,
            BigDecimal returnedAmount,
            /** Bills plus cash back — how much of the float this clears. */
            BigDecimal clearedAmount,
            AdvanceSettlement.Status status,
            Integer pendingLevel,
            String pendingWithRole,
            Instant approvedAt,
            String rejectionReason,
            String remarks,
            Long version,
            List<SettlementLineResponse> lines) {
    }
}

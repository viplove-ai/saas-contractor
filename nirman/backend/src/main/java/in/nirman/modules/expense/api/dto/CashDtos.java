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

    /**
     * Money paid to a supplier before there is a bill to put it against.
     *
     * <p>It is how a lorry of steel gets loaded, and the system could not record it: a
     * payment had to name an expense. The accountant was told, in as many words by the
     * overpayment refusal, to "record the excess as a separate advance" — and had nowhere to
     * record it. So it went in a second book, which is where a supplier's account and the
     * system's account of him stop agreeing.</p>
     */
    public record RecordVendorAdvanceRequest(
            @NotNull LocalDate paymentDate,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
            @NotBlank @Size(max = 20) String paymentMode,
            @Size(max = 80) String referenceNumber,
            @Size(max = 60) String bankAccount,
            /** Why it went out early. An advance with no reason is indistinguishable later
             *  from a payment somebody forgot to attach to a bill. */
            @NotBlank @Size(max = 500) String remarks) {
    }

    /**
     * Where a supplier's account stands, in figures that are each answerable on their own.
     *
     * <p>Deliberately five numbers rather than one balance. "He is owed 40,000" is the
     * question nobody can settle an argument with — the supplier wants to know which bills,
     * the accountant wants to know what has already gone out, and the advance sitting
     * against no bill is the figure both of them forget. A single net balance hides all
     * three, and it is the one number that cannot be reconciled against anything he holds.</p>
     *
     * @param billedAmount     approved bills from him, whether paid or not
     * @param paidAgainstBills cash that went out against those bills
     * @param advancePaid      cash paid on account, against no bill of his
     * @param outstanding      billed less paid: what he is still owed on bills raised
     * @param netPosition      outstanding less the unadjusted advance. Negative means we are
     *                         ahead of him — money is sitting with him against nothing yet.
     */
    public record VendorAccountResponse(
            UUID vendorId,
            String vendorCode,
            String vendorName,
            BigDecimal openingBalance,
            BigDecimal billedAmount,
            BigDecimal paidAgainstBills,
            BigDecimal advancePaid,
            BigDecimal outstanding,
            BigDecimal netPosition,
            int openBills,
            LocalDate oldestUnpaidDate,
            BigDecimal purchasedValue,
            int deliveryCount) {
    }

    /** One line a supplier delivered: what, how much, at what rate, on which bill. */
    public record VendorPurchaseRow(
            UUID receiptId,
            String grnNumber,
            LocalDate receiptDate,
            String invoiceNumber,
            UUID siteId,
            UUID materialId,
            String materialCode,
            String materialName,
            String unitCode,
            BigDecimal quantity,
            /** Null while the office has not priced the line — the delivery still happened. */
            BigDecimal rate,
            BigDecimal amount,
            /** False until the delivery was verified into the store. */
            boolean received) {
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

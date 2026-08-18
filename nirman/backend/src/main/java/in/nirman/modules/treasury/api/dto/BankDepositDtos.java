package in.nirman.modules.treasury.api.dto;

import in.nirman.modules.treasury.domain.BankDeposit;
import in.nirman.modules.treasury.domain.ProjectSecurity;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The FDR register: what the company holds at its banks, and what each one is doing. */
public final class BankDepositDtos {

    private BankDepositDtos() {
    }

    /**
     * One certificate.
     *
     * @param pledgedTo         the contract holding it right now, or null when it is in hand.
     *                          Derived per call from the securities pointing at this row — see
     *                          the V42 header on why it is not a column.
     * @param history           every pledge it has carried, oldest first, the live one included.
     *                          This is what makes an FDR's reuse legible as one thread instead
     *                          of as unrelated rows on two contracts.
     * @param daysToMaturity    negative once matured. Null where no maturity date has been read
     *                          off the certificate yet, because a renewal cannot be counted down
     *                          to a date nobody has.
     */
    public record DepositResponse(
            UUID id,
            String depositNumber,
            String bankName,
            String branch,
            BigDecimal amount,
            LocalDate issuedOn,
            LocalDate maturityOn,
            BigDecimal interestRate,
            BankDeposit.Status status,
            LocalDate closedOn,
            String closedReason,
            String notes,
            PledgeRow pledgedTo,
            List<PledgeRow> history,
            List<PhotoRow> photos,
            Integer daysToMaturity,
            Long version) {
    }

    /** A contract's hold on a certificate, live or finished. */
    public record PledgeRow(
            UUID securityId,
            UUID projectId,
            String projectCode,
            String projectName,
            ProjectSecurity.Type securityType,
            ProjectSecurity.Status status,
            LocalDate lodgedOn,
            LocalDate releasedOn) {
    }

    public record PhotoRow(
            UUID attachmentId,
            String caption) {
    }

    /**
     * The register and the four figures the office actually asks for.
     *
     * @param idleAmount what is held and pledged to nothing — the money available for the next
     *                   tender, which is the question V38's per-contract register could not
     *                   answer at all
     */
    public record RegisterResponse(
            List<DepositResponse> deposits,
            Summary summary) {
    }

    public record Summary(
            int heldCount,
            BigDecimal heldAmount,
            int pledgedCount,
            BigDecimal pledgedAmount,
            int idleCount,
            BigDecimal idleAmount,
            int closedCount,
            int maturingSoonCount) {
    }

    public record CreateDepositRequest(
            UUID id,
            @NotBlank @Size(max = 80) String depositNumber,
            @NotBlank @Size(max = 160) String bankName,
            @Size(max = 160) String branch,
            @NotNull @Positive @Digits(integer = 16, fraction = 2) BigDecimal amount,
            @NotNull LocalDate issuedOn,
            LocalDate maturityOn,
            @PositiveOrZero @Digits(integer = 3, fraction = 3) BigDecimal interestRate,
            String notes) {
    }

    /**
     * The amendable half — what the certificate says, transcribed and sometimes mistyped.
     * Closing is its own endpoint: the bank paying out is an event, not a field.
     */
    public record UpdateDepositRequest(
            @NotBlank @Size(max = 80) String depositNumber,
            @NotBlank @Size(max = 160) String bankName,
            @Size(max = 160) String branch,
            @NotNull @Positive @Digits(integer = 16, fraction = 2) BigDecimal amount,
            @NotNull LocalDate issuedOn,
            LocalDate maturityOn,
            @PositiveOrZero @Digits(integer = 3, fraction = 3) BigDecimal interestRate,
            String notes,
            @NotNull Long version) {
    }

    public record CloseDepositRequest(
            @NotNull LocalDate closedOn,
            @Size(max = 500) String reason,
            @NotNull Long version) {
    }

    public record ReopenDepositRequest(
            @NotNull Long version) {
    }

    public record AddPhotoRequest(
            @NotNull UUID attachmentId,
            @Size(max = 300) String caption) {
    }
}

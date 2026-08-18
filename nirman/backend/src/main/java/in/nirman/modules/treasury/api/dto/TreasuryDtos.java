package in.nirman.modules.treasury.api.dto;

import in.nirman.modules.treasury.domain.ProjectSecurity.Instrument;
import in.nirman.modules.treasury.domain.ProjectSecurity.Status;
import in.nirman.modules.treasury.domain.ProjectSecurity.Type;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for the treasury register and its dashboard. */
public final class TreasuryDtos {

    private TreasuryDtos() {
    }

    // ------------------------------------------------------------------ the register

    /**
     * One deposit, with its contract named beside it.
     *
     * <p>The project's code and name ride along because every screen that shows a deposit shows
     * it in a list spanning contracts, and resolving each row client-side would leave a deposit
     * on a project the caller has not loaded showing a dash.</p>
     *
     * @param daysToRelease negative when the release date has passed. Computed per call against
     *                      today, never stored — a stored countdown is wrong by morning.
     */
    public record SecurityResponse(
            UUID id,
            UUID projectId,
            String projectCode,
            String projectName,
            Type securityType,
            Instrument instrument,
            Status status,
            BigDecimal amount,
            BigDecimal heldAmount,
            String basis,
            String referenceNo,
            String bankName,
            String branch,
            LocalDate lodgedOn,
            LocalDate maturityOn,
            LocalDate expectedReleaseOn,
            LocalDate releasedOn,
            String releaseReference,
            UUID redeployedToProjectId,
            String redeployedToProjectCode,
            String forfeitedReason,
            String notes,
            Integer daysToRelease,
            /** Overdue, or the deposit matures before the department is due to give it back. */
            boolean needsAttention,
            boolean freeToReuse,
            Long version) {
    }

    public record CreateSecurityRequest(
            @NotNull UUID projectId,
            @NotNull Type securityType,
            @NotNull Instrument instrument,
            @NotNull @PositiveOrZero @Digits(integer = 16, fraction = 2) BigDecimal amount,
            @Size(max = 500) String basis,
            LocalDate expectedReleaseOn,
            String notes) {
    }

    /**
     * The amendable half. Status is not in it: lodging, releasing and forfeiting are acts with
     * their own endpoints, because each one is a statement about the company's money to a
     * department and none of them should be reachable by typing a word into a form.
     */
    public record UpdateSecurityRequest(
            @NotNull Instrument instrument,
            @NotNull @PositiveOrZero @Digits(integer = 16, fraction = 2) BigDecimal amount,
            @Size(max = 500) String basis,
            @Size(max = 80) String referenceNo,
            @Size(max = 160) String bankName,
            @Size(max = 160) String branch,
            LocalDate lodgedOn,
            LocalDate maturityOn,
            LocalDate expectedReleaseOn,
            String notes,
            @NotNull Long version) {
    }

    public record LodgeRequest(
            @NotNull LocalDate lodgedOn,
            @Size(max = 80) String referenceNo,
            @Size(max = 160) String bankName,
            @Size(max = 160) String branch,
            LocalDate maturityOn,
            LocalDate expectedReleaseOn,
            @NotNull Long version) {
    }

    /**
     * @param retainedToDate the running total the department has withheld, not this bill's
     *                       slice. Absolute because that is the figure on the bill summary, and
     *                       because an increment sent twice by a flaky connection would
     *                       overstate what is held.
     */
    public record RetainedRequest(
            @NotNull @PositiveOrZero @Digits(integer = 16, fraction = 2) BigDecimal retainedToDate,
            @NotNull LocalDate asOf,
            @NotNull Long version) {
    }

    public record ReleaseRequest(
            @NotNull LocalDate releasedOn,
            @Size(max = 120) String reference,
            @NotNull Long version) {
    }

    public record RedeployRequest(
            @NotNull UUID toProjectId,
            @NotNull Long version) {
    }

    public record ForfeitRequest(
            @NotBlank @Size(max = 500) String reason,
            @NotNull Long version) {
    }

    // ------------------------------------------------------------------ the proposal

    /**
     * What the contract says a deposit ought to be. Read once, on the form; the register keeps
     * what was actually lodged.
     *
     * @param alreadyRecorded true when this contract already carries a deposit of this kind, so
     *                        the form can offer it as a fact rather than as a suggestion to add
     *                        a second one
     */
    public record ProposalResponse(
            Type securityType,
            Instrument instrument,
            BigDecimal amount,
            String basis,
            LocalDate expectedReleaseOn,
            boolean alreadyRecorded) {
    }

    // ------------------------------------------------------------------ the dashboard

    /** One month of the release calendar. Lodged and retained stay apart, as everywhere else. */
    public record ReleaseBucket(
            /** ISO year-month, {@code 2026-08}. */
            String month,
            BigDecimal lodged,
            BigDecimal retained,
            int count) {
    }

    public record TypeSlice(
            Type securityType,
            BigDecimal held,
            int count,
            /** Still to be found — the DUE rows of this kind. */
            BigDecimal awaiting) {
    }

    /**
     * How much is sitting with one bank. Worth a panel of its own: a contractor with four
     * crores of FDRs at one branch has a renewal conversation and a limit problem that a
     * company total hides completely.
     */
    public record BankSlice(
            String bankName,
            BigDecimal held,
            int count) {
    }

    public record ProjectTreasuryRow(
            UUID projectId,
            String projectCode,
            String projectName,
            String status,
            BigDecimal contractValue,
            BigDecimal emdHeld,
            BigDecimal performanceGuaranteeHeld,
            BigDecimal additionalPgHeld,
            BigDecimal securityDepositHeld,
            /** Everything the department is holding against this contract. */
            BigDecimal totalHeld,
            /** Still to be lodged against it — the money the contract has yet to ask for. */
            BigDecimal awaiting,
            BigDecimal releasableNow,
            LocalDate nextReleaseOn,
            BigDecimal nextReleaseAmount,
            int attentionCount) {
    }

    /**
     * The company's blocked money in one view.
     *
     * <p>{@link #totalBlocked} always prints with {@link #lodgedFromOwnFunds} and
     * {@link #retainedFromBills} beside it and never in place of them. They are two different
     * facts: one is cash the contractor took out of his own working capital and can get back
     * into a bank account, the other is money the department kept out of bills he never
     * received. Adding them is a real question — how much of the contract's value is not with
     * us — and reporting only the sum claims a bank balance the company never had.</p>
     *
     * @param asOf every countdown on this screen is measured from here
     */
    public record TreasuryDashboard(
            LocalDate asOf,

            BigDecimal totalBlocked,
            BigDecimal lodgedFromOwnFunds,
            BigDecimal retainedFromBills,

            /** DUE and not yet placed. Money the company still has to find. */
            BigDecimal awaitingLodgement,
            int awaitingLodgementCount,

            /** Release date on or before today and nobody has released it. Chase these. */
            BigDecimal releasableNow,
            int releasableNowCount,
            BigDecimal releasingIn30Days,
            BigDecimal releasingIn90Days,
            BigDecimal releasingIn365Days,

            /** Free to fund the next tender: released, and nothing named as its next home. */
            BigDecimal freeToReuse,
            int freeToReuseCount,

            /** Indian financial year to date — April to March, which is how the books close. */
            BigDecimal releasedThisFinancialYear,
            BigDecimal redeployedThisFinancialYear,
            /**
             * All time, not this year. Forfeiture carries no date of its own — it is rare
             * enough that nobody has asked for one, and dating it by the financial year would
             * quietly drop last year's loss off a screen the office reads as complete.
             */
            BigDecimal forfeitedToDate,

            List<TypeSlice> byType,
            List<BankSlice> byBank,
            /** Eighteen months forward from this month. */
            List<ReleaseBucket> releaseCalendar,
            List<ProjectTreasuryRow> projects,

            /** Overdue for release, or maturing before release is due. Both need a telephone call. */
            List<SecurityResponse> needsAttention,
            List<SecurityResponse> reusable,

            String caveat) {
    }
}

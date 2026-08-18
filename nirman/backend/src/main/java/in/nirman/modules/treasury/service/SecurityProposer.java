package in.nirman.modules.treasury.service;

import in.nirman.modules.treasury.domain.ProjectSecurity.Instrument;
import in.nirman.modules.treasury.domain.ProjectSecurity.Type;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * What the contract says each deposit ought to be, and when it ought to come back.
 *
 * <p>A proposal and never a record. Nothing here is stored: {@code project_securities} keeps
 * the amount actually lodged, which differs from the amount the rule says whenever a bank
 * issues an FDR for a rounder figure — and a register that recomputed its rows would report
 * the rule instead of the bank. The office reads a proposal once, on the form, and then owns
 * the number.</p>
 *
 * <p>Pure, static and free of Spring on purpose: these are four arithmetic rules off a printed
 * form, and they are the part of this module most worth being able to test as arithmetic.</p>
 */
public final class SecurityProposer {

    /**
     * The CPWD standard form's own figures, used only where the notice was silent or never
     * read. Every proposal says which of the two it came from, because "5% because the notice
     * said so" and "5% because that is usually the number" are different claims and the second
     * one is the one somebody should check.
     */
    static final BigDecimal DEFAULT_EMD_PERCENT = new BigDecimal("2.5");
    static final BigDecimal DEFAULT_PG_PERCENT = new BigDecimal("5");
    static final BigDecimal DEFAULT_SD_PERCENT = new BigDecimal("2.5");
    static final BigDecimal DEFAULT_APG_THRESHOLD_PERCENT = new BigDecimal("80");

    static final int CONSTRUCTION_GUARANTEE_MONTHS = 12;
    static final int MAINTENANCE_GUARANTEE_MONTHS = 6;

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private SecurityProposer() {
    }

    /** Which release rule the guarantee follows. Null on the project means nobody has said. */
    public enum WorkNature { CONSTRUCTION, MAINTENANCE }

    /**
     * Everything the four rules read.
     *
     * @param tenderEstimate   the cost the notice put to tender. The guarantee is five per
     *                         cent of this <em>or</em> of the accepted bid, whichever is
     *                         higher, so bidding low does not shrink it — pass it whenever it
     *                         is known.
     * @param acceptedBid      what the work pays at the rate bid: the estimate moved by the
     *                         contractor's quote, held on the project as {@code quoted_cost}
     * @param workStartDate    the day work began, which is what the earnest money's release is
     *                         now dated from. V41 removed the allotment letter, the date that
     *                         actually frees the EMD, because it was never entered; work having
     *                         started says the same thing from the other side, since the
     *                         contract had been awarded by then.
     * @param completionOn     the day the guarantee clock starts: the day work actually
     *                         finished where that is recorded, and the expected completion
     *                         until then
     */
    public record ContractFacts(
            BigDecimal tenderEstimate,
            BigDecimal acceptedBid,
            WorkNature workNature,
            LocalDate workStartDate,
            LocalDate completionOn,
            Integer defectLiabilityMonths,
            NoticeTerms notice) {
    }

    /**
     * What the notice demanded, where one was read. Every field is nullable — a notice that
     * stated no additional-guarantee clause is a reading, not a gap (see V33).
     */
    public record NoticeTerms(
            BigDecimal emdAmount,
            BigDecimal performanceGuaranteePercent,
            BigDecimal securityDepositPercent,
            BigDecimal apgThresholdPercent,
            String apgMethod,
            BigDecimal apgPercent) {

        public static final NoticeTerms NONE =
                new NoticeTerms(null, null, null, null, null, null);
    }

    /**
     * One proposed deposit.
     *
     * @param basis              how the figure was arrived at, in words, for the form to show
     *                           beside it and for the register to keep once it is accepted
     * @param expectedReleaseOn  null where the contract's calendar cannot yet answer it. A
     *                           blank is deliberate: a guessed release date is worse than none,
     *                           because the office stops chasing an FDR that a wrong date says
     *                           is not due yet.
     */
    public record Proposal(
            Type type,
            Instrument instrument,
            BigDecimal amount,
            String basis,
            LocalDate expectedReleaseOn) {
    }

    /**
     * The full schedule for a contract, in the order the money goes out.
     *
     * <p>An additional guarantee appears only when the bid actually triggered one — a zero row
     * would say the contract carries an APG of nothing, which is a different statement from
     * carrying none.</p>
     */
    public static List<Proposal> propose(ContractFacts facts) {
        List<Proposal> proposals = new ArrayList<>(4);
        proposals.add(earnestMoney(facts));
        proposals.add(performanceGuarantee(facts));
        additionalGuarantee(facts).ifPresent(proposals::add);
        proposals.add(securityDeposit(facts));
        return proposals;
    }

    // ------------------------------------------------------------------ the four rules

    private static Proposal earnestMoney(ContractFacts facts) {
        NoticeTerms notice = notice(facts);
        BigDecimal tenderValue = facts.tenderEstimate();
        BigDecimal amount;
        String basis;
        if (notice.emdAmount() != null) {
            amount = round(notice.emdAmount());
            basis = "As stated in the notice.";
        } else if (tenderValue != null) {
            amount = percentOf(DEFAULT_EMD_PERCENT, tenderValue);
            basis = DEFAULT_EMD_PERCENT.stripTrailingZeros().toPlainString()
                    + "% of the estimated cost put to tender (standard form; the notice was not "
                    + "read for this contract).";
        } else {
            amount = BigDecimal.ZERO;
            basis = "No estimated cost recorded, so the earnest money cannot be worked out. "
                    + "Enter the amount actually lodged.";
        }
        return new Proposal(Type.EMD, Instrument.FDR, amount, basis, emdReleaseDate(facts));
    }

    /**
     * The earnest money comes back once the bid stops being live, which the allotment letter
     * used to date. Work having started says the same thing and is a date somebody enters: the
     * contract had been awarded by the day the men were on the site.
     *
     * <p>It is the later of the two readings — the letter arrives first — so a deposit still
     * held on the day work starts is genuinely overdue by then, which is the direction this
     * ought to err in. No start date recorded, no release date proposed.</p>
     */
    private static LocalDate emdReleaseDate(ContractFacts facts) {
        return facts.workStartDate();
    }

    private static Proposal performanceGuarantee(ContractFacts facts) {
        NoticeTerms notice = notice(facts);
        BigDecimal percent = notice.performanceGuaranteePercent() != null
                ? notice.performanceGuaranteePercent() : DEFAULT_PG_PERCENT;
        boolean fromNotice = notice.performanceGuaranteePercent() != null;

        BigDecimal base = guaranteeBase(facts);
        BigDecimal amount = base == null ? BigDecimal.ZERO : percentOf(percent, base);

        String basis;
        if (base == null) {
            basis = "Neither an estimated cost nor a contract value is recorded, so the "
                    + "guarantee cannot be worked out. Enter the amount actually lodged.";
        } else {
            basis = percent.stripTrailingZeros().toPlainString() + "% of "
                    + describeGuaranteeBase(facts)
                    + (fromNotice ? ", as stated in the notice."
                                  : " (standard form; the notice was not read for this contract).");
        }
        return new Proposal(Type.PERFORMANCE_GUARANTEE, Instrument.FDR, amount, basis,
                guaranteeReleaseDate(facts));
    }

    /**
     * Five per cent of the estimate <em>or</em> the contract, whichever is higher. The whole
     * point of the clause: a contractor who bids thirty per cent below still finds the
     * guarantee on the full estimate, and computing it off the quoted amount would understate
     * every deep bid — which is exactly the bid that is short of working capital.
     */
    private static BigDecimal guaranteeBase(ContractFacts facts) {
        BigDecimal estimate = facts.tenderEstimate();
        BigDecimal contract = facts.acceptedBid();
        if (estimate == null) {
            return contract;
        }
        if (contract == null) {
            return estimate;
        }
        return estimate.max(contract);
    }

    private static String describeGuaranteeBase(ContractFacts facts) {
        BigDecimal estimate = facts.tenderEstimate();
        BigDecimal contract = facts.acceptedBid();
        if (estimate == null) {
            return "the contract amount";
        }
        if (contract == null || estimate.compareTo(contract) >= 0) {
            return "the estimated cost put to tender, which is the higher of the two";
        }
        return "the contract amount, which is the higher of the two";
    }

    /**
     * The guarantee is released a year after a construction contract completes and six months
     * after the department's completion letter on a maintenance one.
     *
     * <p>Null where the work nature has not been recorded, and not the construction rule by
     * default — six months of a wrong answer is six months the office is not chasing an FDR
     * it could have had back.</p>
     */
    private static LocalDate guaranteeReleaseDate(ContractFacts facts) {
        if (facts.completionOn() == null || facts.workNature() == null) {
            return null;
        }
        int months = facts.workNature() == WorkNature.MAINTENANCE
                ? MAINTENANCE_GUARANTEE_MONTHS : CONSTRUCTION_GUARANTEE_MONTHS;
        return facts.completionOn().plusMonths(months);
    }

    /**
     * The extra guarantee a low bid triggers, or nothing at all.
     *
     * <p>{@code DIFFERENCE} is the CPWD form's arithmetic — the threshold share of the estimate
     * less what was actually bid, so a bid at 70% of a one-crore estimate adds ten lakh on top
     * of the five lakh guarantee. {@code PERCENT_OF_BID} is what some other departments levy
     * instead. A notice carrying no clause at all produces no row.</p>
     */
    private static java.util.Optional<Proposal> additionalGuarantee(ContractFacts facts) {
        NoticeTerms notice = notice(facts);
        BigDecimal estimate = facts.tenderEstimate();
        BigDecimal contract = facts.acceptedBid();
        if (estimate == null || contract == null || estimate.signum() == 0) {
            return java.util.Optional.empty();
        }

        BigDecimal threshold = notice.apgThresholdPercent() != null
                ? notice.apgThresholdPercent() : DEFAULT_APG_THRESHOLD_PERCENT;
        BigDecimal thresholdAmount = percentOf(threshold, estimate);
        if (contract.compareTo(thresholdAmount) >= 0) {
            return java.util.Optional.empty();
        }

        String method = notice.apgMethod() == null ? "DIFFERENCE" : notice.apgMethod();
        BigDecimal amount;
        String basis;
        if ("PERCENT_OF_BID".equals(method)) {
            BigDecimal percent = notice.apgPercent() == null ? BigDecimal.ZERO : notice.apgPercent();
            amount = percentOf(percent, contract);
            basis = percent.stripTrailingZeros().toPlainString()
                    + "% of the tendered amount, the bid being below "
                    + threshold.stripTrailingZeros().toPlainString()
                    + "% of the estimate, as stated in the notice.";
        } else {
            amount = round(thresholdAmount.subtract(contract));
            basis = threshold.stripTrailingZeros().toPlainString()
                    + "% of the estimated cost less the tendered amount"
                    + (notice.apgThresholdPercent() != null
                            ? ", as stated in the notice."
                            : " (standard form; the notice was not read for this contract).");
        }
        if (amount.signum() <= 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new Proposal(Type.ADDITIONAL_PG, Instrument.FDR, amount,
                basis, guaranteeReleaseDate(facts)));
    }

    /**
     * The retention: withheld from bills rather than lodged, which is why it is proposed as a
     * {@code BILL_RETENTION} and never as an FDR. It is the full figure the department will
     * eventually be holding; how much of it exists today is a running total the office updates
     * as each bill is passed.
     */
    private static Proposal securityDeposit(ContractFacts facts) {
        NoticeTerms notice = notice(facts);
        BigDecimal percent = notice.securityDepositPercent() != null
                ? notice.securityDepositPercent() : DEFAULT_SD_PERCENT;
        boolean fromNotice = notice.securityDepositPercent() != null;

        BigDecimal contract = facts.acceptedBid();
        BigDecimal amount = contract == null ? BigDecimal.ZERO : percentOf(percent, contract);
        String basis = contract == null
                ? "No contract value recorded, so the deduction cannot be worked out."
                : percent.stripTrailingZeros().toPlainString() + "% of the tendered amount"
                        + (fromNotice ? ", as stated in the notice."
                                      : " (standard form; the notice was not read for this "
                                        + "contract).");
        return new Proposal(Type.SECURITY_DEPOSIT, Instrument.BILL_RETENTION, amount, basis,
                depositReleaseDate(facts));
    }

    /**
     * Released after the defect liability period, which varies with the item and the work — so
     * it is read off the contract and never assumed. No period recorded, no date proposed.
     */
    private static LocalDate depositReleaseDate(ContractFacts facts) {
        if (facts.completionOn() == null || facts.defectLiabilityMonths() == null) {
            return null;
        }
        return facts.completionOn().plusMonths(facts.defectLiabilityMonths());
    }

    // ------------------------------------------------------------------ arithmetic

    private static NoticeTerms notice(ContractFacts facts) {
        return facts.notice() == null ? NoticeTerms.NONE : facts.notice();
    }

    private static BigDecimal percentOf(BigDecimal percent, BigDecimal base) {
        return round(base.multiply(percent).divide(HUNDRED, 6, RoundingMode.HALF_UP));
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}

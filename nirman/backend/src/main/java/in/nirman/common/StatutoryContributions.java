package in.nirman.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What the statutes take out of a month's pay, and what the employer pays on top of it.
 *
 * <p><b>In {@code common/} rather than in {@code payroll/}, and not because three modules use
 * it.</b> Two do — payroll works a month out with it, and the offer letter states on its
 * annexure what will come out of the packet it is offering. The obvious alternative, a
 * {@code PayrollLookup} that identity asks, is the one thing that cannot be built here:
 * payroll already depends on identity through {@link
 * in.nirman.modules.identity.service.StaffPayrollLookup}, so pointing identity back at payroll
 * would close exactly the module cycle the boundaries exist to prevent. And the content
 * settles it independently — these are rates fixed by national law, identical for every
 * organisation and owned by no module's business logic, which is what {@code common/} is
 * for.</p>
 *
 * <p>Pure arithmetic — no Spring, no repository, no clock. Everything a payslip's provident
 * fund and insurance lines depend on is an argument to {@link #of}, which is what makes the
 * rules testable against a printed slip from a real office rather than only against the
 * system that produced them.</p>
 *
 * <h2>The rates, and where they came from</h2>
 *
 * <p>Checked against the position as at August 2026, after the four labour codes commenced on
 * 21 November 2025:</p>
 *
 * <ul>
 *   <li><b>Provident fund</b> — 12% from the member, matched by 12% from the employer, of
 *       which 8.33% goes to the pension scheme and is capped at the ceiling (so never more
 *       than ₹1,250 a month). Statutory wage ceiling ₹15,000, unchanged.</li>
 *   <li><b>State insurance</b> — 0.75% from the member and 3.25% from the employer, on gross
 *       wages, where the member is covered. Coverage ceiling ₹21,000 a month.</li>
 *   <li><b>The wage definition</b> — the Code on Wages counts basic, dearness allowance and
 *       retaining allowance as "wages", and then adds back the excess where the excluded
 *       allowances have been let run past half of the whole remuneration.</li>
 * </ul>
 *
 * <p>These are national law and identical for every organisation on the system, so they are
 * constants here rather than columns anywhere. A rate in a table is a rate somebody can type
 * wrong, and a payslip carrying the wrong provident fund rate looks exactly like a correct
 * one — it is the single mistake in this feature that would never be noticed from the
 * document. When a notification moves one of them, it moves <em>here</em>, and every slip
 * already issued keeps the figures it was drawn with, because {@code payslips} stores them.</p>
 *
 * <h2>Two judgment calls, stated rather than buried</h2>
 *
 * <p><b>The fifty-per-cent test is applied to the fixed structure, and overtime is left out
 * of it.</b> The proviso to the wage definition sweeps the excluded payments together and
 * asks whether they exceed half of all remuneration; overtime allowance is one of the
 * excluded payments, so read at its widest the test would answer differently in a month
 * somebody worked Sundays. That would make a member's provident fund wage rise and fall with
 * his overtime, which is not what a definition of the wage he was engaged on can sensibly
 * mean, and no payroll office in the country works it that way. So the test is run on what
 * he is paid every month regardless, and the overtime sits outside it.</p>
 *
 * <p><b>The ceilings are prorated for a part month.</b> A member who loses eight days of a
 * thirty-day month has earned eight-thirtieths less of everything, and holding his provident
 * fund wage at the full ₹15,000 would have the fund taking a larger share of a smaller
 * packet. Prorating is the ordinary practice and the one that keeps the deduction a constant
 * proportion of what he was actually paid.</p>
 */
public final class StatutoryContributions {

    // ------------------------------------------------------------------ the rates

    /** Employees' Provident Fund, member and employer alike. */
    public static final BigDecimal PF_RATE = new BigDecimal("0.12");

    /** The employer's 12% is split; this much of it goes to the pension scheme. */
    public static final BigDecimal EPS_RATE = new BigDecimal("0.0833");

    /** The monthly wage ceiling the fund is computed on unless the member is on full wages. */
    public static final BigDecimal PF_CEILING = new BigDecimal("15000");

    public static final BigDecimal ESI_EMPLOYEE_RATE = new BigDecimal("0.0075");
    public static final BigDecimal ESI_EMPLOYER_RATE = new BigDecimal("0.0325");

    /** Above this monthly wage a member is outside the insurance scheme. */
    public static final BigDecimal ESI_CEILING = new BigDecimal("21000");

    /**
     * The share of the whole remuneration that must be treated as wages however the
     * structure is written. The Code on Wages proviso, stated as the fraction it comes to.
     */
    public static final BigDecimal WAGE_FLOOR_SHARE = new BigDecimal("0.50");

    /** A day of eight hours, the divisor the overtime rate is built on. */
    private static final BigDecimal SHIFT_HOURS = new BigDecimal("8");

    /** Overtime is at twice the ordinary rate — Code on Wages, section 14. */
    private static final BigDecimal OVERTIME_MULTIPLE = new BigDecimal("2");

    private StatutoryContributions() {
    }

    /**
     * What a month's earnings attract.
     *
     * @param earnedIncluded   basic and dearness allowance as actually earned this month —
     *                         the part of the packet the wage definition counts outright
     * @param earnedExcluded   house rent, conveyance and the rest as actually earned. Not
     *                         overtime: see the class note on why the test leaves it out
     * @param overtimeAmount   paid for hours beyond the shift. Outside the wage test and
     *                         outside the provident fund, inside the insurance wage
     * @param structureGross   the full month's agreed gross, which is what the insurance
     *                         coverage ceiling is tested against. Tested on the structure and
     *                         not on the earnings so that a man does not fall into the scheme
     *                         in a month he was absent and out of it in a month he was not
     * @param daysFactor       paid days over payable days, between zero and one
     * @param pfApplicable     whether the fund reaches this member at all
     * @param esiApplicable    whether he is enrolled in the insurance scheme. A stored
     *                         decision, because coverage runs for a whole contribution period
     *                         and does not lapse mid-period on a raise
     * @param pfOnFullWages    contribute on the whole wage rather than on the ceiling
     */
    public static Result of(BigDecimal earnedIncluded, BigDecimal earnedExcluded,
                            BigDecimal overtimeAmount, BigDecimal structureGross,
                            BigDecimal daysFactor, boolean pfApplicable, boolean esiApplicable,
                            boolean pfOnFullWages) {
        BigDecimal included = money(earnedIncluded);
        BigDecimal excluded = money(earnedExcluded);
        BigDecimal overtime = money(overtimeAmount);

        // The wage the statutes work on: what the definition includes, lifted to half of the
        // whole where the allowances have been let run past that. Written as a maximum
        // because it is the same arithmetic as "add back the excess" and does not need the
        // reader to hold two subtractions in his head.
        BigDecimal remuneration = included.add(excluded);
        BigDecimal statutoryWages = included.max(
                money(remuneration.multiply(WAGE_FLOOR_SHARE)));

        // The ceilings, cut to the part of the month that was actually worked.
        BigDecimal proratedCeiling = money(PF_CEILING.multiply(clampFactor(daysFactor)));

        BigDecimal pfWages = BigDecimal.ZERO;
        BigDecimal pfEmployee = BigDecimal.ZERO;
        BigDecimal pfEmployer = BigDecimal.ZERO;
        BigDecimal epsEmployer = BigDecimal.ZERO;
        if (pfApplicable) {
            pfWages = pfOnFullWages ? statutoryWages : statutoryWages.min(proratedCeiling);
            pfEmployee = toRupees(pfWages.multiply(PF_RATE));
            // The pension share is always on the ceiling even where the fund is on full
            // wages: the scheme itself is capped, and an employer paying provident fund on
            // ₹40,000 is not thereby paying pension on ₹40,000.
            epsEmployer = toRupees(pfWages.min(proratedCeiling).multiply(EPS_RATE));
            // The employer's total is 12% and the pension comes out of it, not on top. Taking
            // the difference rather than computing 3.67% separately is what stops the two
            // halves failing to add to the twelve per cent that was actually remitted.
            pfEmployer = toRupees(pfWages.multiply(PF_RATE)).subtract(epsEmployer);
        }

        BigDecimal esiWages = BigDecimal.ZERO;
        BigDecimal esiEmployee = BigDecimal.ZERO;
        BigDecimal esiEmployer = BigDecimal.ZERO;
        // Coverage on the structure, contribution on everything actually paid — including the
        // overtime. That asymmetry is the scheme's own rule and not an oversight: overtime is
        // ignored when deciding whether a man is in, and counted once he is.
        if (esiApplicable && money(structureGross).compareTo(ESI_CEILING) <= 0) {
            esiWages = remuneration.add(overtime);
            esiEmployee = roundedUpToRupee(esiWages.multiply(ESI_EMPLOYEE_RATE));
            esiEmployer = roundedUpToRupee(esiWages.multiply(ESI_EMPLOYER_RATE));
        }

        return new Result(statutoryWages, pfWages, pfEmployee, pfEmployer, epsEmployer,
                esiWages, esiEmployee, esiEmployer);
    }

    /**
     * What the hours come to at the statutory rate: twice the ordinary rate of wages.
     *
     * <p>The ordinary rate is the month's wage over twenty-six days of eight hours, which is
     * the divisor the wage rules use and the one every muster in the country is written
     * against. It is offered rather than imposed — an office that has agreed a different
     * overtime rate types the amount and the hours still print beside it — but a blank box
     * where the law states a formula is a box that gets filled in from memory.</p>
     *
     * @param monthlyStatutoryWages the full month's wage as the definition counts it, not the
     *                              gross: overtime is paid on wages, and paying it on a
     *                              packet padded with allowances would pay twice for the
     *                              padding
     */
    public static BigDecimal overtimeFor(BigDecimal monthlyStatutoryWages, BigDecimal hours,
                                         int monthlyWageDays) {
        if (hours == null || hours.signum() <= 0 || monthlyStatutoryWages == null
                || monthlyWageDays <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal hourly = monthlyStatutoryWages.divide(
                BigDecimal.valueOf(monthlyWageDays).multiply(SHIFT_HOURS), 6, RoundingMode.HALF_UP);
        return money(hourly.multiply(OVERTIME_MULTIPLE).multiply(hours));
    }

    /**
     * The month's figures, every one of them a rupee amount the payslip stores.
     *
     * @param statutoryWages what the definition makes of this month's packet — the basis both
     *                       the fund and, on exit, the gratuity are worked out on
     * @param pfWages        the part of it the fund was actually charged on, after the ceiling
     * @param epsEmployer    the pension share, which comes out of the employer's twelve per
     *                       cent rather than sitting on top of it
     */
    public record Result(
            BigDecimal statutoryWages,
            BigDecimal pfWages,
            BigDecimal pfEmployee,
            BigDecimal pfEmployer,
            BigDecimal epsEmployer,
            BigDecimal esiWages,
            BigDecimal esiEmployee,
            BigDecimal esiEmployer) {
    }

    // ------------------------------------------------------------------ rounding

    /** Rupees and paise, half up. The rounding a person doing this on paper would use. */
    public static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    /** The fund is remitted in whole rupees. */
    private static BigDecimal toRupees(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * The insurance is remitted in whole rupees rounded <em>up</em> — the scheme's own rule,
     * and the reason a slip showing ₹136 against ₹18,133 of wages is right and ₹135 is not.
     */
    private static BigDecimal roundedUpToRupee(BigDecimal value) {
        return value.setScale(0, RoundingMode.CEILING).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal clampFactor(BigDecimal factor) {
        if (factor == null || factor.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return factor.min(BigDecimal.ONE);
    }
}

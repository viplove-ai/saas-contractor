package in.nirman.modules.payroll;

import in.nirman.modules.payroll.domain.StatutoryContributions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The statutory arithmetic, checked against payslips rather than against itself.
 *
 * <p>These are the figures a payroll clerk would arrive at with a calculator, and that is the
 * only useful test of them: a rule that agrees with the code that implements it has proved
 * nothing. The first case is taken from a real payslip — basic ₹15,000, other allowances
 * ₹3,000, gross ₹18,000, insurance ₹135, provident fund ₹1,800 — and every other case here
 * exists because it is one somebody would get wrong by hand.</p>
 */
class StatutoryContributionsTest {

    private static final BigDecimal FULL_MONTH = BigDecimal.ONE;

    @Nested
    @DisplayName("a full month")
    class FullMonth {

        @Test
        @DisplayName("agrees with the printed payslip: 18,000 gross, 135 insurance, 1,800 fund")
        void matchesARealPayslip() {
            // Basic 15,000 and other allowances 3,000. Wages by the definition are 15,000;
            // half the packet is 9,000, so the fifty-per-cent rule does not bite.
            var result = StatutoryContributions.of(
                    new BigDecimal("15000"), new BigDecimal("3000"), BigDecimal.ZERO,
                    new BigDecimal("18000"), FULL_MONTH, true, true, false);

            assertThat(result.statutoryWages()).isEqualByComparingTo("15000.00");
            // The fund on the ceiling, which the basic happens to sit exactly on.
            assertThat(result.pfEmployee()).isEqualByComparingTo("1800.00");
            // The insurance on the whole gross, not on the wage the fund uses.
            assertThat(result.esiWages()).isEqualByComparingTo("18000.00");
            assertThat(result.esiEmployee()).isEqualByComparingTo("135.00");
            assertThat(result.esiEmployer()).isEqualByComparingTo("585.00");
        }

        /**
         * The rule the whole feature turns on. An office writing 40% basic and the rest in
         * allowances does not thereby cut the fund: the excess over half the packet counts as
         * wages anyway.
         */
        @Test
        @DisplayName("allowances past half the packet are added back to the wage")
        void theFiftyPerCentRuleLiftsTheWage() {
            // Basic 8,000 of a 20,000 packet. Basic and dearness allowance come to 8,000; half
            // the packet is 10,000, and the wage is the larger of the two.
            var result = StatutoryContributions.of(
                    new BigDecimal("8000"), new BigDecimal("12000"), BigDecimal.ZERO,
                    new BigDecimal("20000"), FULL_MONTH, true, false, true);

            assertThat(result.statutoryWages()).isEqualByComparingTo("10000.00");
            // On full wages, so 12% of the lifted figure and not of the basic that was written.
            assertThat(result.pfEmployee()).isEqualByComparingTo("1200.00");
        }

        @Test
        @DisplayName("the pension share is capped at the ceiling even on full wages")
        void thePensionShareIsAlwaysCapped() {
            var result = StatutoryContributions.of(
                    new BigDecimal("40000"), BigDecimal.ZERO, BigDecimal.ZERO,
                    new BigDecimal("40000"), FULL_MONTH, true, false, true);

            // 8.33% of the ₹15,000 ceiling, which is the scheme's own maximum.
            assertThat(result.epsEmployer()).isEqualByComparingTo("1250.00");
            // The employer's total is 12% of 40,000, and the pension comes out of it.
            assertThat(result.pfEmployer().add(result.epsEmployer()))
                    .isEqualByComparingTo("4800.00");
        }

        @Test
        @DisplayName("the fund is charged on the ceiling unless the member is on full wages")
        void theCeilingHoldsByDefault() {
            var restricted = StatutoryContributions.of(
                    new BigDecimal("40000"), BigDecimal.ZERO, BigDecimal.ZERO,
                    new BigDecimal("40000"), FULL_MONTH, true, false, false);

            assertThat(restricted.pfWages()).isEqualByComparingTo("15000.00");
            assertThat(restricted.pfEmployee()).isEqualByComparingTo("1800.00");
        }

        /**
         * Coverage is decided on the structure and the contribution is charged on everything
         * paid. Overtime is therefore ignored on the way in and counted once inside — the
         * scheme's own asymmetry, and the one somebody implementing this from memory inverts.
         */
        @Test
        @DisplayName("overtime is outside the insurance ceiling test and inside the contribution")
        void overtimeIsOutsideTheTestAndInsideTheCharge() {
            var result = StatutoryContributions.of(
                    new BigDecimal("15000"), new BigDecimal("5000"), new BigDecimal("4000"),
                    new BigDecimal("20000"), FULL_MONTH, false, true, false);

            // 20,000 of structure is under the ceiling, so he is covered although the packet
            // with overtime comes to 24,000.
            assertThat(result.esiWages()).isEqualByComparingTo("24000.00");
            assertThat(result.esiEmployee()).isEqualByComparingTo("180.00");
        }

        @Test
        @DisplayName("a structure above the ceiling is outside the insurance scheme entirely")
        void aboveTheCeilingThereIsNoInsurance() {
            var result = StatutoryContributions.of(
                    new BigDecimal("22000"), BigDecimal.ZERO, BigDecimal.ZERO,
                    new BigDecimal("22000"), FULL_MONTH, true, true, false);

            assertThat(result.esiWages()).isEqualByComparingTo("0.00");
            assertThat(result.esiEmployee()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("the insurance is rounded up to the rupee, the fund to the nearest")
        void theTwoSchemesRoundDifferently() {
            // 0.75% of 18,133 is 135.9975 — the scheme rounds it up to 136, not down to 135.
            var result = StatutoryContributions.of(
                    new BigDecimal("15133"), new BigDecimal("3000"), BigDecimal.ZERO,
                    new BigDecimal("18133"), FULL_MONTH, true, true, false);

            assertThat(result.esiEmployee()).isEqualByComparingTo("136.00");
            // 12% of the 15,000 ceiling: exactly 1,800, and the wage above it is ignored.
            assertThat(result.pfEmployee()).isEqualByComparingTo("1800.00");
        }

        @Test
        @DisplayName("a member outside both schemes has nothing deducted")
        void neitherSchemeApplies() {
            var result = StatutoryContributions.of(
                    new BigDecimal("15000"), new BigDecimal("3000"), BigDecimal.ZERO,
                    new BigDecimal("18000"), FULL_MONTH, false, false, false);

            assertThat(result.pfEmployee()).isEqualByComparingTo("0.00");
            assertThat(result.esiEmployee()).isEqualByComparingTo("0.00");
            // The wage is still worked out: it is what gratuity will be computed on.
            assertThat(result.statutoryWages()).isEqualByComparingTo("15000.00");
        }
    }

    @Nested
    @DisplayName("a part month")
    class PartMonth {

        /**
         * The ceiling is cut with the month. Holding it at ₹15,000 for somebody who earned
         * half of it would have the fund taking a larger share of a smaller packet.
         */
        @Test
        @DisplayName("the fund ceiling is prorated with the days")
        void theCeilingIsProrated() {
            // Half a month on a structure of 40,000: earnings of 20,000, ceiling of 7,500.
            var result = StatutoryContributions.of(
                    new BigDecimal("20000"), BigDecimal.ZERO, BigDecimal.ZERO,
                    new BigDecimal("40000"), new BigDecimal("0.5"), true, false, false);

            assertThat(result.pfWages()).isEqualByComparingTo("7500.00");
            assertThat(result.pfEmployee()).isEqualByComparingTo("900.00");
        }
    }

    @Nested
    @DisplayName("overtime")
    class Overtime {

        @Test
        @DisplayName("is twice the ordinary rate, on a twenty-six day month of eight hours")
        void twiceTheOrdinaryRate() {
            // 15,600 over 26 days of 8 hours is 75 an hour; twice that is 150; four hours 600.
            assertThat(StatutoryContributions.overtimeFor(new BigDecimal("15600"),
                    new BigDecimal("4"), 26)).isEqualByComparingTo("600.00");
        }

        @Test
        @DisplayName("no hours is no money, and not a division by anything")
        void noHours() {
            assertThat(StatutoryContributions.overtimeFor(new BigDecimal("15600"),
                    BigDecimal.ZERO, 26)).isEqualByComparingTo("0.00");
            assertThat(StatutoryContributions.overtimeFor(new BigDecimal("15600"), null, 26))
                    .isEqualByComparingTo("0.00");
        }
    }
}

package in.nirman.modules.labour;

import in.nirman.modules.labour.domain.AttendanceCalculator;
import in.nirman.modules.labour.domain.AttendanceCalculator.Input;
import in.nirman.modules.labour.domain.AttendanceCalculator.Result;
import in.nirman.modules.labour.domain.AttendanceStatus;
import in.nirman.modules.labour.domain.WageType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The wage rules, pinned. These numbers come from the Kausani workbooks reviewed in
 * docs/09-design-decisions.md: a seven-hour standard shift, ₹625 a day, and an overtime
 * rate of ₹625 ÷ 7 with no premium on top.
 */
class AttendanceCalculatorTest {

    private static final BigDecimal SHIFT_7H = new BigDecimal("7.00");
    private static final BigDecimal SHIFT_8H = new BigDecimal("8.00");
    private static final BigDecimal DAILY_625 = new BigDecimal("625.0000");
    /** ₹625 ÷ 7 hours. The field sheet rounds this to ₹89.00 when it prints. */
    private static final BigDecimal OT_RATE = new BigDecimal("89.2857");

    /** A present day for a ₹625 daily worker on a seven-hour Kausani site, unless overridden. */
    private static InputBuilder present() {
        return new InputBuilder();
    }

    private static final class InputBuilder {
        private AttendanceStatus status = AttendanceStatus.PRESENT;
        private LocalTime checkIn;
        private LocalTime checkOut;
        private int breakMinutes;
        private BigDecimal enteredHours;
        private BigDecimal standardShiftHours = SHIFT_7H;
        private WageType wageType = WageType.DAILY;
        private BigDecimal normalRate = DAILY_625;
        private BigDecimal overtimeRate = OT_RATE;
        private int monthlyWageDays = 26;

        InputBuilder status(AttendanceStatus value) {
            this.status = value;
            return this;
        }

        InputBuilder times(LocalTime in, LocalTime out) {
            this.checkIn = in;
            this.checkOut = out;
            return this;
        }

        InputBuilder breakMinutes(int value) {
            this.breakMinutes = value;
            return this;
        }

        InputBuilder enteredHours(String value) {
            this.enteredHours = new BigDecimal(value);
            return this;
        }

        InputBuilder standardShiftHours(BigDecimal value) {
            this.standardShiftHours = value;
            return this;
        }

        InputBuilder wage(WageType type, BigDecimal normal, BigDecimal overtime) {
            this.wageType = type;
            this.normalRate = normal;
            this.overtimeRate = overtime;
            return this;
        }

        InputBuilder monthlyWageDays(int value) {
            this.monthlyWageDays = value;
            return this;
        }

        Input build() {
            return new Input(status, checkIn, checkOut, breakMinutes, enteredHours,
                    standardShiftHours, wageType, normalRate, overtimeRate, monthlyWageDays);
        }
    }

    // ------------------------------------------------------------------ the simple day

    @Test
    @DisplayName("marked present with no clock readings is exactly one standard shift")
    void presentWithoutTimes() {
        Result result = AttendanceCalculator.calculate(present().build());

        assertThat(result.workedHours()).isEqualByComparingTo("7.00");
        assertThat(result.regularHours()).isEqualByComparingTo("7.00");
        assertThat(result.overtimeHours()).isEqualByComparingTo("0.00");
        assertThat(result.wageAmount()).isEqualByComparingTo("625.00");
        assertThat(result.overtimeAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a worker who leaves early still earns the full day rate, and no overtime")
    void shortDayStillPaysTheDayRate() {
        Result result = AttendanceCalculator.calculate(
                present().times(LocalTime.of(9, 0), LocalTime.of(13, 0)).build());

        assertThat(result.workedHours()).isEqualByComparingTo("4.00");
        assertThat(result.overtimeHours()).isEqualByComparingTo("0.00");
        // Daily wage means daily: the rate is not prorated by hours worked.
        assertThat(result.wageAmount()).isEqualByComparingTo("625.00");
    }

    @Test
    @DisplayName("the unpaid break comes off worked hours")
    void breakIsDeducted() {
        Result result = AttendanceCalculator.calculate(
                present().times(LocalTime.of(9, 0), LocalTime.of(17, 0)).breakMinutes(30).build());

        assertThat(result.workedHours()).isEqualByComparingTo("7.50");
        assertThat(result.regularHours()).isEqualByComparingTo("7.00");
        assertThat(result.overtimeHours()).isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("a break longer than the shift floors worked hours at zero, never negative")
    void absurdBreakDoesNotGoNegative() {
        Result result = AttendanceCalculator.calculate(
                present().times(LocalTime.of(9, 0), LocalTime.of(11, 0)).breakMinutes(600).build());

        assertThat(result.workedHours()).isEqualByComparingTo("0.00");
        assertThat(result.overtimeHours()).isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------ overtime

    @Test
    @DisplayName("overtime starts after the site's shift, not after eight hours")
    void overtimeStartsAfterTheSiteShift() {
        Input tenHours = present().times(LocalTime.of(8, 0), LocalTime.of(18, 0)).build();

        Result atSeven = AttendanceCalculator.calculate(tenHours);
        assertThat(atSeven.overtimeHours()).isEqualByComparingTo("3.00");

        Result atEight = AttendanceCalculator.calculate(
                present().times(LocalTime.of(8, 0), LocalTime.of(18, 0))
                        .standardShiftHours(SHIFT_8H).build());
        assertThat(atEight.overtimeHours())
                .as("an eight-hour site books one hour less overtime for the same day")
                .isEqualByComparingTo("2.00");
    }

    @Test
    @DisplayName("overtime pays the stored rate with no 1.5x premium invented")
    void overtimeCarriesNoPremium() {
        Result result = AttendanceCalculator.calculate(
                present().times(LocalTime.of(8, 0), LocalTime.of(18, 0)).build());

        assertThat(result.overtimeHours()).isEqualByComparingTo("3.00");
        // 3 x 89.2857 = 267.8571, and nothing multiplies it further.
        assertThat(result.overtimeAmount()).isEqualByComparingTo("267.86");
        assertThat(result.totalAmount()).isEqualByComparingTo("892.86");
    }

    /**
     * The invariant behind the field sheet's arithmetic. Because overtime carries no
     * premium, an hour is worth the same whether it lands inside the shift or outside it —
     * which is exactly why ₹17,321 could be computed as (194 hours ÷ 7) × ₹625 without ever
     * separating regular hours from overtime.
     */
    @Test
    @DisplayName("fourteen hours in one day pays the same as two seven-hour days")
    void anHourIsWorthTheSameWhereverItFalls() {
        BigDecimal twoNormalDays = AttendanceCalculator.calculate(present().build()).totalAmount()
                .add(AttendanceCalculator.calculate(present().build()).totalAmount());

        BigDecimal oneDoubleDay = AttendanceCalculator.calculate(
                present().times(LocalTime.of(6, 0), LocalTime.of(20, 0)).build()).totalAmount();

        assertThat(oneDoubleDay).isEqualByComparingTo(twoNormalDays);
        assertThat(oneDoubleDay).isEqualByComparingTo("1250.00");
    }

    // ------------------------------------------------------------------ night shift

    @Nested
    @DisplayName("a shift crossing midnight")
    class NightShift {

        @Test
        @DisplayName("rolls over when check-out reads earlier than check-in")
        void rollsOver() {
            Result result = AttendanceCalculator.calculate(
                    present().times(LocalTime.of(22, 0), LocalTime.of(6, 0)).build());

            assertThat(result.workedHours())
                    .as("22:00 to 06:00 is eight hours, not minus sixteen")
                    .isEqualByComparingTo("8.00");
            assertThat(result.regularHours()).isEqualByComparingTo("7.00");
            assertThat(result.overtimeHours()).isEqualByComparingTo("1.00");
        }

        @Test
        @DisplayName("still deducts the break")
        void deductsBreak() {
            Result result = AttendanceCalculator.calculate(
                    present().times(LocalTime.of(20, 30), LocalTime.of(5, 30))
                            .breakMinutes(45).build());

            assertThat(result.workedHours()).isEqualByComparingTo("8.25");
            assertThat(result.overtimeHours()).isEqualByComparingTo("1.25");
        }

        @Test
        @DisplayName("identical times mean a full round-the-clock shift, not a zero one")
        void identicalTimesMeanTwentyFourHours() {
            Result result = AttendanceCalculator.calculate(
                    present().times(LocalTime.of(9, 0), LocalTime.of(9, 0)).build());

            assertThat(result.workedHours()).isEqualByComparingTo("24.00");
            assertThat(result.overtimeHours()).isEqualByComparingTo("17.00");
        }
    }

    // ------------------------------------------------------------------ half day

    @Nested
    @DisplayName("a half day")
    class HalfDay {

        @Test
        @DisplayName("pays half the day rate and half a shift without clock readings")
        void paysHalf() {
            Result result = AttendanceCalculator.calculate(
                    present().status(AttendanceStatus.HALF_DAY).build());

            assertThat(result.workedHours()).isEqualByComparingTo("3.50");
            assertThat(result.wageAmount()).isEqualByComparingTo("312.50");
            assertThat(result.overtimeHours()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("books no overtime however long the clock says the worker stayed")
        void neverBooksOvertime() {
            Result result = AttendanceCalculator.calculate(
                    present().status(AttendanceStatus.HALF_DAY)
                            .times(LocalTime.of(6, 0), LocalTime.of(20, 0)).build());

            assertThat(result.workedHours()).isEqualByComparingTo("14.00");
            assertThat(result.overtimeHours())
                    .as("the status decides, not the clock")
                    .isEqualByComparingTo("0.00");
            assertThat(result.overtimeAmount()).isEqualByComparingTo("0.00");
            assertThat(result.wageAmount()).isEqualByComparingTo("312.50");
        }
    }

    // ------------------------------------------------------------------ unpaid statuses

    @ParameterizedTest
    @EnumSource(value = AttendanceStatus.class, names = {"ABSENT", "LEAVE"})
    @DisplayName("absent and leave earn nothing, even with times recorded")
    void unpaidStatusesEarnNothing(AttendanceStatus status) {
        Result result = AttendanceCalculator.calculate(
                present().status(status).times(LocalTime.of(8, 0), LocalTime.of(18, 0)).build());

        assertThat(result.workedHours()).isEqualByComparingTo("0.00");
        assertThat(result.regularHours()).isEqualByComparingTo("0.00");
        assertThat(result.overtimeHours()).isEqualByComparingTo("0.00");
        assertThat(result.wageAmount()).isEqualByComparingTo("0.00");
        assertThat(result.totalAmount()).isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------ wage types

    @Test
    @DisplayName("an hourly worker is paid by the clock, and overtime hours at the overtime rate")
    void hourlyWorker() {
        Result result = AttendanceCalculator.calculate(
                present().wage(WageType.HOURLY, new BigDecimal("80.0000"), OT_RATE)
                        .times(LocalTime.of(8, 0), LocalTime.of(18, 0)).build());

        assertThat(result.regularHours()).isEqualByComparingTo("7.00");
        assertThat(result.wageAmount()).isEqualByComparingTo("560.00");   // 7 x 80
        assertThat(result.overtimeAmount()).isEqualByComparingTo("267.86"); // 3 x 89.2857
    }

    @Test
    @DisplayName("an hourly half day is paid for the hours worked, not half a day rate")
    void hourlyHalfDay() {
        Result result = AttendanceCalculator.calculate(
                present().status(AttendanceStatus.HALF_DAY)
                        .wage(WageType.HOURLY, new BigDecimal("80.0000"), OT_RATE)
                        .times(LocalTime.of(9, 0), LocalTime.of(12, 0)).build());

        assertThat(result.wageAmount()).isEqualByComparingTo("240.00");   // 3 x 80
    }

    @Test
    @DisplayName("a monthly wage is divided by the site's wage days to cost a single day")
    void monthlyWorker() {
        Result result = AttendanceCalculator.calculate(
                present().wage(WageType.MONTHLY, new BigDecimal("26000.0000"), OT_RATE).build());

        assertThat(result.wageAmount())
                .as("26000 over 26 wage days is 1000 a day")
                .isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("a monthly half day is half the derived daily rate")
    void monthlyHalfDay() {
        Result result = AttendanceCalculator.calculate(
                present().status(AttendanceStatus.HALF_DAY)
                        .wage(WageType.MONTHLY, new BigDecimal("26000.0000"), OT_RATE).build());

        assertThat(result.wageAmount()).isEqualByComparingTo("500.00");
    }

    // ------------------------------------------------------------------ typed hours

    @Test
    @DisplayName("nine typed hours on a seven-hour site is seven regular and two overtime")
    void typedHoursSplitAtTheShiftLength() {
        Result result = AttendanceCalculator.calculate(present().enteredHours("9").build());

        assertThat(result.workedHours()).isEqualByComparingTo("9.00");
        assertThat(result.regularHours()).isEqualByComparingTo("7.00");
        assertThat(result.overtimeHours()).isEqualByComparingTo("2.00");
        // A daily worker earns the full day plus the two hours at the overtime rate.
        assertThat(result.wageAmount()).isEqualByComparingTo("625.00");
        assertThat(result.overtimeAmount()).isEqualByComparingTo("178.57");
    }

    @Test
    @DisplayName("typed hours override the clock rather than being reconciled with it")
    void typedHoursBeatTheClock() {
        Result result = AttendanceCalculator.calculate(present()
                .times(LocalTime.of(9, 0), LocalTime.of(17, 0))
                .enteredHours("9")
                .build());

        assertThat(result.workedHours()).isEqualByComparingTo("9.00");
        assertThat(result.overtimeHours()).isEqualByComparingTo("2.00");
    }

    @Test
    @DisplayName("a break is not deducted from typed hours, which already have lunch out of them")
    void typedHoursIgnoreTheBreak() {
        Result result = AttendanceCalculator.calculate(present()
                .enteredHours("9")
                .breakMinutes(60)
                .build());

        assertThat(result.workedHours()).isEqualByComparingTo("9.00");
        assertThat(result.overtimeHours()).isEqualByComparingTo("2.00");
    }

    @Test
    @DisplayName("typed hours under the shift length book no overtime and no negative regular")
    void typedHoursBelowTheShift() {
        Result result = AttendanceCalculator.calculate(present().enteredHours("4").build());

        assertThat(result.workedHours()).isEqualByComparingTo("4.00");
        assertThat(result.regularHours()).isEqualByComparingTo("4.00");
        assertThat(result.overtimeHours()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("typed hours on an unpaid day earn nothing, whatever the number says")
    void typedHoursOnAnUnpaidDay() {
        Result result = AttendanceCalculator.calculate(present()
                .status(AttendanceStatus.ABSENT)
                .enteredHours("9")
                .build());

        assertThat(result.workedHours()).isEqualByComparingTo("0.00");
        assertThat(result.overtimeHours()).isEqualByComparingTo("0.00");
        assertThat(result.wageAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("an hourly worker is paid for the regular hours he typed, not for a whole shift")
    void typedHoursForAnHourlyWorker() {
        Result result = AttendanceCalculator.calculate(present()
                .wage(WageType.HOURLY, new BigDecimal("89.2857"), OT_RATE)
                .enteredHours("9")
                .build());

        assertThat(result.regularHours()).isEqualByComparingTo("7.00");
        assertThat(result.wageAmount()).isEqualByComparingTo("625.00");
        assertThat(result.overtimeAmount()).isEqualByComparingTo("178.57");
    }

    // ------------------------------------------------------------------ guards

    @Test
    @DisplayName("hours outside a real day are rejected before they reach the wage")
    void rejectsImpossibleTypedHours() {
        assertThatThrownBy(() -> present().enteredHours("25").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("enteredHours");
        assertThatThrownBy(() -> present().enteredHours("-1").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("enteredHours");
    }

    @Test
    @DisplayName("a non-positive shift length is rejected rather than dividing by zero later")
    void rejectsImpossibleShift() {
        assertThatThrownBy(() -> present().standardShiftHours(BigDecimal.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("standardShiftHours");
    }

    @Test
    @DisplayName("a monthly wage with no wage days is rejected")
    void rejectsMonthlyWithoutWageDays() {
        assertThatThrownBy(() -> present()
                .wage(WageType.MONTHLY, new BigDecimal("26000.0000"), OT_RATE)
                .monthlyWageDays(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("monthlyWageDays");
    }
}

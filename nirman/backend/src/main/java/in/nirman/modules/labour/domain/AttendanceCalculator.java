package in.nirman.modules.labour.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalTime;

/**
 * Turns one day's clock readings into hours and money. Deliberately a pure function with no
 * Spring, no JPA and no clock of its own — every rule below is a line in
 * docs/00-assumptions.md, and this is the one place they are expressed, so the exhaustive
 * unit tests run in milliseconds and nothing else in the module needs to re-derive them.
 *
 * <p>The rules, and where they come from:</p>
 * <ul>
 *   <li><b>Standard shift is per site</b>, not a constant. Kausani runs seven hours, and
 *       assuming eight would have inflated every overtime figure there (assumption 6).</li>
 *   <li><b>Overtime carries no premium.</b> It pays {@code overtime_rate} per hour as it
 *       stands on the attendance date. The field data confirmed OT income ÷ OT hours equals
 *       the plain hourly rate, so a hard-coded 1.5x would have been actively wrong
 *       (assumption 7).</li>
 *   <li><b>A shift crossing midnight</b> shows a check-out earlier than its check-in; add
 *       24 hours. The record belongs to the check-in date (assumption 11).</li>
 *   <li><b>Half day</b> is half a day's wage and never overtime, whatever the clock says
 *       (assumption 8).</li>
 *   <li><b>Monthly workers</b> get a derived daily rate of monthly ÷
 *       {@code sites.monthly_wage_days}, for costing only (assumption 9).</li>
 * </ul>
 *
 * <p>Hours reach this class one of three ways, in order of precedence. {@code enteredHours}
 * is what the supervisor typed and wins outright — it is an assertion about the day, not a
 * reading to be re-derived. Failing that, a check-in and check-out pair is measured. Failing
 * both, the status implies the hours: a present day is exactly one standard shift and earns
 * no overtime, which is what makes marking forty workers present a single tap.</p>
 */
public final class AttendanceCalculator {

    /** Hours are stored as numeric(6,2), money as numeric(18,2), rates as numeric(18,4). */
    private static final int HOUR_SCALE = 2;
    private static final int MONEY_SCALE = 2;
    private static final int RATE_SCALE = 4;

    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal HOURS_PER_DAY = BigDecimal.valueOf(24);

    private AttendanceCalculator() {
    }

    /**
     * @param status          what the worker did that day
     * @param checkIn         optional; null means the day was marked without clock readings
     * @param checkOut        optional; earlier than {@code checkIn} means the shift rolled past midnight
     * @param breakMinutes    unpaid break deducted from worked hours; ignored when {@code enteredHours} is given
     * @param enteredHours    optional; the hours the supervisor typed, which override the clock entirely
     * @param standardShiftHours the site's shift length; overtime begins after it
     * @param wageType        what {@code normalRate} is denominated in
     * @param normalRate      per day, hour or month according to {@code wageType}
     * @param overtimeRate    always per hour
     * @param monthlyWageDays the site's divisor for monthly wages
     */
    public record Input(
            AttendanceStatus status,
            LocalTime checkIn,
            LocalTime checkOut,
            int breakMinutes,
            BigDecimal enteredHours,
            BigDecimal standardShiftHours,
            WageType wageType,
            BigDecimal normalRate,
            BigDecimal overtimeRate,
            int monthlyWageDays) {

        public Input {
            if (status == null || standardShiftHours == null || wageType == null) {
                throw new IllegalArgumentException("status, standardShiftHours and wageType are required");
            }
            if (standardShiftHours.signum() <= 0) {
                throw new IllegalArgumentException("standardShiftHours must be positive");
            }
            if (wageType == WageType.MONTHLY && monthlyWageDays <= 0) {
                throw new IllegalArgumentException("monthlyWageDays must be positive for a monthly wage");
            }
            if (enteredHours != null
                    && (enteredHours.signum() < 0 || enteredHours.compareTo(HOURS_PER_DAY) > 0)) {
                throw new IllegalArgumentException("enteredHours must be between 0 and 24");
            }
            normalRate = normalRate == null ? BigDecimal.ZERO : normalRate;
            overtimeRate = overtimeRate == null ? BigDecimal.ZERO : overtimeRate;
        }
    }

    public record Result(
            BigDecimal workedHours,
            BigDecimal regularHours,
            BigDecimal overtimeHours,
            BigDecimal wageAmount,
            BigDecimal overtimeAmount) {

        /** What the day is worth in total — the figure the ledger and the field sheet agree on. */
        public BigDecimal totalAmount() {
            return wageAmount.add(overtimeAmount);
        }
    }

    public static Result calculate(Input in) {
        if (!in.status().isPaid()) {
            return new Result(zeroHours(), zeroHours(), zeroHours(), zeroMoney(), zeroMoney());
        }

        BigDecimal worked = workedHours(in);
        BigDecimal regular = worked.min(in.standardShiftHours());

        // Half a day never books overtime, however long the clock says the worker stayed.
        BigDecimal overtime = in.status() == AttendanceStatus.HALF_DAY
                ? BigDecimal.ZERO
                : worked.subtract(in.standardShiftHours()).max(BigDecimal.ZERO);

        BigDecimal wage = wageAmount(in, regular);
        BigDecimal overtimeAmount = overtime.multiply(in.overtimeRate());

        return new Result(
                scaleHours(worked),
                scaleHours(regular),
                scaleHours(overtime),
                scaleMoney(wage),
                scaleMoney(overtimeAmount));
    }

    /**
     * Typed hours win, then clock readings, then the status implies the hours — a full
     * standard shift for a present day, half of one for a half day.
     *
     * <p>The break is not deducted from typed hours. "He worked nine hours" already has
     * lunch taken out of it in the way a supervisor means it, and subtracting a break on
     * top would quietly pay him for eight.</p>
     */
    private static BigDecimal workedHours(Input in) {
        if (in.enteredHours() != null) {
            return in.enteredHours();
        }
        if (in.checkIn() == null || in.checkOut() == null) {
            return in.status() == AttendanceStatus.HALF_DAY
                    ? in.standardShiftHours().multiply(HALF)
                    : in.standardShiftHours();
        }

        BigDecimal gross = hoursBetween(in.checkIn(), in.checkOut());
        BigDecimal breakHours = BigDecimal.valueOf(in.breakMinutes())
                .divide(MINUTES_PER_HOUR, RATE_SCALE, RoundingMode.HALF_UP);
        // A break longer than the shift itself would otherwise produce negative hours.
        return gross.subtract(breakHours).max(BigDecimal.ZERO);
    }

    private static BigDecimal hoursBetween(LocalTime checkIn, LocalTime checkOut) {
        BigDecimal hours = BigDecimal.valueOf(Duration.between(checkIn, checkOut).toMinutes())
                .divide(MINUTES_PER_HOUR, RATE_SCALE, RoundingMode.HALF_UP);
        // Equal times mean a full round-the-clock shift, not a zero-length one.
        return hours.signum() <= 0 ? hours.add(HOURS_PER_DAY) : hours;
    }

    private static BigDecimal wageAmount(Input in, BigDecimal regularHours) {
        return switch (in.wageType()) {
            // Paid by the clock: the hours are the truth, and a half day simply has fewer.
            case HOURLY -> in.normalRate().multiply(regularHours);
            case DAILY -> dayFraction(in).multiply(in.normalRate());
            case MONTHLY -> dayFraction(in).multiply(
                    in.normalRate().divide(BigDecimal.valueOf(in.monthlyWageDays()),
                            RATE_SCALE, RoundingMode.HALF_UP));
        };
    }

    private static BigDecimal dayFraction(Input in) {
        return in.status() == AttendanceStatus.HALF_DAY ? HALF : BigDecimal.ONE;
    }

    private static BigDecimal scaleHours(BigDecimal value) {
        return value.setScale(HOUR_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaleMoney(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal zeroHours() {
        return BigDecimal.ZERO.setScale(HOUR_SCALE, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal zeroMoney() {
        return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    }
}

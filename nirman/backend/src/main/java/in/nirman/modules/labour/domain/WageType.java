package in.nirman.modules.labour.domain;

/**
 * What {@code wage_rates.normal_rate} is denominated in for a given worker. The overtime
 * rate is always per hour regardless.
 */
public enum WageType {
    /** normal_rate is per day. */
    DAILY,
    /** normal_rate is per hour. */
    HOURLY,
    /** normal_rate is per month; the daily rate is derived using sites.monthly_wage_days. */
    MONTHLY
}

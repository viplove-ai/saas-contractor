package in.nirman.modules.labour.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The labour module's read API for the DPR and the dashboards, in the shape they roll up.
 *
 * <p>The same boundary {@link in.nirman.modules.project.service.SiteLookup} draws for sites:
 * a daily progress report needs a handful of facts about one site's attendance on one day,
 * and it gets them here rather than reaching into {@code AttendanceRecordRepository}.</p>
 *
 * <h2>Why the cost comes back split by whether it is verified</h2>
 *
 * <p>Hours are recomputed on every save, but the <b>wage is frozen at verification</b>
 * (docs/09, and {@code AttendanceRecord.freezeWage}). So the money on an unverified row is
 * provisional: it was computed against whatever rate happened to be in force when the
 * supervisor typed the day, and verification may resolve a different one. A DPR prepared the
 * same evening therefore quotes a labour cost that is honest only if it says which part of
 * it nobody has signed for yet — and a dashboard that folded the two together would report a
 * project cost that moves by itself overnight.</p>
 */
public interface LabourLookup {

    /**
     * One site's attendance on one day.
     *
     * @param presentCount   men who worked, half-days included — the head count a DPR reports
     * @param cost           wage plus overtime over every live row, verified or not
     * @param unverifiedCost the part of {@code cost} still waiting on an engineer, so a
     *                       reader can tell a signed figure from a provisional one
     * @param groups         the same total broken down the way a DPR's labour table prints it
     * @param boqItemIds     work items men were booked against that day. A DPR prefills its
     *                       work-item rows from these, because a line somebody's time was
     *                       charged to is a line somebody worked on.
     */
    record LabourDay(
            LocalDate date,
            int presentCount,
            int absentCount,
            BigDecimal regularHours,
            BigDecimal overtimeHours,
            BigDecimal cost,
            BigDecimal unverifiedCost,
            int recordCount,
            int unverifiedCount,
            List<LabourGroup> groups,
            List<UUID> boqItemIds) {
    }

    /**
     * A row of the DPR's labour table: how many of one trade, under one contractor, for how
     * long. Skill and contractor together because that is how the muster is read on site —
     * "six masons under Karam Singh" is one line, and splitting it by either alone loses the
     * other.
     */
    record LabourGroup(
            UUID skillCategoryId,
            String skillCategoryName,
            UUID labourContractorId,
            String labourContractorName,
            int headCount,
            BigDecimal regularHours,
            BigDecimal overtimeHours,
            BigDecimal cost) {
    }

    /**
     * The same figures over a period, for the dashboards.
     *
     * @param manDays        present days plus half a day for each half-day, which is what a
     *                       productivity figure has to divide by
     * @param pendingVerification rows a supervisor has submitted and nobody has signed —
     *                            the number a site dashboard exists to make visible
     */
    record LabourPeriod(
            LocalDate from,
            LocalDate to,
            BigDecimal manDays,
            BigDecimal regularHours,
            BigDecimal overtimeHours,
            BigDecimal cost,
            BigDecimal verifiedCost,
            int pendingVerification,
            int daysWithAttendance) {
    }

    /** One day's labour cost, for a dashboard trend line. */
    record DailyCost(LocalDate date, BigDecimal cost) {
    }

    /**
     * Men on site under a labour contractor, counted rather than marked.
     *
     * <p>Separate from {@link LabourDay} on purpose, and it carries <b>no cost</b>. These men
     * have no worker record and no wage rate — the contractor bills for the work — so any
     * money attached here would be invented. A DPR prints the counts beside the muster-roll
     * labour and adds nothing of theirs to the day's cost.</p>
     *
     * <p>Hours it does carry, and they are unpriced for the same reason. They are recorded
     * where the site knows them and left out where it does not, which is why they are
     * nullable rather than zero.</p>
     *
     * @param manHours the day's man-hours over the trades that recorded hours, or zero if
     *                 none did
     */
    record OutsourcedDay(
            LocalDate date,
            boolean enabled,
            int headCount,
            BigDecimal manHours,
            List<OutsourcedGroup> groups) {
    }

    /**
     * @param hours    hours each man of the trade worked, or null if nobody recorded them
     * @param manHours {@code hours} times {@code headCount}, or null for the same reason
     */
    record OutsourcedGroup(
            UUID skillCategoryId,
            String skillCategoryName,
            UUID labourContractorId,
            String labourContractorName,
            int headCount,
            BigDecimal hours,
            BigDecimal manHours) {
    }

    /** No permission check: the caller has already passed the one that got it here. */
    OutsourcedDay outsourced(UUID siteId, LocalDate date);

    /** No permission check: the caller has already passed the one that got it here. */
    LabourDay day(UUID siteId, LocalDate date);

    LabourPeriod period(UUID siteId, LocalDate from, LocalDate to);

    /**
     * The period's cost bucketed by day, in one pass over the rows rather than one call per
     * day — a month-long trend must not cost a month's worth of round trips.
     *
     * <p>{@code siteId} null means every site in the organisation.</p>
     */
    List<DailyCost> dailyCost(UUID siteId, LocalDate from, LocalDate to);

    /** Dates in the range with no live attendance row at all — a data-quality question. */
    List<LocalDate> daysWithoutAttendance(UUID siteId, LocalDate from, LocalDate to);
}

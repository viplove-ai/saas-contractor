package in.nirman.modules.labour.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for outsourced labour head counts. */
public final class SiteLabourCountDtos {

    private SiteLabourCountDtos() {
    }

    /**
     * One trade's count for the day.
     *
     * @param headCount zero is a real entry — "no bar benders today" — and is kept rather
     *                  than dropped, because a missing row means nobody said, and a zero
     *                  means somebody said none.
     * @param hours     hours <b>each</b> man of the trade worked, not the gang's total.
     *                  Optional, and null is not zero: a day counted at the gate without
     *                  anybody noting hours has said nothing about them. No money follows
     *                  from it — the contractor bills for the work.
     */
    public record CountLine(
            @NotNull UUID skillCategoryId,
            UUID labourContractorId,
            @Min(0) int headCount,
            @Digits(integer = 2, fraction = 2) @DecimalMin("0.0") @DecimalMax("24.0")
            BigDecimal hours,
            @Size(max = 300) String remarks) {
    }

    /**
     * The whole day, sent as one list. Replacing the day rather than patching a line is what
     * makes the screen safe to re-send from a phone that lost signal mid-save: the result of
     * sending it twice is the same day, not two days added together.
     */
    public record SaveCountsRequest(
            @NotNull UUID siteId,
            @NotNull LocalDate date,
            @Valid @NotNull List<CountLine> lines) {
    }

    /** @param manHours head count times hours each, or null when hours were not recorded */
    public record CountResponse(
            UUID id,
            UUID skillCategoryId,
            String skillCategoryName,
            UUID labourContractorId,
            String labourContractorName,
            int headCount,
            BigDecimal hours,
            BigDecimal manHours,
            String remarks) {
    }

    /**
     * @param enabled whether this site records outsourced labour at all. The screen asks
     *                before it draws the section, so a site with its own muster roll never
     *                shows a counts box that would mean nothing.
     * @param periodLocked the month is closed; the section renders read-only rather than
     *                     letting somebody type a day that will be refused on save
     * @param totalManHours the day's man-hours over the trades that recorded hours. Zero
     *                      when none did, which the screen shows as nothing rather than as
     *                      a total — see {@link CountLine#hours()}.
     */
    public record DayCountsResponse(
            UUID siteId,
            LocalDate date,
            boolean enabled,
            boolean periodLocked,
            int totalHeadCount,
            BigDecimal totalManHours,
            List<CountResponse> lines) {
    }
}

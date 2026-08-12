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
            UUID labourSupplierId,
            @Min(0) int headCount,
            @Digits(integer = 2, fraction = 2) @DecimalMin("0.0") @DecimalMax("24.0")
            BigDecimal hours,
            @Size(max = 300) String remarks) {
    }

    /**
     * Whether a supplier, or the man he sends to run his gang, was on the site that day.
     *
     * <p>Per supplier and not per trade, because a supplier who sent masons, helpers and bar
     * benders has three count lines and "was he here" answered three times is a question
     * that can contradict itself.</p>
     *
     * <p>Worth asking at all because it is what the site argues about a fortnight later. A
     * gang left to itself for a week is how work goes wrong, and "his man was never here" is
     * a claim that needs a day-by-day answer rather than a memory.</p>
     */
    public record SupplierDayLine(
            @NotNull UUID labourSupplierId,
            boolean supplierPresent,
            /** Who came, when it was not the supplier himself. */
            @Size(max = 150) String representativeName,
            @Size(max = 300) String remarks) {
    }

    /**
     * The whole day, sent as one list. Replacing the day rather than patching a line is what
     * makes the screen safe to re-send from a phone that lost signal mid-save: the result of
     * sending it twice is the same day, not two days added together.
     *
     * <p>{@code suppliers} is null on a client that has not been updated, which is read as
     * "nothing to say about them" and leaves the day's supplier rows alone. An empty list is
     * a different claim — nobody was there — and clears them.</p>
     */
    public record SaveCountsRequest(
            @NotNull UUID siteId,
            @NotNull LocalDate date,
            @Valid @NotNull List<CountLine> lines,
            @Valid List<SupplierDayLine> suppliers) {
    }

    /** @param manHours head count times hours each, or null when hours were not recorded */
    public record CountResponse(
            UUID id,
            UUID skillCategoryId,
            String skillCategoryName,
            UUID labourSupplierId,
            String labourSupplierName,
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
    public record SupplierDayResponse(
            UUID labourSupplierId,
            String labourSupplierName,
            boolean supplierPresent,
            String representativeName,
            String remarks,
            /** His men on the site that day, added across the trades he supplied. */
            int headCount) {
    }

    public record DayCountsResponse(
            UUID siteId,
            LocalDate date,
            boolean enabled,
            boolean periodLocked,
            int totalHeadCount,
            BigDecimal totalManHours,
            List<CountResponse> lines,
            /** One entry per supplier named on the day, whether or not he was there. */
            List<SupplierDayResponse> suppliers) {
    }
}

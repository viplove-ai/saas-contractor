package in.nirman.modules.labour.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
     */
    public record CountLine(
            @NotNull UUID skillCategoryId,
            UUID labourContractorId,
            @Min(0) int headCount,
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

    public record CountResponse(
            UUID id,
            UUID skillCategoryId,
            String skillCategoryName,
            UUID labourContractorId,
            String labourContractorName,
            int headCount,
            String remarks) {
    }

    /**
     * @param enabled whether this site records outsourced labour at all. The screen asks
     *                before it draws the section, so a site with its own muster roll never
     *                shows a counts box that would mean nothing.
     * @param periodLocked the month is closed; the section renders read-only rather than
     *                     letting somebody type a day that will be refused on save
     */
    public record DayCountsResponse(
            UUID siteId,
            LocalDate date,
            boolean enabled,
            boolean periodLocked,
            int totalHeadCount,
            List<CountResponse> lines) {
    }
}

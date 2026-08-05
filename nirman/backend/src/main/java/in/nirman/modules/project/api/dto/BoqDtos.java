package in.nirman.modules.project.api.dto;

import in.nirman.modules.project.domain.BoqItem;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for BOQ items. */
public final class BoqDtos {

    private BoqDtos() {
    }

    public record CreateBoqItemRequest(
            @NotNull UUID projectId,
            /** Null means the line applies across the project rather than at one site. */
            UUID siteId,
            @NotBlank @Size(max = 40) String itemNumber,
            @NotBlank String description,
            @NotNull UUID unitId,
            @NotNull @DecimalMin("0") BigDecimal contractQuantity,
            @NotNull @DecimalMin("0") BigDecimal contractRate,
            @Size(max = 40) String workPart,
            /** Matches a consumption norm's work category, so estimates can be derived. */
            @Size(max = 80) String category,
            Integer sortOrder) {
    }

    public record UpdateBoqItemRequest(
            UUID siteId,
            @NotBlank String description,
            @NotNull @DecimalMin("0") BigDecimal contractQuantity,
            @NotNull @DecimalMin("0") BigDecimal contractRate,
            @Size(max = 40) String workPart,
            @Size(max = 80) String category,
            Integer sortOrder,
            BoqItem.Status status,
            @NotNull Long version) {
    }

    /**
     * A measurement against a line. Signed: a negative quantity corrects an earlier
     * over-measurement, which is the only way an entry is ever undone.
     */
    public record RecordProgressRequest(
            /** Needed only for a line that spans the project rather than sitting at one site. */
            UUID siteId,
            @NotNull LocalDate entryDate,
            @NotNull BigDecimal quantity,
            @Size(max = 2000) String remarks) {
    }

    public record ProgressEntryResponse(
            UUID id,
            UUID boqItemId,
            String itemNumber,
            UUID siteId,
            LocalDate entryDate,
            BigDecimal quantity,
            /** Set when the claim arrived through a verified daily progress report. */
            UUID dprId,
            String remarks,
            UUID recordedBy,
            Instant createdAt) {
    }

    /**
     * The measurement book for one line.
     *
     * @param percentComplete null when the contract quantified nothing — an unanswerable
     *                        question, not a zero
     * @param overClaimedQuantity measured beyond what the contract quantified. Permitted and
     *                            reported, because over-measurement against a tendered figure
     *                            is ordinary and a system that refused it would be lied to.
     */
    public record ProgressLedger(
            UUID boqItemId,
            String itemNumber,
            String description,
            UUID unitId,
            BigDecimal contractQuantity,
            BigDecimal completedQuantity,
            BigDecimal percentComplete,
            BigDecimal overClaimedQuantity,
            BigDecimal claimedInRange,
            BoqItem.Status status,
            LocalDate actualStartDate,
            LocalDate actualCompletionDate,
            List<ProgressEntryResponse> entries) {
    }

    public record BoqItemResponse(
            UUID id,
            UUID projectId,
            UUID siteId,
            String itemNumber,
            String description,
            UUID unitId,
            BigDecimal contractQuantity,
            BigDecimal contractRate,
            BigDecimal contractAmount,
            BigDecimal completedQuantity,
            BoqItem.Status status,
            String workPart,
            String category,
            boolean synthetic,
            int sortOrder,
            Long version) {
    }
}

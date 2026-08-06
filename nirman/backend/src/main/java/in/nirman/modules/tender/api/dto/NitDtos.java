package in.nirman.modules.tender.api.dto;

import in.nirman.modules.project.api.dto.ProjectDtos.CreateProjectRequest;
import in.nirman.modules.project.api.dto.ProjectDtos.ProjectResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for reading a NIT and turning it into a project. */
public final class NitDtos {

    private NitDtos() {
    }

    /**
     * Everything the notice was read to say, beyond the schedule itself.
     *
     * <p>Sent out in the preview and sent back on confirm, so a correction the user makes to
     * the earnest money or a deadline is what gets stored. Every field is nullable: this is a
     * reading of a scanned government document, not a form somebody filled in.</p>
     */
    public record NitFields(
            @Size(max = 120) String nitNo,
            String workName,
            @Digits(integer = 16, fraction = 2) BigDecimal estimatedCost,
            @Digits(integer = 16, fraction = 2) BigDecimal civilEstimatedCost,
            @Digits(integer = 16, fraction = 2) BigDecimal electricalEstimatedCost,
            @Digits(integer = 16, fraction = 2) BigDecimal emdAmount,
            @Size(max = 80) String completionPeriod,
            LocalDateTime submissionClosing,
            LocalDateTime bidOpening,
            @Size(max = 120) String division,
            @Size(max = 300) String location,
            @Size(max = 40) String bidType,
            String contractorEligibility,
            String similarWorkCriteria,
            @Digits(integer = 3, fraction = 3) BigDecimal performanceGuaranteePercent,
            @Digits(integer = 3, fraction = 3) BigDecimal securityDepositPercent,
            Integer civilDsrYear,
            @Digits(integer = 4, fraction = 3) BigDecimal civilCostIndexPercent,
            Integer electricalDsrYear,
            @Digits(integer = 4, fraction = 3) BigDecimal electricalCostIndexPercent) {
    }

    /**
     * One schedule row as the preview shows it.
     *
     * @param amount        what the tender printed
     * @param derivedAmount quantity × rate, which is what will actually be stored. Both are
     *                      sent because a row where they disagree is a row worth looking at.
     * @param unit          as printed; {@code unitCode} is what it resolved to
     * @param unitRecognised false when the unit had to be invented for this organisation
     * @param synthetic     a reconciliation placeholder, not work
     * @param renumbered    true when the item number had to be changed to stay unique
     */
    public record PreviewBoqLine(
            int index,
            String itemNumber,
            String description,
            BigDecimal quantity,
            String unit,
            String unitCode,
            boolean unitRecognised,
            BigDecimal rate,
            BigDecimal amount,
            BigDecimal derivedAmount,
            String workPart,
            String category,
            boolean synthetic,
            boolean renumbered) {
    }

    /**
     * The result of reading an uploaded notice. Nothing is persisted for this except the PDF
     * itself, which is stored once so the confirm step need not re-upload it.
     *
     * @param suggestedCode a project code derived from the NIT number, which the user almost
     *                      always adjusts — it is a starting point, not an answer
     * @param derivedTotal  what the schedule will be worth once stored
     */
    public record NitPreviewResponse(
            UUID attachmentId,
            String fileName,
            int pageCount,
            String suggestedCode,
            String suggestedName,
            String nitNumber,
            String tenderReference,
            BigDecimal contractValue,
            NitFields fields,
            List<PreviewBoqLine> boqLines,
            BigDecimal boqTotal,
            BigDecimal derivedTotal,
            List<String> warnings) {
    }

    /**
     * A schedule row as the user confirmed it.
     *
     * <p>The amount is deliberately absent: it is derived from the quantity and the rate when
     * the line is written, so there is no way to save a row whose total disagrees with its
     * own arithmetic.</p>
     */
    public record ConfirmedBoqLine(
            @NotBlank @Size(max = 40) String itemNumber,
            @NotBlank String description,
            @NotBlank @Size(max = 20) String unitCode,
            @NotNull @DecimalMin("0") @Digits(integer = 14, fraction = 4) BigDecimal quantity,
            @NotNull @DecimalMin("0") @Digits(integer = 14, fraction = 4) BigDecimal rate,
            @Size(max = 40) String workPart,
            @Size(max = 80) String category,
            boolean synthetic) {
    }

    /**
     * The confirm step. The client sends back what it displayed, edits and all, so the server
     * holds no draft between the two calls and the user's corrections are authoritative.
     */
    public record CreateFromNitRequest(
            @NotNull UUID attachmentId,
            @Positive int pageCount,
            @Valid @NotNull CreateProjectRequest project,
            @Valid NitFields fields,
            @Valid @Size(max = 2000) List<ConfirmedBoqLine> boqLines,
            /** Echoed back from the preview, so the record keeps what the reader was unsure of. */
            List<String> warnings) {
    }

    public record NitImportResponse(
            ProjectResponse project,
            UUID nitDocumentId,
            int boqLineCount,
            BigDecimal boqValue) {
    }

    /** The stored reading of a project's tender. */
    public record NitDocumentResponse(
            UUID id,
            UUID projectId,
            UUID attachmentId,
            String fileName,
            int pageCount,
            String parserVersion,
            NitFields fields,
            BigDecimal boqTotal,
            int extractedItemCount,
            List<String> warnings) {
    }
}

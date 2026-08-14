package in.nirman.modules.tender.api.dto;

import in.nirman.modules.project.api.dto.ProjectDtos.CreateProjectRequest;
import in.nirman.modules.project.api.dto.ProjectDtos.ProjectResponse;
import in.nirman.modules.tender.parser.AllowedTime;
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
     * One milestone, as read from Schedule F.
     *
     * <p>Both percentages are nullable, which is a reading rather than a gap: a milestone may
     * state a share of the tendered value, or name the work expected finished, or both joined by
     * "or". {@code physical} says which — and the physical descriptions are the half a plan is
     * built from, because they are the department's own phasing of the work.</p>
     *
     * @param withheldPercent of the accepted tendered value, held back on a miss and released
     *                        when a later milestone is met. A timing event, never a cost.
     */
    public record MilestoneTerm(
            @Positive int sequence,
            @NotBlank String description,
            @Positive Integer timeAllowedValue,
            AllowedTime.Unit timeAllowedUnit,
            @Digits(integer = 3, fraction = 3) BigDecimal financialPercent,
            @Digits(integer = 3, fraction = 3) BigDecimal withheldPercent,
            boolean physical) {
    }

    /**
     * Clause 7's threshold for one work part, or for the whole contract when {@code workPart} is
     * null. Civil and E&amp;M carry different figures and therefore bill on different rhythms.
     */
    public record InterimMinimumTerm(
            @Size(max = 40) String workPart,
            @NotNull @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal amount) {
    }

    /**
     * The contractual terms a plan is built on: when the work must reach which stage, when the
     * clock starts, and how much work must exist before a bill may be raised.
     *
     * <p>Round-tripped through the client like {@link NitFields}, because the server holds no
     * draft between preview and confirm. Unlike the fields, these are not expected to be edited
     * — a person correcting a milestone table row by row would be retyping the contract, not
     * reviewing a reading — but they travel the same path so there is one shape to the flow.</p>
     */
    public record ScheduleTerms(
            @Positive Integer completionValue,
            AllowedTime.Unit completionUnit,
            Integer startReckoningDays,
            Boolean clause7aApplicable,
            /** Bid below this share of the estimate and a second guarantee falls due. */
            @Digits(integer = 3, fraction = 3) BigDecimal apgThresholdPercent,
            @Size(max = 20) String apgMethod,
            @Digits(integer = 3, fraction = 3) BigDecimal apgPercent,
            @Valid @Size(max = 20) List<MilestoneTerm> milestones,
            @Valid @Size(max = 6) List<InterimMinimumTerm> interimMinimums) {

        public static final ScheduleTerms EMPTY =
                new ScheduleTerms(null, null, null, null, null, null, null,
                        List.of(), List.of());

        public List<MilestoneTerm> milestonesOrEmpty() {
            return milestones == null ? List.of() : milestones;
        }

        public List<InterimMinimumTerm> interimMinimumsOrEmpty() {
            return interimMinimums == null ? List.of() : interimMinimums;
        }
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
            ScheduleTerms scheduleTerms,
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
            @Valid ScheduleTerms scheduleTerms,
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
            ScheduleTerms scheduleTerms,
            BigDecimal boqTotal,
            int extractedItemCount,
            List<String> warnings) {
    }
}

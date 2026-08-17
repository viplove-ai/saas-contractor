package in.nirman.modules.dpr.api.dto;

import in.nirman.modules.dpr.domain.DailyProgressReport.NonOperationalCause;
import in.nirman.modules.dpr.domain.DailyProgressReport.Weather;
import in.nirman.modules.dpr.domain.DailyProgressReport.Workflow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for daily progress reports. */
public final class DprDtos {

    private DprDtos() {
    }

    // ---------------------------------------------------------------- prefill

    /**
     * What the other three modules already know about the day, offered to the wizard's first
     * step so a supervisor confirms figures instead of copying them.
     *
     * <p>Every figure here is derived from the underlying records at the moment of the call —
     * nothing is cached — which is what makes the Phase 6 exit criterion ("DPR prefill matches
     * the underlying records exactly") a property of the design rather than a hope.</p>
     *
     * @param labourCostProvisional true while any attendance row behind the labour cost is
     *                              still unverified. The wage is frozen at verification, so
     *                              until then the figure is what today's rates say it is, and
     *                              a report that did not admit that would be quoting a number
     *                              that can change without anybody editing it.
     * @param suggestedWorkItems    BOQ lines that had labour or material charged to them that
     *                              day. Suggestions, not entries: what the site consumed
     *                              against a line is evidence somebody worked on it, and it is
     *                              not a measurement of how much got built.
     * @param caveat                the sentence that travels with the numbers, written once
     *                              here so every client says the same thing about them
     */
    public record DprPrefill(
            UUID siteId,
            String siteName,
            LocalDate reportDate,
            boolean reportExists,
            UUID existingDprId,
            LabourPrefill labour,
            OutsourcedPrefill outsourcedLabour,
            MaterialPrefill material,
            ExpensePrefill expense,
            boolean labourCostProvisional,
            List<SuggestedWorkItem> suggestedWorkItems,
            String caveat) {
    }

    public record LabourPrefill(
            int presentCount,
            int absentCount,
            BigDecimal regularHours,
            BigDecimal overtimeHours,
            BigDecimal cost,
            BigDecimal unverifiedCost,
            int recordCount,
            int unverifiedCount,
            List<LabourLine> lines) {
    }

    /**
     * @param outsourced the row is a head count from a contractor-run site rather than a
     *                   line of the muster roll, so its hours are zero because nobody
     *                   clocked them — not because the men stood idle. Never add an
     *                   outsourced row's head count into the muster's.
     */
    public record LabourLine(
            UUID skillCategoryId,
            String skillCategoryName,
            UUID labourSupplierId,
            String labourSupplierName,
            int headCount,
            BigDecimal regularHours,
            BigDecimal overtimeHours,
            boolean outsourced) {
    }

    /**
     * The contractor's men, counted at the gate rather than marked on a muster roll.
     *
     * <p>Kept apart from {@link LabourPrefill} rather than folded into its head count, and
     * carrying no hours and no money. These men have no worker record and no wage rate —
     * the contractor bills for the work — so a total that mixed them with our own labour
     * would read as forty men earning wages when twenty-six of them are somebody else's
     * bill.</p>
     *
     * @param enabled false for a site that keeps its own muster roll, in which case the
     *                section is not drawn at all rather than drawn empty
     */
    public record OutsourcedPrefill(
            boolean enabled,
            int headCount,
            /** Man-hours over the trades that recorded hours; zero when none did. */
            BigDecimal manHours,
            List<OutsourcedLine> lines) {
    }

    /**
     * @param hours    hours each man of the trade worked, or null if nobody recorded them —
     *                 which the report prints as nothing, not as zero
     * @param manHours {@code hours} times {@code headCount}, null for the same reason
     */
    public record OutsourcedLine(
            UUID skillCategoryId,
            String skillCategoryName,
            UUID labourSupplierId,
            String labourSupplierName,
            int headCount,
            BigDecimal hours,
            BigDecimal manHours) {
    }

    /**
     * @param receivedValue what arrived at the store — inventory, not cost
     * @param consumedValue what was issued to the work face, at the moving average of the day
     *                      it left. This is the figure that adds to project cost.
     */
    public record MaterialPrefill(
            BigDecimal receivedValue,
            BigDecimal consumedValue,
            int receiptCount,
            int issueCount,
            List<MaterialLine> lines) {
    }

    public record MaterialLine(
            UUID materialId,
            String materialCode,
            String materialName,
            String baseUnitCode,
            BigDecimal receivedQty,
            BigDecimal receivedValue,
            BigDecimal consumedQty,
            BigDecimal consumedValue) {
    }

    /**
     * The four figures, never one. {@code costIncurred} is the only one that may be added to
     * labour cost and material consumption; the other two are costed elsewhere (docs/09).
     */
    public record ExpensePrefill(
            BigDecimal totalBooked,
            BigDecimal costIncurred,
            BigDecimal materialPurchases,
            BigDecimal labourDisbursements,
            int expenseCount,
            int unapprovedCount) {
    }

    public record SuggestedWorkItem(
            UUID boqItemId,
            String itemNumber,
            String description,
            UUID unitId,
            /** Why this line is being suggested — "material issued", "labour charged", or both. */
            String because) {
    }

    // ---------------------------------------------------------------- write

    /**
     * {@code id} is generated by the client so a report typed at site with no signal and
     * synced three times is one report.
     *
     * <p>The fields fall into two halves and the service keeps them apart. {@code siteOperational}
     * down to {@code workingHoursLost} are the supervisor's — he was there — and everything from
     * {@code workSummary} onwards is the engineer's, because a quantity on this report becomes a
     * claim against the contract when he signs it.</p>
     *
     * @param siteOperational whether the site worked at all. Defaults to true when a client
     *                        omits it, which is what every report written before the question
     *                        existed meant.
     */
    public record CreateDprRequest(
            @NotNull UUID id,
            @NotNull UUID siteId,
            @NotNull LocalDate reportDate,
            Boolean siteOperational,
            NonOperationalCause nonOperationalCause,
            @Size(max = 2000) String nonOperationalNote,
            Weather weather,
            BigDecimal temperatureC,
            @DecimalMin("0") BigDecimal workingHoursLost,
            @Size(max = 5000) String workSummary,
            @Size(max = 5000) String delays,
            @Size(max = 5000) String safetyObservations,
            @Size(max = 5000) String qualityObservations,
            @Size(max = 5000) String instructionsReceived,
            @Size(max = 5000) String managementAttention,
            @Size(max = 5000) String nextDayPlan,
            @Valid List<WorkItemInput> workItems,
            @Valid List<MachineryInput> machinery) {
    }

    public record UpdateDprRequest(
            Boolean siteOperational,
            NonOperationalCause nonOperationalCause,
            @Size(max = 2000) String nonOperationalNote,
            Weather weather,
            BigDecimal temperatureC,
            @DecimalMin("0") BigDecimal workingHoursLost,
            @Size(max = 5000) String workSummary,
            @Size(max = 5000) String delays,
            @Size(max = 5000) String safetyObservations,
            @Size(max = 5000) String qualityObservations,
            @Size(max = 5000) String instructionsReceived,
            @Size(max = 5000) String managementAttention,
            @Size(max = 5000) String nextDayPlan,
            @Valid List<WorkItemInput> workItems,
            @Valid List<MachineryInput> machinery,
            @NotNull Long version) {
    }

    /**
     * @param boqItemId null for work that measures against no contract line — shuttering
     *                  struck, curing, a site cleared. Real work, and not a claim.
     * @param quantity  null or zero describes; a figure claims. Only a claim reaches the
     *                  measurement book, and only when the engineer verifies the report.
     */
    public record WorkItemInput(
            UUID boqItemId,
            @NotBlank @Size(max = 2000) String activity,
            @Size(max = 150) String workLocation,
            BigDecimal quantity,
            UUID unitId,
            @Size(max = 2000) String remarks) {
    }

    public record MachineryInput(
            @NotBlank @Size(max = 150) String machineryName,
            int count,
            @DecimalMin("0") BigDecimal hoursUsed,
            @DecimalMin("0") BigDecimal idleHours,
            @Size(max = 300) String remarks) {
    }

    public record AttachPhotoRequest(
            @NotNull UUID attachmentId,
            @Size(max = 300) String caption,
            Instant takenAt) {
    }

    public record VerifyDprRequest(
            @NotNull Action action,
            @Size(max = 2000) String remarks) {

        public enum Action { VERIFY, REJECT }
    }

    /**
     * Why a report is being deleted, and it is not optional.
     *
     * <p>The same rule a project's deletion follows: what is being removed carries a document
     * number and sat in the register, and six months later "it is not there" without a reason
     * beside it is indistinguishable from something having gone wrong.</p>
     */
    public record DeleteDprRequest(
            @NotBlank @Size(max = 500) String reason) {
    }

    // ---------------------------------------------------------------- read

    public record WorkItemResponse(
            UUID id,
            UUID boqItemId,
            String itemNumber,
            String activity,
            String workLocation,
            BigDecimal quantity,
            UUID unitId,
            String remarks,
            int sortOrder,
            /** Whether this row claims measured work against the contract. */
            boolean measured) {
    }

    public record MachineryResponse(
            UUID id,
            String machineryName,
            int count,
            BigDecimal hoursUsed,
            BigDecimal idleHours,
            String remarks) {
    }

    public record PhotoResponse(
            UUID id,
            UUID attachmentId,
            String caption,
            Instant takenAt,
            String fileName,
            String contentType,
            long sizeBytes,
            int sortOrder) {
    }

    /**
     * @param snapshotFrozen true once the report has been submitted. From then on the rolled-up
     *                       figures are the document's own and are never recomputed — a
     *                       correction to an underlying record shows up as a difference, not by
     *                       rewriting a report somebody signed.
     * @param progressPosted claims this report put into the measurement book on verification
     */
    public record DprResponse(
            UUID id,
            String dprNumber,
            UUID siteId,
            String siteName,
            UUID projectId,
            LocalDate reportDate,
            /** False when the site did not work. The report is then about why, and nothing else. */
            boolean siteOperational,
            NonOperationalCause nonOperationalCause,
            String nonOperationalNote,
            Weather weather,
            BigDecimal temperatureC,
            BigDecimal workingHoursLost,
            Integer labourPresentCount,
            /** Contractor's men counted at the gate. Beside the present count, never in it. */
            int outsourcedHeadCount,
            /** Their man-hours, kept out of labourRegularHours for the same reason. */
            BigDecimal outsourcedManHours,
            /**
             * Every man who stood on the site: the muster roll's plus the suppliers' gangs.
             *
             * <p>"How many men were on the site" has one answer and the department asks it of
             * the site, not of the payroll — so the report answers it, and the two figures it
             * is made of stay beside it because they are what the answer is made of.</p>
             *
             * <p><b>Derived, never stored.</b> {@code labour_present_count} and
             * {@code outsourced_head_count} are the frozen columns; this is their sum computed
             * per call, so the two kinds of man remain separable in the record and no wage is
             * ever spread over a supplier's gang. The money figures are untouched by it —
             * {@code labourCost} is still the muster roll's alone, and the hours stay in their
             * own columns for the same reason: a man-hour on the muster has a rate behind it
             * and one at the gate has none.</p>
             *
             * <p>Named for what it counts rather than {@code totalHeadCount}, which the
             * labour-counts endpoint already uses for the suppliers' men alone. Two totals
             * with one name is how a screen ends up printing the smaller one.</p>
             */
            int menOnSite,
            BigDecimal labourRegularHours,
            BigDecimal labourOvertimeHours,
            BigDecimal labourCost,
            BigDecimal materialReceivedValue,
            BigDecimal materialConsumedValue,
            BigDecimal expenseAmount,
            /** Labour + material consumed + cost incurred. The one total that adds up. */
            BigDecimal dayCost,
            boolean snapshotFrozen,
            String workSummary,
            String delays,
            String safetyObservations,
            String qualityObservations,
            String instructionsReceived,
            String managementAttention,
            String nextDayPlan,
            Workflow workflowStatus,
            UUID preparedBy,
            String preparedByName,
            Instant submittedAt,
            UUID verifiedBy,
            String verifiedByName,
            Instant verifiedAt,
            String rejectionReason,
            Integer progressPosted,
            Long version,
            List<WorkItemResponse> workItems,
            List<LabourLine> labour,
            List<MachineryResponse> machinery,
            List<PhotoResponse> photos) {
    }
}

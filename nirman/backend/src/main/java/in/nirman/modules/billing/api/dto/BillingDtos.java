package in.nirman.modules.billing.api.dto;

import in.nirman.modules.billing.domain.MeasurementSheet;
import in.nirman.modules.billing.domain.RaBill;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for measurement sheets and running account bills. */
public final class BillingDtos {

    private BillingDtos() {
    }

    // ------------------------------------------------------------------ measurement sheets

    /**
     * One ruled row. Every dimension is optional because the item's unit decides which apply,
     * and a missing one means the question does not arise — not that the value is zero.
     */
    public record MeasurementLineInput(
            @Size(max = 300) String location,
            @DecimalMin("0") BigDecimal nos,
            @DecimalMin("0") BigDecimal mult,
            @DecimalMin("0") BigDecimal length,
            @DecimalMin("0") BigDecimal breadth,
            @DecimalMin("0") BigDecimal height,
            boolean deduction,
            Integer barDia) {
    }

    public record MeasurementLineResponse(
            UUID id,
            int lineNo,
            String location,
            BigDecimal nos,
            BigDecimal mult,
            BigDecimal length,
            BigDecimal breadth,
            BigDecimal height,
            BigDecimal contents,
            boolean deduction,
            Integer barDia) {
    }

    public record CreateSheetRequest(
            @NotNull UUID projectId,
            /** Optional: a BILLING_ONLY project has exactly one site and the caller need not know it. */
            UUID siteId,
            @NotNull UUID boqItemId,
            /** Pre-printed on the paper. Null for a sheet typed with no page behind it. */
            @Size(max = 40) String sheetSerial,
            MeasurementSheet.SheetType sheetType,
            @NotNull LocalDate measuredOn,
            @Size(max = 2000) String locationNote,
            /** The total he worked out at the foot of the page. */
            BigDecimal writtenTotal,
            /** Sections only: the tested kg/m the summed length is taken against. */
            @DecimalMin("0") BigDecimal unitWeight,
            UUID attachmentId,
            @Size(max = 2000) String remarks,
            @Valid List<MeasurementLineInput> lines) {
    }

    public record UpdateSheetRequest(
            @Size(max = 40) String sheetSerial,
            @NotNull LocalDate measuredOn,
            @Size(max = 2000) String locationNote,
            BigDecimal writtenTotal,
            @DecimalMin("0") BigDecimal unitWeight,
            UUID attachmentId,
            @Size(max = 2000) String remarks,
            @Valid List<MeasurementLineInput> lines,
            @NotNull Long version) {
    }

    /**
     * @param computedTotal what the rows come to
     * @param claimedQuantity the same figure, or that figure times the unit weight for a
     *                        section sheet — the quantity that actually reaches the bill
     * @param totalsAgree     whether his written total and the computed one match. False
     *                        blocks signing, which is the whole point of copying the paper
     *                        faithfully.
     */
    public record SheetResponse(
            UUID id,
            UUID projectId,
            UUID siteId,
            UUID boqItemId,
            String itemNumber,
            String itemDescription,
            String sheetSerial,
            MeasurementSheet.SheetType sheetType,
            LocalDate measuredOn,
            UUID measuredBy,
            String locationNote,
            BigDecimal writtenTotal,
            BigDecimal computedTotal,
            BigDecimal claimedQuantity,
            boolean totalsAgree,
            BigDecimal unitWeight,
            MeasurementSheet.Status status,
            Instant signedAt,
            UUID signedBy,
            UUID raBillId,
            UUID attachmentId,
            String remarks,
            Long version,
            List<MeasurementLineResponse> lines) {
    }

    // ------------------------------------------------------------------ the unbilled queue

    /** One item's worth of measured-but-unbilled work — the queue the engineer lives in. */
    public record UnbilledItem(
            UUID boqItemId,
            String itemNumber,
            String description,
            BigDecimal contractQuantity,
            BigDecimal quantityPaidToDate,
            BigDecimal quantityUnbilled,
            BigDecimal rate,
            BigDecimal valueUnbilled,
            int sheetCount) {
    }

    /**
     * @param overClaimed items whose measured total now exceeds the contract quantity. Not
     *                    refused — a deviation is a real thing and the department decides it —
     *                    but never silent either.
     */
    public record UnbilledSummary(
            UUID projectId,
            LocalDate cutoffDate,
            List<UnbilledItem> items,
            BigDecimal totalValue,
            int sheetCount,
            List<String> overClaimed) {
    }

    // ------------------------------------------------------------------ bills

    public record CreateBillRequest(
            @NotNull UUID projectId,
            @NotNull LocalDate cutoffDate,
            @Size(max = 120) String title,
            @Size(max = 2000) String remarks) {
    }

    public record BillItemResponse(
            UUID boqItemId,
            String itemNumber,
            String description,
            BigDecimal contractQuantity,
            BigDecimal qtySincePrevious,
            BigDecimal qtyToDate,
            BigDecimal rate,
            BigDecimal amountToDate,
            BigDecimal amountPrevious,
            BigDecimal amountSince,
            int sortOrder) {
    }

    /**
     * @param frozen true once the bill has been passed, in which case the items are the
     *               snapshot taken at that moment rather than anything computed now
     */
    public record BillResponse(
            UUID id,
            UUID projectId,
            int serialNo,
            String title,
            LocalDate cutoffDate,
            UUID previousBillId,
            RaBill.Status status,
            boolean frozen,
            Instant frozenAt,
            UUID frozenBy,
            BigDecimal grossWorkDone,
            BigDecimal valueSincePrevious,
            int revision,
            String remarks,
            Long version,
            List<BillItemResponse> items) {
    }

    public record BillSummary(
            UUID id,
            UUID projectId,
            int serialNo,
            String title,
            LocalDate cutoffDate,
            RaBill.Status status,
            BigDecimal grossWorkDone,
            int revision) {
    }

    public record DecideBillRequest(
            @NotNull Action action,
            @Size(max = 2000) String remarks) {

        public enum Action { SUBMIT, CHECK, PASS, REOPEN }
    }

    // ------------------------------------------------------------------ the agreement

    public record AgreementRequest(
            @Size(max = 120) String agreementNo,
            @Size(max = 200) String division,
            @Size(max = 200) String subDivision,
            UUID dsrScheduleId,
            @NotNull @DecimalMin("0") BigDecimal dsrCoefficient,
            @NotNull BigDecimal costIndexPct,
            /** Signed. Negative is a tender below the schedule, which is the usual direction. */
            @NotNull BigDecimal tenderPct,
            @NotNull @DecimalMin("0") BigDecimal deviationLimitPct,
            /* Asked once at NIT import, printed on every page of every bill for this tender. */
            @Size(max = 200) String contractorName,
            @Size(max = 200) String measuredByName,
            @Size(max = 120) String measuredByDesignation,
            @Size(max = 200) String preparedByName,
            @Size(max = 120) String preparedByDesignation,
            @Size(max = 200) String checkedByName,
            @Size(max = 120) String checkedByDesignation,
            @Size(max = 200) String executiveEngineer,
            @Size(max = 60) String cmbNo,
            BigDecimal estimatedCost,
            BigDecimal tenderedCost,
            LocalDate dateOfStart,
            LocalDate stipulatedCompletion) {
    }

    /**
     * @param sampleDerivation what the chain does to a rate of 1000, so the screen can show
     *                         the arithmetic rather than assert the answer
     */
    public record AgreementResponse(
            UUID id,
            UUID projectId,
            String agreementNo,
            String division,
            String subDivision,
            UUID dsrScheduleId,
            BigDecimal dsrCoefficient,
            BigDecimal costIndexPct,
            BigDecimal tenderPct,
            BigDecimal deviationLimitPct,
            String contractorName,
            String measuredByName,
            String measuredByDesignation,
            String preparedByName,
            String preparedByDesignation,
            String checkedByName,
            String checkedByDesignation,
            String executiveEngineer,
            String cmbNo,
            BigDecimal estimatedCost,
            BigDecimal tenderedCost,
            LocalDate dateOfStart,
            LocalDate stipulatedCompletion,
            BigDecimal sampleDerivation,
            Long version) {
    }
}

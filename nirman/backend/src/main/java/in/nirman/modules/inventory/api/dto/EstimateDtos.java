package in.nirman.modules.inventory.api.dto;

import in.nirman.modules.inventory.domain.MaterialEstimate.Level;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Material estimates, and the estimated-versus-actual comparison they exist for. */
public final class EstimateDtos {

    private EstimateDtos() {
    }

    // ------------------------------------------------------------------ estimates

    /**
     * @param boqItemId null means the estimate covers the whole project. Naming an item is
     *                  what makes a scoped variance possible later, so the screen should
     *                  push for it.
     */
    public record SaveEstimateRequest(
            @NotNull UUID projectId,
            UUID siteId,
            UUID boqItemId,
            @NotNull UUID materialId,
            @NotNull Level estimateLevel,
            @NotNull @DecimalMin("0") BigDecimal estimatedQtyBase,
            @DecimalMin("0") @DecimalMax("100") BigDecimal wastagePercent,
            LocalDate effectiveFrom,
            @Size(max = 120) String sourceRef,
            String notes) {
    }

    /**
     * Derives an estimate from a consumption coefficient instead of a take-off: the BOQ
     * line says 5.94 cum of RCC M25, the norm says 5.0 bags of cement per cum, and the
     * estimate that comes out says 29.7 bags.
     */
    public record DeriveEstimateRequest(
            @NotNull UUID boqItemId,
            @NotNull UUID materialId,
            @Size(max = 80) String workSubType,
            @DecimalMin("0") @DecimalMax("100") BigDecimal wastagePercent,
            @NotNull Level estimateLevel) {
    }

    public record EstimateResponse(
            UUID id,
            UUID projectId,
            UUID siteId,
            UUID boqItemId,
            String boqItemNumber,
            UUID materialId,
            String materialCode,
            String materialName,
            String baseUnitCode,
            Level estimateLevel,
            BigDecimal estimatedQtyBase,
            BigDecimal wastagePercent,
            BigDecimal qtyWithWastage,
            int revision,
            LocalDate effectiveFrom,
            Instant supersededAt,
            String sourceRef,
            boolean derivedFromNorm,
            String notes,
            Long version) {
    }

    // ------------------------------------------------------------------ variance

    /** One BOQ line inside the scope an estimate covers. */
    public record ScopeLine(
            UUID boqItemId,
            String itemNumber,
            String description,
            BigDecimal estimatedQty,
            BigDecimal issuedQty,
            BigDecimal variance) {
    }

    /**
     * Consumption that happened outside anything the estimate covers, itemised rather than
     * folded into the variance.
     *
     * @param boqItemId null means the issue named no work item at all
     */
    public record UncoveredLine(
            UUID boqItemId,
            String itemNumber,
            String description,
            BigDecimal issuedQty) {
    }

    /**
     * Estimated versus actual for one material, computed <b>only over the BOQ scope the
     * estimate actually spans</b>.
     *
     * <p>This is the shape docs/09 section B insists on, and the reason it is not simply
     * two numbers and a percentage. The Kausani cement take-off totals 263 bags against 460
     * received. That looks like a 75% overrun and is nothing of the kind: the take-off
     * covered RCC and two brickwork lines and never touched plaster, flooring or the rest
     * of the masonry. Report it as a 75% overrun and the engineer stops trusting the
     * dashboard on day one.</p>
     *
     * <p>So {@code variance} is measured across {@code scope} alone, and everything outside
     * it is reported beside that figure rather than inside it. {@code scopeComplete} says
     * in one boolean whether the comparison can be read as the whole story.</p>
     *
     * @param receivedQty   the full chain from docs/09: estimated → received → issued →
     *                      wasted → balance. Received, wasted and balance fall out of the
     *                      ledger; issued is the one the site has to actually record.
     */
    public record MaterialVariance(
            UUID materialId,
            String materialCode,
            String materialName,
            String baseUnitCode,
            Level estimateLevel,
            BigDecimal estimatedQty,
            BigDecimal issuedWithinScope,
            BigDecimal variance,
            BigDecimal variancePercent,
            boolean scopeComplete,
            BigDecimal issuedOutsideScope,
            BigDecimal issuedWithoutBoqItem,
            int boqItemsCovered,
            int boqItemsWithConsumption,
            BigDecimal receivedQty,
            BigDecimal wastedQty,
            BigDecimal balanceQty,
            List<ScopeLine> scope,
            List<UncoveredLine> uncovered) {
    }

    /**
     * @param caveat plain-English statement of what the variance does and does not cover,
     *               written server-side so every client says the same thing about it
     */
    public record VarianceReport(
            UUID projectId,
            String projectName,
            Level estimateLevel,
            List<MaterialVariance> materials,
            String caveat) {
    }
}

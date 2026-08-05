package in.nirman.modules.inventory.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.inventory.api.dto.EstimateDtos.DeriveEstimateRequest;
import in.nirman.modules.inventory.api.dto.EstimateDtos.EstimateResponse;
import in.nirman.modules.inventory.api.dto.EstimateDtos.MaterialVariance;
import in.nirman.modules.inventory.api.dto.EstimateDtos.SaveEstimateRequest;
import in.nirman.modules.inventory.api.dto.EstimateDtos.ScopeLine;
import in.nirman.modules.inventory.api.dto.EstimateDtos.UncoveredLine;
import in.nirman.modules.inventory.api.dto.EstimateDtos.VarianceReport;
import in.nirman.modules.inventory.domain.MaterialConsumptionNorm;
import in.nirman.modules.inventory.domain.MaterialEstimate;
import in.nirman.modules.inventory.domain.MaterialEstimate.Level;
import in.nirman.modules.inventory.domain.StockTransaction;
import in.nirman.modules.inventory.domain.StockTransaction.TxnType;
import in.nirman.modules.inventory.repository.MaterialConsumptionNormRepository;
import in.nirman.modules.inventory.repository.MaterialEstimateRepository;
import in.nirman.modules.inventory.repository.StockTransactionRepository;
import in.nirman.modules.masterdata.service.MaterialLookup;
import in.nirman.modules.masterdata.service.MaterialLookup.MaterialInfo;
import in.nirman.modules.project.repository.ProjectRepository;
import in.nirman.modules.project.service.BoqLookup;
import in.nirman.security.CurrentUserProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Material estimates, and the estimated-versus-actual comparison they exist for.
 *
 * <h2>The completeness trap, and the rule that avoids it</h2>
 *
 * <p>The Kausani cement take-off totals 263 bags. 460 were received. Reported naively that
 * is a 75% overrun, and it is nothing of the kind: the take-off covered RCC and two
 * brickwork lines and never touched plaster, flooring or the rest of the masonry. Ship that
 * number and the first dashboard an engineer sees tells him he wasted 197 bags of cement he
 * did not waste. He stops trusting it, and he does not come back (docs/09 section B).</p>
 *
 * <p>So the variance here is computed <b>only over the BOQ scope the estimate actually
 * spans</b>. Consumption charged to work items nobody estimated, and consumption charged to
 * no work item at all, are reported <i>beside</i> the variance rather than inside it, and
 * {@code scopeComplete} says in one flag whether the comparison can be read as the whole
 * story. Steel is the clean case, because a bar bending schedule covers the whole
 * structure; cement usually is not, and the report has to say so rather than pretend.</p>
 *
 * <p>A project-wide estimate — one with no BOQ item — is the exception: it claims to cover
 * everything, so everything is in scope and nothing can fall outside it.</p>
 */
@Service
@Transactional
public class MaterialEstimateService {

    /** Movements that count as consumption. A transfer out is not consumed, it is relocated. */
    private static final Set<TxnType> CONSUMPTION =
            Set.of(TxnType.ISSUE, TxnType.WASTAGE, TxnType.DAMAGE);
    private static final Set<TxnType> ISSUED = Set.of(TxnType.ISSUE);
    private static final Set<TxnType> WASTED = Set.of(TxnType.WASTAGE, TxnType.DAMAGE);
    private static final Set<TxnType> RECEIVED = Set.of(TxnType.RECEIPT, TxnType.OPENING_STOCK);

    private static final Set<TxnType> ALL_TRACKED = Set.of(TxnType.ISSUE, TxnType.WASTAGE,
            TxnType.DAMAGE, TxnType.RECEIPT, TxnType.OPENING_STOCK);

    private final MaterialEstimateRepository estimates;
    private final MaterialConsumptionNormRepository norms;
    private final StockTransactionRepository transactions;
    private final MaterialLookup materials;
    private final BoqLookup boqItems;
    private final ProjectRepository projects;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public MaterialEstimateService(MaterialEstimateRepository estimates,
                                   MaterialConsumptionNormRepository norms,
                                   StockTransactionRepository transactions,
                                   MaterialLookup materials, BoqLookup boqItems,
                                   ProjectRepository projects, CurrentUserProvider currentUser,
                                   AuditService audit) {
        this.estimates = estimates;
        this.norms = norms;
        this.transactions = transactions;
        this.materials = materials;
        this.boqItems = boqItems;
        this.projects = projects;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ estimates

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('boq:read')")
    public List<EstimateResponse> list(UUID projectId, UUID materialId, Level level) {
        Map<UUID, BoqLookup.BoqItemInfo> items = boqItems.forProject(projectId).stream()
                .collect(Collectors.toMap(BoqLookup.BoqItemInfo::id, item -> item));
        List<MaterialEstimate> live = estimates.findLiveForProject(orgId(), projectId,
                materialId, level);
        Map<UUID, MaterialInfo> byMaterial = materials.byIds(live.stream()
                .map(MaterialEstimate::getMaterialId).collect(Collectors.toSet()));
        return live.stream().map(estimate -> toResponse(estimate, byMaterial, items)).toList();
    }

    /**
     * Records an estimate, superseding whatever revision it replaces.
     *
     * <p>A revision never overwrites: the old row stays readable with a
     * {@code superseded_at} on it, so a variance quoted last month remains reproducible.
     * Same rule as freezing a wage at verification, for the same reason — a number somebody
     * acted on must not silently become a different number.</p>
     */
    @PreAuthorize("hasAuthority('boq:write')")
    public EstimateResponse save(SaveEstimateRequest request) {
        projects.findByIdAndOrgIdAndDeletedAtIsNull(request.projectId(), orgId())
                .orElseThrow(() -> BusinessException.notFound("Project", request.projectId()));
        if (request.boqItemId() != null) {
            BoqLookup.BoqItemInfo item = boqItems.requireChargeable(request.boqItemId());
            if (!item.projectId().equals(request.projectId())) {
                throw new BusinessException("estimate.boq-other-project",
                        "Item " + item.itemNumber() + " belongs to a different project.");
            }
        }
        materials.require(request.materialId());

        int revision = 1;
        var previous = estimates.findLive(orgId(), request.projectId(), request.boqItemId(),
                request.materialId(), request.estimateLevel());
        if (previous.isPresent()) {
            previous.get().supersede(Instant.now());
            revision = previous.get().getRevision() + 1;
            // Flushed before the replacement is inserted. Hibernate orders inserts ahead of
            // updates within a flush, so without this the new row meets the old one under
            // uq_material_estimate_live and a legitimate revision comes back as a conflict.
            estimates.flush();
        }

        MaterialEstimate estimate = new MaterialEstimate(orgId(), request.projectId(),
                request.siteId(), request.boqItemId(), request.materialId(),
                request.estimateLevel(), request.estimatedQtyBase(), request.wastagePercent(),
                request.effectiveFrom());
        estimate.setRevision(revision);
        estimate.setSourceRef(request.sourceRef());
        estimate.setNotes(request.notes());
        estimates.save(estimate);

        audit.record("MATERIAL_ESTIMATE", estimate.getId(), previous.isPresent() ? "REVISE" : "CREATE",
                null, Map.of("materialId", request.materialId().toString(),
                        "level", request.estimateLevel().name(),
                        "quantity", request.estimatedQtyBase(), "revision", revision), null);
        return toResponse(estimate, materials.byIds(List.of(request.materialId())),
                boqItemMap(request.boqItemId()));
    }

    /**
     * Derives an estimate from a consumption coefficient rather than a take-off: the BOQ
     * line says 5.94 cum of RCC M25, the norm says 5.0 bags of cement per cum, and what
     * comes out is 29.7 bags.
     *
     * <p>This is the bridge docs/09 section B is about. A NIT gives work quantities, never
     * material quantities, and the field applies these coefficients by hand today.</p>
     */
    @PreAuthorize("hasAuthority('boq:write')")
    public EstimateResponse derive(DeriveEstimateRequest request) {
        BoqLookup.BoqItemInfo item = boqItems.requireChargeable(request.boqItemId());
        if (item.category() == null) {
            throw new BusinessException("estimate.no-category",
                    "Item " + item.itemNumber() + " has no work category, so no consumption "
                            + "norm can be matched to it. Set one on the BOQ item first.");
        }
        MaterialConsumptionNorm norm = norms
                .findNorm(orgId(), item.category(), request.workSubType(), request.materialId())
                .or(() -> norms.findNorm(orgId(), item.category(), null, request.materialId()))
                .orElseThrow(() -> new BusinessException("estimate.no-norm",
                        "No consumption norm for %s in %s. Add one, or enter the estimate by hand."
                                .formatted(materials.require(request.materialId()).name(),
                                        item.category())));

        BigDecimal derived = norm.applyTo(item.contractQuantity());
        SaveEstimateRequest asSave = new SaveEstimateRequest(item.projectId(), item.siteId(),
                item.id(), request.materialId(), request.estimateLevel(), derived,
                request.wastagePercent(), null,
                "Norm %s / %s".formatted(norm.getWorkCategory(),
                        norm.getWorkSubType() == null ? "any" : norm.getWorkSubType()),
                "Derived from %s per %s of work".formatted(
                        norm.getQtyPerWorkUnit().toPlainString(), item.itemNumber()));
        EstimateResponse saved = save(asSave);

        estimates.findByIdAndOrgId(saved.id(), orgId())
                .ifPresent(estimate -> estimate.setDerivedFromNorm(true));
        return saved;
    }

    // ------------------------------------------------------------------ variance

    /**
     * Estimated versus actual for a project, one row per material, honest about its own
     * coverage.
     *
     * @param level which estimate to compare against — what was tendered, or what the
     *              drawings actually demand. Collapsing the two would hide the most
     *              commercially useful number available.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('report:operational')")
    public VarianceReport variance(UUID projectId, Level level, UUID materialId) {
        String projectName = projects.findByIdAndOrgIdAndDeletedAtIsNull(projectId, orgId())
                .orElseThrow(() -> BusinessException.notFound("Project", projectId))
                .getName();

        List<MaterialEstimate> live = estimates.findLiveForProject(orgId(), projectId,
                materialId, level);
        Map<UUID, BoqLookup.BoqItemInfo> items = boqItems.forProject(projectId).stream()
                .collect(Collectors.toMap(BoqLookup.BoqItemInfo::id, item -> item));
        Map<UUID, MaterialInfo> byMaterial = materials.byIds(live.stream()
                .map(MaterialEstimate::getMaterialId).collect(Collectors.toSet()));

        List<MaterialVariance> rows = live.stream()
                .collect(Collectors.groupingBy(MaterialEstimate::getMaterialId))
                .entrySet().stream()
                .map(entry -> varianceFor(projectId, entry.getKey(), entry.getValue(),
                        byMaterial.get(entry.getKey()), items, level))
                .sorted(Comparator.comparing(row ->
                        row.materialCode() == null ? "" : row.materialCode()))
                .toList();

        boolean anyIncomplete = rows.stream().anyMatch(row -> !row.scopeComplete());
        return new VarianceReport(projectId, projectName, level, rows, caveat(anyIncomplete));
    }

    private MaterialVariance varianceFor(UUID projectId, UUID materialId,
                                         List<MaterialEstimate> forMaterial, MaterialInfo material,
                                         Map<UUID, BoqLookup.BoqItemInfo> items, Level level) {
        // A project-wide estimate claims the whole job. Anything scoped to a BOQ item claims
        // only that item, and the difference is what makes the variance trustworthy.
        boolean projectWide = forMaterial.stream()
                .anyMatch(estimate -> estimate.getBoqItemId() == null);
        Map<UUID, BigDecimal> estimatedByItem = forMaterial.stream()
                .filter(estimate -> estimate.getBoqItemId() != null)
                .collect(Collectors.toMap(MaterialEstimate::getBoqItemId,
                        MaterialEstimate::comparableQuantity, BigDecimal::add));
        BigDecimal estimatedTotal = forMaterial.stream()
                .map(MaterialEstimate::comparableQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<StockTransaction> movements = transactions.findForProjectMaterial(orgId(), projectId,
                materialId, ALL_TRACKED);

        Map<UUID, BigDecimal> issuedByItem = new HashMap<>();
        BigDecimal issuedUnallocated = BigDecimal.ZERO;
        BigDecimal issuedTotal = BigDecimal.ZERO;
        BigDecimal wasted = BigDecimal.ZERO;
        BigDecimal received = BigDecimal.ZERO;

        for (StockTransaction movement : movements) {
            BigDecimal qty = movement.getQuantityBase();
            if (RECEIVED.contains(movement.getTxnType())) {
                received = received.add(qty);
                continue;
            }
            if (WASTED.contains(movement.getTxnType())) {
                wasted = wasted.add(qty);
            }
            if (!CONSUMPTION.contains(movement.getTxnType())) {
                continue;
            }
            if (ISSUED.contains(movement.getTxnType())) {
                issuedTotal = issuedTotal.add(qty);
                if (movement.getBoqItemId() == null) {
                    issuedUnallocated = issuedUnallocated.add(qty);
                } else {
                    issuedByItem.merge(movement.getBoqItemId(), qty, BigDecimal::add);
                }
            }
        }

        Set<UUID> covered = projectWide ? issuedByItem.keySet() : estimatedByItem.keySet();
        BigDecimal issuedWithinScope = projectWide
                ? issuedTotal
                : covered.stream()
                        .map(id -> issuedByItem.getOrDefault(id, BigDecimal.ZERO))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<ScopeLine> scope = scopeLines(estimatedByItem, issuedByItem, items, projectWide);
        List<UncoveredLine> uncovered = projectWide
                ? List.of()
                : uncoveredLines(estimatedByItem.keySet(), issuedByItem, issuedUnallocated, items);

        BigDecimal issuedOutsideScope = uncovered.stream()
                .filter(line -> line.boqItemId() != null)
                .map(UncoveredLine::issuedQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unallocated = projectWide ? BigDecimal.ZERO : issuedUnallocated;

        BigDecimal variance = issuedWithinScope.subtract(estimatedTotal);
        boolean complete = projectWide
                || (issuedOutsideScope.signum() == 0 && unallocated.signum() == 0);

        return new MaterialVariance(materialId,
                material == null ? null : material.code(),
                material == null ? null : material.name(),
                material == null ? null : material.baseUnitCode(),
                level, estimatedTotal, issuedWithinScope, variance,
                percentOf(variance, estimatedTotal), complete, issuedOutsideScope, unallocated,
                projectWide ? items.size() : estimatedByItem.size(), issuedByItem.size(),
                received, wasted, received.subtract(issuedTotal).subtract(wasted),
                scope, uncovered);
    }

    private static List<ScopeLine> scopeLines(Map<UUID, BigDecimal> estimatedByItem,
                                              Map<UUID, BigDecimal> issuedByItem,
                                              Map<UUID, BoqLookup.BoqItemInfo> items,
                                              boolean projectWide) {
        if (projectWide) {
            return List.of();   // the scope is the whole project; there is nothing to itemise
        }
        return estimatedByItem.entrySet().stream()
                .map(entry -> {
                    BoqLookup.BoqItemInfo item = items.get(entry.getKey());
                    BigDecimal issued = issuedByItem.getOrDefault(entry.getKey(), BigDecimal.ZERO);
                    return new ScopeLine(entry.getKey(),
                            item == null ? null : item.itemNumber(),
                            item == null ? null : item.description(),
                            entry.getValue(), issued, issued.subtract(entry.getValue()));
                })
                .sorted(Comparator.comparing(line ->
                        line.itemNumber() == null ? "" : line.itemNumber()))
                .toList();
    }

    /**
     * Consumption the estimate never claimed to cover — this is the half that keeps the
     * variance honest. Itemised rather than totalled, because "197 bags went somewhere
     * else" is an accusation and "197 bags went into plaster, flooring and the boundary
     * wall, none of which were estimated" is an explanation.
     */
    private static List<UncoveredLine> uncoveredLines(Set<UUID> coveredItems,
                                                      Map<UUID, BigDecimal> issuedByItem,
                                                      BigDecimal issuedUnallocated,
                                                      Map<UUID, BoqLookup.BoqItemInfo> items) {
        List<UncoveredLine> rows = new ArrayList<>();
        Set<UUID> outside = new HashSet<>(issuedByItem.keySet());
        outside.removeAll(coveredItems);
        outside.stream()
                .sorted(Comparator.comparing(id -> {
                    BoqLookup.BoqItemInfo item = items.get(id);
                    return item == null ? "" : item.itemNumber();
                }))
                .forEach(id -> {
                    BoqLookup.BoqItemInfo item = items.get(id);
                    rows.add(new UncoveredLine(id,
                            item == null ? null : item.itemNumber(),
                            item == null ? null : item.description(),
                            issuedByItem.get(id)));
                });
        if (issuedUnallocated.signum() > 0) {
            rows.add(new UncoveredLine(null, null,
                    "Issued without a work item", issuedUnallocated));
        }
        return rows;
    }

    /**
     * The sentence that goes with the numbers, written once here so every client says the
     * same thing about what the comparison covers.
     */
    private static String caveat(boolean anyIncomplete) {
        return anyIncomplete
                ? "Variance is measured only over the BOQ items the estimate covers. Material "
                + "consumed against other items, or against none, is listed separately and is "
                + "not an overrun."
                : "Every recorded consumption falls inside the estimated scope, so the variance "
                + "is the whole picture for these materials.";
    }

    private static BigDecimal percentOf(BigDecimal variance, BigDecimal estimated) {
        if (estimated.signum() == 0) {
            return null;   // no estimate is not a 100% overrun, it is an unanswerable question
        }
        return variance.multiply(BigDecimal.valueOf(100))
                .divide(estimated, 2, RoundingMode.HALF_UP);
    }

    private Map<UUID, BoqLookup.BoqItemInfo> boqItemMap(UUID boqItemId) {
        return boqItemId == null ? Map.of() : boqItems.byIds(List.of(boqItemId));
    }

    private static EstimateResponse toResponse(MaterialEstimate estimate,
                                               Map<UUID, MaterialInfo> byMaterial,
                                               Map<UUID, BoqLookup.BoqItemInfo> items) {
        MaterialInfo material = byMaterial.get(estimate.getMaterialId());
        BoqLookup.BoqItemInfo item = estimate.getBoqItemId() == null
                ? null : items.get(estimate.getBoqItemId());
        return new EstimateResponse(estimate.getId(), estimate.getProjectId(), estimate.getSiteId(),
                estimate.getBoqItemId(), item == null ? null : item.itemNumber(),
                estimate.getMaterialId(),
                material == null ? null : material.code(),
                material == null ? null : material.name(),
                material == null ? null : material.baseUnitCode(),
                estimate.getEstimateLevel(), estimate.getEstimatedQtyBase(),
                estimate.getWastagePercent(), estimate.getQtyWithWastage(), estimate.getRevision(),
                estimate.getEffectiveFrom(), estimate.getSupersededAt(), estimate.getSourceRef(),
                estimate.isDerivedFromNorm(), estimate.getNotes(), estimate.getVersion());
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }
}

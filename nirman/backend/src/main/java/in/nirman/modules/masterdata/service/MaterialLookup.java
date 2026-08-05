package in.nirman.modules.masterdata.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The master-data module's public read API for materials, in the shape inventory needs it.
 *
 * <p>The same boundary {@link in.nirman.modules.project.service.SiteLookup} draws for
 * sites: inventory needs a handful of facts about a material — what its base unit is, what
 * an entered unit converts to, what its reorder level is — and gets them here rather than
 * reaching into {@code MaterialRepository}.</p>
 *
 * <p>Deliberately unguarded by a permission. Every role holds {@code masterdata:read}, and
 * the caller has already been checked for the inventory permission that let it get this
 * far; making a unit conversion an authorisation decision would only mean a supervisor
 * cannot convert quintals to kilograms while booking a delivery he is allowed to book.</p>
 */
public interface MaterialLookup {

    /**
     * @param baseUnitCode   what the ledger counts in — BAG for cement, KG for steel
     * @param minStockLevel  the reorder level, in base units, for the low-stock report
     */
    record MaterialInfo(
            UUID id,
            String code,
            String name,
            UUID baseUnitId,
            String baseUnitCode,
            BigDecimal minStockLevel,
            BigDecimal gstPercent,
            boolean active) {
    }

    /** @throws in.nirman.common.BusinessException 404 if no such live material in the org */
    MaterialInfo require(UUID materialId);

    /** Bulk form for the screens and reports that need thirty materials named at once. */
    Map<UUID, MaterialInfo> byIds(Collection<UUID> materialIds);

    /**
     * How many base units one of {@code unitId} makes: 1000 for a tonne of a material
     * stocked in kilograms, 1 when the unit given <i>is</i> the base unit.
     *
     * @throws in.nirman.common.BusinessException 422 when the material has no conversion for
     *         that unit — booking steel in cubic metres is a mistake, not a rounding problem
     */
    BigDecimal factorToBase(UUID materialId, UUID unitId);

    /** Unit codes by id, so a document can be shown in the unit it was actually entered in. */
    Map<UUID, String> unitCodes(Collection<UUID> unitIds);
}

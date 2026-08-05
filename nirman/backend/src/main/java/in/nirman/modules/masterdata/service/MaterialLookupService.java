package in.nirman.modules.masterdata.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.masterdata.domain.Material;
import in.nirman.modules.masterdata.domain.MaterialUnitConversion;
import in.nirman.modules.masterdata.domain.Unit;
import in.nirman.modules.masterdata.repository.MaterialRepository;
import in.nirman.modules.masterdata.repository.MaterialUnitConversionRepository;
import in.nirman.modules.masterdata.repository.UnitRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link MaterialLookup}, kept apart from {@link MasterDataService} rather than folded into
 * it.
 *
 * <p>{@code MasterDataService} carries a class-level {@code masterdata:read} check because
 * it serves the master-data screens. This does not, and must not: it answers arithmetic
 * questions — what does a tonne of this come to in kilograms — for a caller that has
 * already been checked for the inventory permission that got it this far. Gating a unit
 * conversion behind a second permission would mean a storekeeper cannot book a delivery he
 * is allowed to book.</p>
 */
@Service
@Transactional(readOnly = true)
public class MaterialLookupService implements MaterialLookup {

    private final MaterialRepository materials;
    private final MaterialUnitConversionRepository conversions;
    private final UnitRepository units;
    private final CurrentUserProvider currentUser;

    public MaterialLookupService(MaterialRepository materials,
                                 MaterialUnitConversionRepository conversions,
                                 UnitRepository units, CurrentUserProvider currentUser) {
        this.materials = materials;
        this.conversions = conversions;
        this.units = units;
        this.currentUser = currentUser;
    }

    @Override
    public MaterialInfo require(UUID materialId) {
        Material material = materials
                .findByIdAndOrgIdAndDeletedAtIsNull(materialId, currentUser.currentOrgId())
                .orElseThrow(() -> BusinessException.notFound("Material", materialId));
        return toInfo(material, unitCode(material.getBaseUnitId()));
    }

    @Override
    public Map<UUID, MaterialInfo> byIds(Collection<UUID> materialIds) {
        if (materialIds.isEmpty()) {
            return Map.of();
        }
        List<Material> found = materials.findAllById(materialIds).stream()
                .filter(m -> m.getOrgId().equals(currentUser.currentOrgId()))
                .toList();
        Map<UUID, String> codes = unitCodes(
                found.stream().map(Material::getBaseUnitId).collect(Collectors.toSet()));
        return found.stream().collect(Collectors.toMap(Material::getId,
                m -> toInfo(m, codes.get(m.getBaseUnitId()))));
    }

    /**
     * {@inheritDoc}
     *
     * <p>A missing conversion is a business rejection rather than a silent 1.0. Assuming
     * parity would let somebody book five cubic metres of steel and have the ledger record
     * five kilograms, which is the kind of error nobody finds until a stock count.</p>
     */
    @Override
    public BigDecimal factorToBase(UUID materialId, UUID unitId) {
        MaterialInfo material = require(materialId);
        if (material.baseUnitId().equals(unitId)) {
            return BigDecimal.ONE;
        }
        return conversions.findByMaterialId(materialId).stream()
                .filter(c -> c.getAltUnitId().equals(unitId))
                .map(MaterialUnitConversion::getFactorToBase)
                .findFirst()
                .orElseThrow(() -> new BusinessException("material.no-conversion",
                        "%s is stocked in %s and has no conversion for the unit given. Add one "
                                .formatted(material.name(), material.baseUnitCode())
                                + "on the material before booking it in that unit."));
    }

    @Override
    public Map<UUID, String> unitCodes(Collection<UUID> unitIds) {
        if (unitIds.isEmpty()) {
            return Map.of();
        }
        return units.findAllById(unitIds).stream()
                .collect(Collectors.toMap(Unit::getId, Unit::getCode, (a, b) -> a));
    }

    private String unitCode(UUID unitId) {
        return units.findById(unitId).map(Unit::getCode).orElse(null);
    }

    private static MaterialInfo toInfo(Material material, String baseUnitCode) {
        return new MaterialInfo(material.getId(), material.getCode(), material.getName(),
                material.getBaseUnitId(), baseUnitCode, material.getMinStockLevel(),
                material.getGstPercent(), material.isActive());
    }
}

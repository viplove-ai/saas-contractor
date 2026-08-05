package in.nirman.modules.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One alternative unit for a material. {@code factorToBase} converts one alt unit into
 * base units: cement base BAG with alt KG carries 0.02 (1 kg = 0.02 bag).
 */
@Entity
@Table(name = "material_unit_conversions")
public class MaterialUnitConversion {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;

    @Column(name = "alt_unit_id", nullable = false, updatable = false)
    private UUID altUnitId;

    @Column(name = "factor_to_base", nullable = false, precision = 18, scale = 8)
    private BigDecimal factorToBase;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected MaterialUnitConversion() {
    }

    public MaterialUnitConversion(UUID orgId, UUID materialId, UUID altUnitId, BigDecimal factorToBase) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.materialId = materialId;
        this.altUnitId = altUnitId;
        this.factorToBase = factorToBase;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getMaterialId() {
        return materialId;
    }

    public UUID getAltUnitId() {
        return altUnitId;
    }

    public BigDecimal getFactorToBase() {
        return factorToBase;
    }

    public void setFactorToBase(BigDecimal factorToBase) {
        this.factorToBase = factorToBase;
    }

    public Long getVersion() {
        return version;
    }
}

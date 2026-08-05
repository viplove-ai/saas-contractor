package in.nirman.modules.masterdata.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A stocked material. {@code baseUnitId} is the valuation unit — every ledger quantity is
 * stored in base units, whatever unit the delivery challan used.
 */
@Entity
@Table(name = "materials")
public class Material extends BaseEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "code", nullable = false, length = 40, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "base_unit_id", nullable = false, updatable = false)
    private UUID baseUnitId;

    @Column(name = "hsn_code", length = 10)
    private String hsnCode;

    @Column(name = "gst_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal gstPercent = BigDecimal.ZERO;

    @Column(name = "min_stock_level", nullable = false, precision = 18, scale = 4)
    private BigDecimal minStockLevel = BigDecimal.ZERO;

    @Column(name = "standard_rate", precision = 18, scale = 4)
    private BigDecimal standardRate;

    @Column(name = "preferred_vendor_id")
    private UUID preferredVendorId;

    @Column(name = "is_consumable", nullable = false)
    private boolean consumable = true;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Material() {
    }

    public Material(UUID orgId, String code, String name, UUID baseUnitId) {
        this.orgId = orgId;
        this.code = code;
        this.name = name;
        this.baseUnitId = baseUnitId;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    public UUID getBaseUnitId() {
        return baseUnitId;
    }

    public String getHsnCode() {
        return hsnCode;
    }

    public void setHsnCode(String hsnCode) {
        this.hsnCode = hsnCode;
    }

    public BigDecimal getGstPercent() {
        return gstPercent;
    }

    public void setGstPercent(BigDecimal gstPercent) {
        this.gstPercent = gstPercent;
    }

    public BigDecimal getMinStockLevel() {
        return minStockLevel;
    }

    public void setMinStockLevel(BigDecimal minStockLevel) {
        this.minStockLevel = minStockLevel;
    }

    public BigDecimal getStandardRate() {
        return standardRate;
    }

    public void setStandardRate(BigDecimal standardRate) {
        this.standardRate = standardRate;
    }

    public UUID getPreferredVendorId() {
        return preferredVendorId;
    }

    public void setPreferredVendorId(UUID preferredVendorId) {
        this.preferredVendorId = preferredVendorId;
    }

    public boolean isConsumable() {
        return consumable;
    }

    public void setConsumable(boolean consumable) {
        this.consumable = consumable;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}

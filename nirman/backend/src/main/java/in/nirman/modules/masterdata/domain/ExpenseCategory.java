package in.nirman.modules.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

/**
 * Two-level expense taxonomy. The two flags are the double-counting guards from docs/09:
 * {@code labourPayment} rows settle wages already costed through verified attendance, and
 * {@code materialPurchase} rows become inventory value rather than direct cost.
 */
@Entity
@Table(name = "expense_categories")
public class ExpenseCategory {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "code", nullable = false, length = 40, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "is_material_purchase", nullable = false)
    private boolean materialPurchase;

    @Column(name = "is_labour_payment", nullable = false)
    private boolean labourPayment;

    @Column(name = "requires_vendor", nullable = false)
    private boolean requiresVendor;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ExpenseCategory() {
    }

    public ExpenseCategory(UUID orgId, String code, String name, UUID parentId) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.code = code;
        this.name = name;
        this.parentId = parentId;
    }

    public UUID getId() {
        return id;
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

    public UUID getParentId() {
        return parentId;
    }

    public boolean isMaterialPurchase() {
        return materialPurchase;
    }

    public void setMaterialPurchase(boolean materialPurchase) {
        this.materialPurchase = materialPurchase;
    }

    public boolean isLabourPayment() {
        return labourPayment;
    }

    public void setLabourPayment(boolean labourPayment) {
        this.labourPayment = labourPayment;
    }

    public boolean isRequiresVendor() {
        return requiresVendor;
    }

    public void setRequiresVendor(boolean requiresVendor) {
        this.requiresVendor = requiresVendor;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Long getVersion() {
        return version;
    }
}

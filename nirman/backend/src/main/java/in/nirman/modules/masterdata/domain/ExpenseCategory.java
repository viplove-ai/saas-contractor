package in.nirman.modules.masterdata.domain;

import in.nirman.common.CostAllocation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

/**
 * Two-level expense taxonomy. The two flags are the double-counting guards from docs/09:
 * {@code labourPayment} rows settle wages already costed through verified attendance, and
 * {@code materialPurchase} rows become inventory value rather than direct cost.
 *
 * <p>{@code labourPayment} is a claim about a wage having been costed, not about the head
 * being a labour one, so the expense module reads it against the site: where the work is let
 * to a supplier there is no muster to have costed anything, and the bill counts as cost. The
 * flag stays a property of the head all the same — the same head is a settlement at a site
 * that keeps its own men.</p>
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

    /**
     * Whose cost rows under this head almost always are (V36). Staff salary and office
     * spending are the organisation's at every site, and asking the approver the same question
     * about every one of them is how the question stops being read.
     *
     * <p>Never {@code SPLIT}: a split is an amount, and an amount is a fact about one bill
     * rather than about a category. The column is a proposal — the approver decides.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_allocation", nullable = false, length = 10)
    private CostAllocation defaultAllocation = CostAllocation.SITE;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /**
     * Named from a site while booking an expense rather than set up by the office (V24). A
     * phrase off a bill, with neither of the two flags above decided — so the office can find
     * these rows and fold them into the real taxonomy.
     */
    @Column(name = "provisional", nullable = false)
    private boolean provisional;

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

    public CostAllocation getDefaultAllocation() {
        return defaultAllocation;
    }

    /** Refuses {@code SPLIT}: a split is an amount, and an amount is not a fact about a head. */
    public void setDefaultAllocation(CostAllocation defaultAllocation) {
        if (defaultAllocation != null && !defaultAllocation.isProposable()) {
            throw new IllegalArgumentException("a head cannot default to a split");
        }
        this.defaultAllocation = defaultAllocation == null ? CostAllocation.SITE
                : defaultAllocation;
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

    public boolean isProvisional() {
        return provisional;
    }

    public void setProvisional(boolean provisional) {
        this.provisional = provisional;
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

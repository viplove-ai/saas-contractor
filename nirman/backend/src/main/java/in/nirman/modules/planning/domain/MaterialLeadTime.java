package in.nirman.modules.planning.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * How far ahead a material has to be ordered, and how long it will keep once it arrives.
 *
 * <p>This is what separates the two curves a plan produces. The <b>requirement</b> curve answers
 * "what will be consumed in March"; the <b>procurement</b> curve answers "what has to be ordered
 * in January so March can happen". Conflating them is how a site runs out of cement while the
 * plan says it has plenty.</p>
 *
 * <p>{@link #getShelfLifeDays()} and {@link #isStorable()} are what stop the answer being "order
 * the whole job in month one". Cement keeps about three months in a dry shed; ready-mixed
 * concrete keeps ninety minutes. On a long job the same material is therefore ordered again and
 * again, and the plan has to say when.</p>
 */
@Entity
@Table(name = "material_lead_times")
public class MaterialLeadTime extends BaseEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;

    @Column(name = "lead_days", nullable = false)
    private int leadDays = 7;

    /** Held on top of the lead time, because a delivery late by its own average is still late. */
    @Column(name = "buffer_days", nullable = false)
    private int bufferDays = 3;

    /** Null means it does not deteriorate. */
    @Column(name = "shelf_life_days")
    private Integer shelfLifeDays;

    @Column(name = "storable", nullable = false)
    private boolean storable = true;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "notes")
    private String notes;

    protected MaterialLeadTime() {
    }

    public void reviseTo(Integer leadDays, Integer bufferDays, Integer shelfLifeDays,
                         Boolean storable, Boolean active, String notes) {
        if (leadDays != null) {
            this.leadDays = leadDays;
        }
        if (bufferDays != null) {
            this.bufferDays = bufferDays;
        }
        if (shelfLifeDays != null) {
            this.shelfLifeDays = shelfLifeDays;
        }
        if (storable != null) {
            this.storable = storable;
        }
        if (active != null) {
            this.active = active;
        }
        if (notes != null) {
            this.notes = notes;
        }
    }

    /** How far before the need date an order has to be placed. */
    public int orderAheadDays() {
        return leadDays + bufferDays;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getMaterialId() {
        return materialId;
    }

    public int getLeadDays() {
        return leadDays;
    }

    public int getBufferDays() {
        return bufferDays;
    }

    public Integer getShelfLifeDays() {
        return shelfLifeDays;
    }

    public boolean isStorable() {
        return storable;
    }

    public boolean isActive() {
        return active;
    }

    public String getNotes() {
        return notes;
    }
}

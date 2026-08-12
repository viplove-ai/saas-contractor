package in.nirman.modules.labour.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A supplier who had men on a site on a day, and whether he was there with them.
 *
 * <p>Its own row rather than a column on {@link SiteLabourCount}, because a supplier who
 * sent masons, helpers and bar benders has three count rows and "was he here" answered three
 * times is a question that can contradict itself.</p>
 *
 * <p>It is worth writing down because it is what a site argues about afterwards. A gang left
 * to itself for a week is how work goes wrong, and "his man was never here" is a claim that
 * needs a day-by-day answer rather than a memory. <b>Still no money.</b> Nothing here is
 * multiplied by anything — the supplier bills for the work, and this is the site's note of
 * who turned up.</p>
 */
@Entity
@Table(name = "site_labour_supplier_days")
public class SiteLabourSupplierDay extends BaseEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private UUID siteId;

    @Column(name = "count_date", nullable = false, updatable = false)
    private LocalDate countDate;

    @Column(name = "labour_supplier_id", nullable = false, updatable = false)
    private UUID labourSupplierId;

    /** The supplier himself, or the man he sends to run the gang, was on site. */
    @Column(name = "supplier_present", nullable = false)
    private boolean supplierPresent;

    /**
     * Who that was, when it was not the supplier in person. A name is what turns "somebody
     * was here" into something anybody can check a fortnight later.
     */
    @Column(name = "representative_name", length = 150)
    private String representativeName;

    @Column(name = "remarks", length = 300)
    private String remarks;

    protected SiteLabourSupplierDay() {
    }

    public SiteLabourSupplierDay(UUID orgId, UUID siteId, LocalDate countDate,
                                 UUID labourSupplierId) {
        this.orgId = orgId;
        this.siteId = siteId;
        this.countDate = countDate;
        this.labourSupplierId = labourSupplierId;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getSiteId() {
        return siteId;
    }

    public LocalDate getCountDate() {
        return countDate;
    }

    public UUID getLabourSupplierId() {
        return labourSupplierId;
    }

    public boolean isSupplierPresent() {
        return supplierPresent;
    }

    public void setSupplierPresent(boolean supplierPresent) {
        this.supplierPresent = supplierPresent;
    }

    public String getRepresentativeName() {
        return representativeName;
    }

    public void setRepresentativeName(String representativeName) {
        this.representativeName = representativeName;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }
}

package in.nirman.modules.labour.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * How many of one trade stood on a site on one day, where nobody marks a muster roll.
 *
 * <p>This is what a site staffed by a labour contractor actually knows: eleven masons came,
 * six helpers came, they went home. There is no worker record, no wage rate and no
 * attendance — the contractor bills for the work, and the count is the site's own note of
 * what the day looked like. It reaches the daily report beside the muster-roll labour and
 * stops there: <b>no money is ever derived from it</b>, because a head count multiplied by
 * an assumed rate is a guess wearing the clothes of a figure.</p>
 *
 * <p>Zero is a real entry. "The bar benders did not come today" is worth writing down and
 * is not the same fact as leaving the trade off the list.</p>
 */
@Entity
@Table(name = "site_labour_counts")
public class SiteLabourCount extends BaseEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private UUID siteId;

    @Column(name = "count_date", nullable = false, updatable = false)
    private LocalDate countDate;

    @Column(name = "skill_category_id", nullable = false, updatable = false)
    private UUID skillCategoryId;

    @Column(name = "labour_supplier_id", updatable = false)
    private UUID labourSupplierId;

    @Column(name = "head_count", nullable = false)
    private int headCount;

    /**
     * Hours <b>each</b> man of this trade worked that day, not the gang's total — eleven
     * masons for eight hours is what the gate knows, and the man-hours are the product.
     *
     * <p>Null is not zero. A day where nobody recorded hours says nothing about how long the
     * men stood there, and printing that as "no hours" would be the report inventing a fact.
     * Still no money follows from it: there is no rate to multiply by and this class does not
     * look for one.</p>
     */
    @Column(name = "hours", precision = 4, scale = 2)
    private BigDecimal hours;

    @Column(name = "remarks", length = 300)
    private String remarks;

    protected SiteLabourCount() {
    }

    public SiteLabourCount(UUID orgId, UUID siteId, LocalDate countDate, UUID skillCategoryId,
                           UUID labourSupplierId, int headCount, BigDecimal hours,
                           String remarks) {
        this.orgId = orgId;
        this.siteId = siteId;
        this.countDate = countDate;
        this.skillCategoryId = skillCategoryId;
        this.labourSupplierId = labourSupplierId;
        this.headCount = headCount;
        this.hours = hours;
        this.remarks = remarks;
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

    public UUID getSkillCategoryId() {
        return skillCategoryId;
    }

    public UUID getLabourSupplierId() {
        return labourSupplierId;
    }

    public int getHeadCount() {
        return headCount;
    }

    public BigDecimal getHours() {
        return hours;
    }

    /** Head count times hours each, or null when nobody recorded hours. @see #hours */
    public BigDecimal manHours() {
        return hours == null ? null : hours.multiply(BigDecimal.valueOf(headCount));
    }

    public String getRemarks() {
        return remarks;
    }

    /**
     * The trade and the contractor are the row's identity, so a correction only ever moves
     * the count, the hours and the note. Re-entering the day replaces the numbers on the rows
     * that are already there rather than writing a second row for the same trade.
     */
    public void amend(int newHeadCount, BigDecimal newHours, String newRemarks) {
        this.headCount = newHeadCount;
        this.hours = newHours;
        this.remarks = newRemarks;
    }
}

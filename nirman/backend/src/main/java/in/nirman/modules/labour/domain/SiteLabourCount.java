package in.nirman.modules.labour.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

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

    @Column(name = "labour_contractor_id", updatable = false)
    private UUID labourContractorId;

    @Column(name = "head_count", nullable = false)
    private int headCount;

    @Column(name = "remarks", length = 300)
    private String remarks;

    protected SiteLabourCount() {
    }

    public SiteLabourCount(UUID orgId, UUID siteId, LocalDate countDate, UUID skillCategoryId,
                           UUID labourContractorId, int headCount, String remarks) {
        this.orgId = orgId;
        this.siteId = siteId;
        this.countDate = countDate;
        this.skillCategoryId = skillCategoryId;
        this.labourContractorId = labourContractorId;
        this.headCount = headCount;
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

    public UUID getLabourContractorId() {
        return labourContractorId;
    }

    public int getHeadCount() {
        return headCount;
    }

    public String getRemarks() {
        return remarks;
    }

    /**
     * The trade and the contractor are the row's identity, so a correction only ever moves
     * the count and the note. Re-entering the day replaces the numbers on the rows that are
     * already there rather than writing a second row for the same trade.
     */
    public void amend(int newHeadCount, String newRemarks) {
        this.headCount = newHeadCount;
        this.remarks = newRemarks;
    }
}

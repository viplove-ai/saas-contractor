package in.nirman.modules.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One priced row of a published schedule of rates — {@code 15.7.4}, and what it costs.
 *
 * <p>{@code confirmed} is false while the figure is only what an importer read. The review
 * screen shows those first, and a schedule cannot be published while any remain: a rate
 * nobody has looked at is the one input that can be wrong in every line of a bill at once.</p>
 *
 * <p>{@code unitText} keeps what the schedule actually printed even after the unit is
 * resolved, because a schedule prices work in units an organisation's master data has never
 * heard of — {@code per bag of 50 kg cement used} is a real one.</p>
 */
@Entity
@Table(name = "dsr_items")
public class DsrItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "schedule_id", nullable = false, updatable = false)
    private UUID scheduleId;

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "unit_id")
    private UUID unitId;

    @Column(name = "unit_text", length = 40)
    private String unitText;

    @Column(name = "rate", nullable = false, precision = 18, scale = 4)
    private BigDecimal rate;

    @Column(name = "chapter", length = 120)
    private String chapter;

    @Column(name = "page_no")
    private Integer pageNo;

    @Column(name = "confirmed", nullable = false)
    private boolean confirmed;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected DsrItem() {
    }

    public DsrItem(UUID orgId, UUID scheduleId, String code, String description,
                   BigDecimal rate, String unitText, UUID unitId, boolean confirmed) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.scheduleId = scheduleId;
        this.code = code;
        this.description = description;
        this.rate = rate;
        this.unitText = unitText;
        this.unitId = unitId;
        this.confirmed = confirmed;
    }

    public void confirm(BigDecimal rate, String description) {
        this.rate = rate;
        this.description = description;
        this.confirmed = true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getScheduleId() {
        return scheduleId;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public UUID getUnitId() {
        return unitId;
    }

    public void setUnitId(UUID unitId) {
        this.unitId = unitId;
    }

    public String getUnitText() {
        return unitText;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public String getChapter() {
        return chapter;
    }

    public void setChapter(String chapter) {
        this.chapter = chapter;
    }

    public Integer getPageNo() {
        return pageNo;
    }

    public void setPageNo(Integer pageNo) {
        this.pageNo = pageNo;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

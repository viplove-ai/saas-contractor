package in.nirman.modules.dpr.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Plant on site that day, with the hours it ran and the hours it stood.
 *
 * <p>Hours only, no money — and that is a known gap rather than an oversight. docs/09 records
 * machinery rental cost as not modelled in v1 (section 5): the field data reviewed carried no
 * rental rates, and inventing a rate table would put a fabricated number into project cost.
 * Idle hours are recorded separately because they are the operationally useful half: a mixer
 * that stood for five hours is a story about something else that went wrong.</p>
 */
@Entity
@Table(name = "dpr_machinery")
public class DprMachinery {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "dpr_id", nullable = false, updatable = false)
    private UUID dprId;

    @Column(name = "machinery_name", nullable = false, length = 150)
    private String machineryName;

    @Column(name = "count", nullable = false)
    private int count = 1;

    @Column(name = "hours_used", precision = 6, scale = 2)
    private BigDecimal hoursUsed;

    @Column(name = "idle_hours", precision = 6, scale = 2)
    private BigDecimal idleHours;

    @Column(name = "remarks", length = 300)
    private String remarks;

    protected DprMachinery() {
    }

    public DprMachinery(UUID dprId, String machineryName, int count, BigDecimal hoursUsed,
                        BigDecimal idleHours, String remarks) {
        this.id = UUID.randomUUID();
        this.dprId = dprId;
        this.machineryName = machineryName;
        this.count = count;
        this.hoursUsed = hoursUsed;
        this.idleHours = idleHours;
        this.remarks = remarks;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDprId() {
        return dprId;
    }

    public String getMachineryName() {
        return machineryName;
    }

    public int getCount() {
        return count;
    }

    public BigDecimal getHoursUsed() {
        return hoursUsed;
    }

    public BigDecimal getIdleHours() {
        return idleHours;
    }

    public String getRemarks() {
        return remarks;
    }
}

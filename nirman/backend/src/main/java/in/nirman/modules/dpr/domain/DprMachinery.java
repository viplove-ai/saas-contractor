package in.nirman.modules.dpr.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * Plant on site that day, with the hours it ran and the hours it stood.
 *
 * <p>Idle hours are recorded separately because they are the operationally useful half: a
 * mixer that stood for five hours is a story about something else that went wrong.</p>
 *
 * <p><b>The hours are the supervisor's and the rate is not.</b> It carried no money at all
 * until V39, on docs/09's argument that the field data reviewed had no rental rates and
 * inventing a rate table would put a fabricated number into project cost. That was an
 * argument against inventing a rate, not against recording one somebody agreed — a hired JCB
 * is billed by the hour whether or not this system knows the figure. So the rate is here, and
 * it is written after the handover by whoever the report goes to: the man who watched the
 * mixer run does not hold the hire agreement, and a rate box on his screen is a number
 * guessed at seven in the evening.</p>
 */
@Entity
@Table(name = "dpr_machinery")
public class DprMachinery {

    /**
     * What the rate is per.
     *
     * <p>No MONTH. This is one day's report, so a monthly rate would need a divisor — how
     * many days the month is worked — and a divisor assumed here is two screens disagreeing
     * about the same machine. An organisation hiring by the month enters the day rate it
     * works out to, once, where somebody can see it.</p>
     */
    public enum RateBasis {
        /** Multiplies the hours the machine ran. Idle hours are not charged at this rate. */
        HOUR,
        /** Multiplies the number of machines: the report is one day. */
        DAY
    }

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

    /** What it is charged at, or null while nobody has said. Null is not free. */
    @Column(name = "hire_rate", precision = 18, scale = 4)
    private BigDecimal hireRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_basis", length = 10)
    private RateBasis rateBasis;

    @Column(name = "rate_set_at")
    private Instant rateSetAt;

    @Column(name = "rate_set_by")
    private UUID rateSetBy;

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

    /**
     * The rate, put on by whoever the report went to after the handover.
     *
     * <p>A null rate clears the basis with it, and the signature with both: a basis left
     * behind is a unit for nothing, and a name left behind says somebody priced a machine
     * that now carries no price.</p>
     */
    public void priceAt(BigDecimal rate, RateBasis basis, Instant at, UUID by) {
        if (rate == null) {
            this.hireRate = null;
            this.rateBasis = null;
            this.rateSetAt = null;
            this.rateSetBy = null;
            return;
        }
        this.hireRate = rate;
        this.rateBasis = basis;
        this.rateSetAt = at;
        this.rateSetBy = by;
    }

    /**
     * Carries a rate across a rebuild of the plant table.
     *
     * <p>The supervisor may still correct the day's account after the handover, and the
     * update replaces every machinery row rather than merging them — so without this, the
     * office's rate would be deleted by a supervisor adding the second mixer that turned up
     * at four. The signature travels with the figure: it is still the office's rate, not the
     * supervisor's, whatever rewrote the row around it.</p>
     */
    public void carryRateFrom(DprMachinery previous) {
        this.hireRate = previous.hireRate;
        this.rateBasis = previous.rateBasis;
        this.rateSetAt = previous.rateSetAt;
        this.rateSetBy = previous.rateSetBy;
    }

    public BigDecimal getHireRate() {
        return hireRate;
    }

    public RateBasis getRateBasis() {
        return rateBasis;
    }

    public Instant getRateSetAt() {
        return rateSetAt;
    }

    public UUID getRateSetBy() {
        return rateSetBy;
    }

    /**
     * What this row comes to, or null while nobody has priced it.
     *
     * <p>Derived on read rather than stored, like every other roll-up here: the hours can be
     * corrected until the signature, and a stored total would be the version somebody
     * believed on Tuesday.</p>
     *
     * <p>An hourly rate charges the hours the machine <b>ran</b>. Standing time is usually
     * agreed separately and often at a different rate, so charging it here at the running
     * rate would invent the very number V1 refused to invent — the idle hours stay in their
     * own column, beside the figure, where whoever reads the report can see them. Hours that
     * were never recorded come back null and not zero: nobody said the mixer ran for nothing,
     * they said nothing at all.</p>
     */
    public BigDecimal hireAmount() {
        if (hireRate == null || rateBasis == null) {
            return null;
        }
        if (rateBasis == RateBasis.DAY) {
            return hireRate.multiply(BigDecimal.valueOf(count)).setScale(2, RoundingMode.HALF_UP);
        }
        return hoursUsed == null
                ? null : hireRate.multiply(hoursUsed).setScale(2, RoundingMode.HALF_UP);
    }
}

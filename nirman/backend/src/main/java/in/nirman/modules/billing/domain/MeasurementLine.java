package in.nirman.modules.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * One ruled row of a measurement sheet: {@code nos × mult × L × B × H}.
 *
 * <p>Six shapes cover every measurement line in a real bill, and they are all this one
 * formula with the unused dimensions left null — area drops the height, linear drops breadth
 * and height, a count drops all three. <b>Null is not zero.</b> A linear item has no breadth,
 * and multiplying by zero would claim the work had none rather than that the question does
 * not arise.</p>
 *
 * <p>{@code contents} is derived from the five and stored, because the bill reads thousands
 * of these and re-multiplying at read time buys nothing. It is recomputed on every write, so
 * it can never drift from the dimensions beside it.</p>
 *
 * <p>A deduction — an opening taken out of plaster or brickwork — is an ordinary row with
 * {@code isDeduction} set. It prints indented under the additions and subtracts from the
 * total, which is how the department expects to read it.</p>
 */
@Entity
@Table(name = "measurement_lines")
public class MeasurementLine {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "sheet_id", nullable = false, updatable = false)
    private UUID sheetId;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "location", length = 300)
    private String location;

    @Column(name = "nos", nullable = false, precision = 12, scale = 3)
    private BigDecimal nos = BigDecimal.ONE;

    @Column(name = "mult", nullable = false, precision = 12, scale = 3)
    private BigDecimal mult = BigDecimal.ONE;

    @Column(name = "length", precision = 12, scale = 3)
    private BigDecimal length;

    @Column(name = "breadth", precision = 12, scale = 3)
    private BigDecimal breadth;

    @Column(name = "height", precision = 12, scale = 3)
    private BigDecimal height;

    @Column(name = "contents", nullable = false, precision = 18, scale = 4)
    private BigDecimal contents = BigDecimal.ZERO;

    @Column(name = "is_deduction", nullable = false)
    private boolean deduction;

    /** Bar bending only: the diameter in mm this row's steel is. */
    @Column(name = "bar_dia")
    private Integer barDia;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected MeasurementLine() {
    }

    public MeasurementLine(UUID sheetId, int lineNo, String location, BigDecimal nos,
                           BigDecimal mult, BigDecimal length, BigDecimal breadth,
                           BigDecimal height, boolean deduction, Integer barDia) {
        this.id = UUID.randomUUID();
        this.sheetId = sheetId;
        this.lineNo = lineNo;
        this.location = location;
        this.nos = nos == null ? BigDecimal.ONE : nos;
        this.mult = mult == null ? BigDecimal.ONE : mult;
        this.length = length;
        this.breadth = breadth;
        this.height = height;
        this.deduction = deduction;
        this.barDia = barDia;
        this.contents = computeContents();
    }

    /**
     * The product of whichever dimensions were given, rounded the way a measurement book
     * rounds — two places, half up — and signed negative for a deduction.
     *
     * <p>Rounding here rather than at the total is deliberate: the printed sheet shows this
     * figure per row and the column is expected to add up to the total beside it. Rounding
     * only at the end would produce a page whose own arithmetic looks wrong to the Assistant
     * Engineer checking it, which is a worse problem than the fraction of a unit it saves.</p>
     */
    public BigDecimal computeContents() {
        BigDecimal value = nos.multiply(mult);
        if (length != null) {
            value = value.multiply(length);
        }
        if (breadth != null) {
            value = value.multiply(breadth);
        }
        if (height != null) {
            value = value.multiply(height);
        }
        value = value.setScale(2, RoundingMode.HALF_UP);
        return deduction ? value.negate() : value;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSheetId() {
        return sheetId;
    }

    public int getLineNo() {
        return lineNo;
    }

    public String getLocation() {
        return location;
    }

    public BigDecimal getNos() {
        return nos;
    }

    public BigDecimal getMult() {
        return mult;
    }

    public BigDecimal getLength() {
        return length;
    }

    public BigDecimal getBreadth() {
        return breadth;
    }

    public BigDecimal getHeight() {
        return height;
    }

    public BigDecimal getContents() {
        return contents;
    }

    public boolean isDeduction() {
        return deduction;
    }

    public Integer getBarDia() {
        return barDia;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

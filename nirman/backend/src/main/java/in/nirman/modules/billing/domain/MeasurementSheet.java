package in.nirman.modules.billing.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One ruled page of measurements against one contract item — the piece of paper the engineer
 * filled in with a tape in his hand.
 *
 * <p>The sheet is the unit rather than the line because the sheet is the object that exists:
 * it is printed with a serial, filled in, totalled by hand, signed, photographed, and swept
 * into a bill whole. A bill claims sheets, not rows.</p>
 *
 * <p><b>The two totals are the point.</b> {@code writtenTotal} is what he worked out at the
 * foot of the page; {@code computedTotal} is what the rows come to when the system multiplies
 * them. Two independent arrivals at one number, and {@link #sign} refuses while they
 * disagree — which catches a typing slip, a skipped row, a transposed 5.8 for 8.5, and his
 * own arithmetic. {@code ck_sheet_signed_agrees} is the backstop under it.</p>
 *
 * <p><b>Once billed it is closed.</b> {@code raBillId} is stamped when a bill freezes and is
 * what makes paying the same quantity twice impossible: the column cannot hold two ids. A
 * correction found afterwards is a new sheet with negative rows, dated when it was found —
 * never an edit to a sheet that has been paid.</p>
 */
@Entity
@Table(name = "measurement_sheets")
public class MeasurementSheet extends BaseEntity {

    /**
     * MEASUREMENT is the ordinary grid. BAR_BENDING is reinforcement — diameter, nos and
     * length, totalled per diameter against tested unit weights — which is a different table
     * on the paper too, and a different sheet in every bill ever prepared by hand.
     */
    public enum SheetType { MEASUREMENT, BAR_BENDING }

    public enum Status { DRAFT, SIGNED }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private UUID siteId;

    @Column(name = "boq_item_id", nullable = false, updatable = false)
    private UUID boqItemId;

    /** Pre-printed on the paper. Null for a sheet typed straight in with no page behind it. */
    @Column(name = "sheet_serial", length = 40)
    private String sheetSerial;

    @Enumerated(EnumType.STRING)
    @Column(name = "sheet_type", nullable = false, length = 20, updatable = false)
    private SheetType sheetType = SheetType.MEASUREMENT;

    @Column(name = "measured_on", nullable = false)
    private LocalDate measuredOn;

    @Column(name = "measured_by")
    private UUID measuredBy;

    @Column(name = "location_note")
    private String locationNote;

    @Column(name = "written_total", precision = 18, scale = 4)
    private BigDecimal writtenTotal;

    @Column(name = "computed_total", nullable = false, precision = 18, scale = 4)
    private BigDecimal computedTotal = BigDecimal.ZERO;

    /** Sections only: the tested kg/m the summed length is taken against. */
    @Column(name = "unit_weight", precision = 18, scale = 4)
    private BigDecimal unitWeight;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.DRAFT;

    @Column(name = "signed_at")
    private Instant signedAt;

    @Column(name = "signed_by")
    private UUID signedBy;

    /** Null while measured but not yet billed. The double-payment guard. */
    @Column(name = "ra_bill_id")
    private UUID raBillId;

    /** The photograph of the paper. Evidence — nothing reads it. */
    @Column(name = "attachment_id")
    private UUID attachmentId;

    @Column(name = "remarks")
    private String remarks;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected MeasurementSheet() {
    }

    public MeasurementSheet(UUID orgId, UUID projectId, UUID siteId, UUID boqItemId,
                            SheetType sheetType, LocalDate measuredOn, UUID measuredBy) {
        this.orgId = orgId;
        this.projectId = projectId;
        this.siteId = siteId;
        this.boqItemId = boqItemId;
        this.sheetType = sheetType == null ? SheetType.MEASUREMENT : sheetType;
        this.measuredOn = measuredOn;
        this.measuredBy = measuredBy;
    }

    /**
     * The quantity this sheet claims. For a section sheet the rows sum to a length and the
     * claim is that length times the tested unit weight; everywhere else the rows are already
     * in the item's own unit.
     */
    public BigDecimal claimedQuantity() {
        if (unitWeight == null) {
            return computedTotal;
        }
        return computedTotal.multiply(unitWeight).setScale(4, java.math.RoundingMode.HALF_UP);
    }

    /** True when the engineer's own total and the system's agree to the rounding kept. */
    public boolean totalsAgree() {
        return writtenTotal == null
                || writtenTotal.subtract(computedTotal).abs().compareTo(new BigDecimal("0.01")) <= 0;
    }

    public void sign(Instant when, UUID who) {
        this.status = Status.SIGNED;
        this.signedAt = when;
        this.signedBy = who;
    }

    /** Releasing a sheet back to the unbilled queue when a draft bill is discarded. */
    public void releaseFromBill() {
        this.raBillId = null;
    }

    public boolean isBilled() {
        return raBillId != null;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getSiteId() {
        return siteId;
    }

    public UUID getBoqItemId() {
        return boqItemId;
    }

    public String getSheetSerial() {
        return sheetSerial;
    }

    public void setSheetSerial(String sheetSerial) {
        this.sheetSerial = sheetSerial;
    }

    public SheetType getSheetType() {
        return sheetType;
    }

    public LocalDate getMeasuredOn() {
        return measuredOn;
    }

    public void setMeasuredOn(LocalDate measuredOn) {
        this.measuredOn = measuredOn;
    }

    public UUID getMeasuredBy() {
        return measuredBy;
    }

    public String getLocationNote() {
        return locationNote;
    }

    public void setLocationNote(String locationNote) {
        this.locationNote = locationNote;
    }

    public BigDecimal getWrittenTotal() {
        return writtenTotal;
    }

    public void setWrittenTotal(BigDecimal writtenTotal) {
        this.writtenTotal = writtenTotal;
    }

    public BigDecimal getComputedTotal() {
        return computedTotal;
    }

    public void setComputedTotal(BigDecimal computedTotal) {
        this.computedTotal = computedTotal;
    }

    public BigDecimal getUnitWeight() {
        return unitWeight;
    }

    public void setUnitWeight(BigDecimal unitWeight) {
        this.unitWeight = unitWeight;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getSignedAt() {
        return signedAt;
    }

    public UUID getSignedBy() {
        return signedBy;
    }

    public UUID getRaBillId() {
        return raBillId;
    }

    public void setRaBillId(UUID raBillId) {
        this.raBillId = raBillId;
    }

    public UUID getAttachmentId() {
        return attachmentId;
    }

    public void setAttachmentId(UUID attachmentId) {
        this.attachmentId = attachmentId;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void markDeleted(Instant when) {
        this.deletedAt = when;
    }
}

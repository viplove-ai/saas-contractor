package in.nirman.modules.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One movement of stock. Append-only: never updated, never deleted.
 *
 * <p>This is the whole inventory module in one table. A goods receipt, an issue, a
 * transfer, a wastage and a count adjustment are five different pieces of paper and exactly
 * one kind of ledger row, which is what makes "what happened to this material" a single
 * query instead of a five-way union.</p>
 *
 * <p>{@code quantityBase} is always positive and {@code direction} carries the sign, the
 * same split the worker ledger uses: a report can total receipts and issues separately
 * without re-deriving which is which from a signed number. Quantities are always in the
 * material's base unit — a delivery booked in quintals and an issue booked in kilograms
 * have to be subtractable.</p>
 *
 * <p>{@code balanceAfter} and {@code avgRateAfter} are snapshots taken at the moment of
 * writing, so a ledger screen can show a running balance without re-summing history on
 * every scroll. They are derived, not authoritative — re-summing the column is what proves
 * them right.</p>
 */
@Entity
@Table(name = "stock_transactions")
@EntityListeners(AuditingEntityListener.class)
public class StockTransaction {

    /** What kind of movement this is. The sign follows from it; see {@link #directionOf}. */
    public enum TxnType {
        OPENING_STOCK,
        RECEIPT,
        ISSUE,
        TRANSFER_OUT,
        TRANSFER_IN,
        /** Material coming back unused from the work face. */
        RETURN,
        WASTAGE,
        DAMAGE,
        /** A count difference or an administrative correction. Can go either way. */
        ADJUSTMENT
    }

    /** The document family that caused the movement, so a row can be traced back to paper. */
    public enum SourceType {
        OPENING, GOODS_RECEIPT, ISSUE, TRANSFER, COUNT, WASTAGE, ADJUSTMENT
    }

    public static final short IN = 1;
    public static final short OUT = -1;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "project_id", updatable = false)
    private UUID projectId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private UUID siteId;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;

    @Enumerated(EnumType.STRING)
    @Column(name = "txn_type", nullable = false, length = 25, updatable = false)
    private TxnType txnType;

    @Column(name = "direction", nullable = false, updatable = false)
    private short direction;

    @Column(name = "txn_date", nullable = false, updatable = false)
    private LocalDate txnDate;

    @Column(name = "quantity_base", nullable = false, precision = 18, scale = 4, updatable = false)
    private BigDecimal quantityBase;

    /** Per base unit. For an outward move this is the moving average frozen at issue time. */
    @Column(name = "rate_base", nullable = false, precision = 18, scale = 4, updatable = false)
    private BigDecimal rateBase = BigDecimal.ZERO;

    @Column(name = "value", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal value = BigDecimal.ZERO;

    @Column(name = "balance_after", precision = 18, scale = 4)
    private BigDecimal balanceAfter;

    @Column(name = "avg_rate_after", precision = 18, scale = 4)
    private BigDecimal avgRateAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30, updatable = false)
    private SourceType sourceType;

    @Column(name = "source_id", updatable = false)
    private UUID sourceId;

    /** The document line. Unique per movement direction, which is what makes a re-send safe. */
    @Column(name = "source_line_id", updatable = false)
    private UUID sourceLineId;

    @Column(name = "boq_item_id", updatable = false)
    private UUID boqItemId;

    @Column(name = "reason", updatable = false)
    private String reason;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected StockTransaction() {
    }

    public StockTransaction(UUID orgId, UUID projectId, UUID siteId, UUID storeId, UUID materialId,
                            TxnType txnType, short direction, LocalDate txnDate,
                            BigDecimal quantityBase, BigDecimal rateBase,
                            SourceType sourceType, UUID sourceId, UUID sourceLineId,
                            UUID boqItemId, String reason) {
        if (quantityBase == null || quantityBase.signum() <= 0) {
            throw new IllegalArgumentException(
                    "stock quantity must be positive; direction carries the sign");
        }
        if (direction != IN && direction != OUT) {
            throw new IllegalArgumentException("direction must be +1 or -1");
        }
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.projectId = projectId;
        this.siteId = siteId;
        this.storeId = storeId;
        this.materialId = materialId;
        this.txnType = txnType;
        this.direction = direction;
        this.txnDate = txnDate;
        this.quantityBase = quantityBase;
        this.rateBase = rateBase == null ? BigDecimal.ZERO : rateBase;
        this.value = this.quantityBase.multiply(this.rateBase).setScale(2, java.math.RoundingMode.HALF_UP);
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.sourceLineId = sourceLineId;
        this.boqItemId = boqItemId;
        this.reason = reason;
    }

    /**
     * The sign a movement type carries. {@code ADJUSTMENT} is deliberately absent: a count
     * difference can be either way, so its caller states the direction rather than guessing.
     */
    public static short directionOf(TxnType type) {
        return switch (type) {
            case OPENING_STOCK, RECEIPT, TRANSFER_IN, RETURN -> IN;
            case ISSUE, TRANSFER_OUT, WASTAGE, DAMAGE -> OUT;
            case ADJUSTMENT -> throw new IllegalArgumentException(
                    "an adjustment must state its own direction");
        };
    }

    /** The quantity with its sign applied — what a running balance actually adds. */
    public BigDecimal signedQuantity() {
        return direction == OUT ? quantityBase.negate() : quantityBase;
    }

    /**
     * Pins the store's position as it stood immediately after this movement, so a ledger
     * screen can show a running balance without re-summing history on every scroll. Written
     * once, by the ledger service, inside the transaction that moved the stock.
     */
    public void snapshot(BigDecimal balanceAfter, BigDecimal avgRateAfter) {
        this.balanceAfter = balanceAfter;
        this.avgRateAfter = avgRateAfter;
    }

    public UUID getId() {
        return id;
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

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getMaterialId() {
        return materialId;
    }

    public TxnType getTxnType() {
        return txnType;
    }

    public short getDirection() {
        return direction;
    }

    public LocalDate getTxnDate() {
        return txnDate;
    }

    public BigDecimal getQuantityBase() {
        return quantityBase;
    }

    public BigDecimal getRateBase() {
        return rateBase;
    }

    public BigDecimal getValue() {
        return value;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public BigDecimal getAvgRateAfter() {
        return avgRateAfter;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public UUID getSourceLineId() {
        return sourceLineId;
    }

    public UUID getBoqItemId() {
        return boqItemId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }
}

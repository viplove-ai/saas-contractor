package in.nirman.modules.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * How much of one material is in one store right now, and what it is worth.
 *
 * <p>A cache, not the truth. {@code stock_transactions} is the truth; this row is written
 * inside the same transaction under {@code SELECT … FOR UPDATE}, and if the two ever
 * disagree the ledger wins and the difference is a data-quality alert rather than a
 * balance somebody can retype. There is no setter for the quantity anywhere a user can
 * reach — docs/03 rule 3.</p>
 *
 * <h2>Weighted average, and why</h2>
 * <p>Cement bought at ₹410 and ₹425 in the same week is the same cement in the same heap;
 * nobody at site can tell you which bag came from which lorry, so FIFO would be a fiction
 * maintained by the software alone. The average moves on the way in and is frozen on the
 * way out:</p>
 * <ul>
 *   <li><b>Inward</b> — value grows by quantity × the rate actually paid, and the average
 *       is re-derived from the new value and quantity.</li>
 *   <li><b>Outward</b> — the issue is valued at the average <i>as it stands</i>, and value
 *       falls by exactly that. The average itself does not move: taking material out of a
 *       heap does not change what the heap cost.</li>
 * </ul>
 * <p>Deriving the average from value ÷ quantity rather than accumulating it separately is
 * what stops rounding drift accumulating over a year of deliveries.</p>
 */
@Entity
@Table(name = "stock_balances")
public class StockBalance {

    private static final int QTY_SCALE = 4;
    private static final int MONEY_SCALE = 2;
    private static final int RATE_SCALE = 4;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;

    @Column(name = "quantity_base", nullable = false, precision = 18, scale = 4)
    private BigDecimal quantityBase = BigDecimal.ZERO;

    @Column(name = "moving_avg_rate", nullable = false, precision = 18, scale = 4)
    private BigDecimal movingAvgRate = BigDecimal.ZERO;

    @Column(name = "stock_value", nullable = false, precision = 18, scale = 2)
    private BigDecimal stockValue = BigDecimal.ZERO;

    /**
     * Dispatched from another store and not yet received here. Deliberately <b>not</b> part
     * of {@code quantityBase}: material on a lorry is at neither end, and counting it at
     * either one is how two stores both think they have the same forty bags.
     */
    @Column(name = "in_transit_qty_base", nullable = false, precision = 18, scale = 4)
    private BigDecimal inTransitQtyBase = BigDecimal.ZERO;

    @Column(name = "last_txn_at")
    private Instant lastTxnAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected StockBalance() {
    }

    public StockBalance(UUID orgId, UUID storeId, UUID materialId) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.storeId = storeId;
        this.materialId = materialId;
    }

    /**
     * Applies one ledger movement. Called only from the ledger service, which has already
     * taken the row lock and already refused anything that would drive the quantity below
     * zero.
     *
     * @return the rate the movement was valued at — the rate paid on the way in, the
     *         average in force on the way out. The caller freezes it onto the ledger row.
     */
    public BigDecimal apply(short direction, BigDecimal quantity, BigDecimal inwardRate) {
        BigDecimal appliedRate = direction == StockTransaction.IN
                ? receive(quantity, inwardRate)
                : issue(quantity);
        this.lastTxnAt = Instant.now();
        this.updatedAt = Instant.now();
        return appliedRate;
    }

    private BigDecimal receive(BigDecimal quantity, BigDecimal rate) {
        BigDecimal rateIn = rate == null ? BigDecimal.ZERO : rate;
        this.stockValue = stockValue
                .add(quantity.multiply(rateIn))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        this.quantityBase = quantityBase.add(quantity).setScale(QTY_SCALE, RoundingMode.HALF_UP);
        // Safe: an inward movement carries a positive quantity, so the divisor cannot be zero.
        this.movingAvgRate = stockValue.divide(quantityBase, RATE_SCALE, RoundingMode.HALF_UP);
        return rateIn;
    }

    private BigDecimal issue(BigDecimal quantity) {
        BigDecimal atRate = movingAvgRate;
        this.quantityBase = quantityBase.subtract(quantity).setScale(QTY_SCALE, RoundingMode.HALF_UP);
        if (quantityBase.signum() == 0) {
            // Emptying the store empties its value exactly, rather than leaving the paisa
            // that rounding each issue would otherwise strand there for ever.
            this.stockValue = BigDecimal.ZERO.setScale(MONEY_SCALE);
        } else {
            this.stockValue = stockValue
                    .subtract(quantity.multiply(atRate))
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return atRate;
    }

    /** True when this store cannot cover the quantity asked for. */
    public boolean cannotCover(BigDecimal quantity) {
        return quantityBase.compareTo(quantity) < 0;
    }

    /** Material dispatched towards this store and not yet received. */
    public void addInTransit(BigDecimal quantity) {
        this.inTransitQtyBase = inTransitQtyBase.add(quantity);
        this.updatedAt = Instant.now();
    }

    public void clearInTransit(BigDecimal quantity) {
        BigDecimal remaining = inTransitQtyBase.subtract(quantity);
        // A shortage on receipt means less arrives than left; never let the figure go under.
        this.inTransitQtyBase = remaining.signum() < 0 ? BigDecimal.ZERO : remaining;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getMaterialId() {
        return materialId;
    }

    public BigDecimal getQuantityBase() {
        return quantityBase;
    }

    public BigDecimal getMovingAvgRate() {
        return movingAvgRate;
    }

    public BigDecimal getStockValue() {
        return stockValue;
    }

    public BigDecimal getInTransitQtyBase() {
        return inTransitQtyBase;
    }

    public Instant getLastTxnAt() {
        return lastTxnAt;
    }

    public Long getVersion() {
        return version;
    }
}

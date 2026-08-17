package in.nirman.modules.inventory.domain;

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
 * The field asking for a stock figure to be put right.
 *
 * <p>It holds no stock. That is the point of it existing at all: {@code stock_transactions} is
 * append-only and an ADJUSTMENT is administrator-only, because a role that can move a balance
 * without a document behind it is a role that can hide a loss. But the man who can see the
 * shed is the storekeeper, and until this table he had no sentence to say — twelve bags booked
 * and eleven delivered stayed wrong until a physical count found it in another quarter.</p>
 *
 * <p>So he asks, and an administrator decides. Accepting posts the ordinary ADJUSTMENT through
 * {@code StockAdjustmentService}, with the ordinary period lock and the ordinary refusal to
 * drive a balance below zero — the ledger is not made one degree less strict by any of this,
 * and the posted row is kept on the request so "what did the office actually do about my
 * count" has an answer.</p>
 *
 * <p>A rejected request stays and carries its reason. A row that quietly disappears is a
 * storekeeper who counts the shed once and never says so again.</p>
 */
@Entity
@Table(name = "stock_correction_requests")
public class StockCorrectionRequest extends BaseEntity {

    public enum Status {
        PENDING,
        ACCEPTED,
        REJECTED
    }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private UUID siteId;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;

    /**
     * The unit he counted in, not the base unit the ledger keeps. Storing what he actually
     * said is what lets the office read "two bags short" instead of "-100 kg" and recognise
     * its own store; the conversion happens once, on the way to the posting.
     */
    @Column(name = "unit_id", nullable = false, updatable = false)
    private UUID unitId;

    /** Signed, in {@link #unitId}: negative writes stock off, positive writes it on. */
    @Column(name = "quantity_delta", nullable = false, precision = 14, scale = 4)
    private BigDecimal quantityDelta;

    /**
     * The day the correction belongs to, which is not the day it was typed. A count made on
     * the 31st and sent on the 2nd is March's, and the period lock is asked about this date.
     */
    @Column(name = "correction_date", nullable = false)
    private LocalDate correctionDate;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    /** The ADJUSTMENT this became. Null until somebody accepts it, and never null after. */
    @Column(name = "posted_txn_id")
    private UUID postedTxnId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decision_remarks", length = 500)
    private String decisionRemarks;

    protected StockCorrectionRequest() {
    }

    public StockCorrectionRequest(UUID id, UUID orgId, UUID siteId, UUID storeId, UUID materialId,
                                  UUID unitId, BigDecimal quantityDelta, LocalDate correctionDate,
                                  String reason) {
        setId(id);
        this.orgId = orgId;
        this.siteId = siteId;
        this.storeId = storeId;
        this.materialId = materialId;
        this.unitId = unitId;
        this.quantityDelta = quantityDelta;
        this.correctionDate = correctionDate;
        this.reason = reason;
    }

    /**
     * The office agreeing with the count, and the ledger row it produced.
     *
     * <p>The posted transaction is taken rather than looked up later: an accepted request that
     * cannot name its adjustment is a correction everybody believes happened and the ledger
     * has never heard of, which is the one state the table's check constraint refuses.</p>
     */
    public void accept(UUID postedTxnId, Instant at, UUID by, String remarks) {
        this.status = Status.ACCEPTED;
        this.postedTxnId = postedTxnId;
        this.decidedAt = at;
        this.decidedBy = by;
        this.decisionRemarks = remarks;
    }

    /** No, and here is why. The row stays: the man who counted the shed is owed the answer. */
    public void reject(Instant at, UUID by, String remarks) {
        this.status = Status.REJECTED;
        this.decidedAt = at;
        this.decidedBy = by;
        this.decisionRemarks = remarks;
    }

    public UUID getOrgId() {
        return orgId;
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

    public UUID getUnitId() {
        return unitId;
    }

    public BigDecimal getQuantityDelta() {
        return quantityDelta;
    }

    public LocalDate getCorrectionDate() {
        return correctionDate;
    }

    public String getReason() {
        return reason;
    }

    public Status getStatus() {
        return status;
    }

    public UUID getPostedTxnId() {
        return postedTxnId;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public String getDecisionRemarks() {
        return decisionRemarks;
    }
}

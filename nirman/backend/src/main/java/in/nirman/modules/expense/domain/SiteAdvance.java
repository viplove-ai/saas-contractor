package in.nirman.modules.expense.domain;

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
 * Petty cash issued to a member of staff to spend at a site.
 *
 * <p>Distinct from {@code worker_advances}, which is money against a labourer's wages. A
 * worker is not a user and does not spend on the firm's behalf; a supervisor holding ₹20,000
 * of site float is doing exactly that, and what he owes back is the float less the bills he
 * produces (docs/09 section A).</p>
 *
 * <p>{@code balanceAmount} is a generated column — issued less adjusted less returned — and
 * is read here, never written. It is the one number the holder and the office argue about,
 * so it must not be independently settable by either.</p>
 */
@Entity
@Table(name = "site_advances")
public class SiteAdvance extends BaseEntity {

    public enum SettlementStatus { OPEN, PARTIALLY_SETTLED, SETTLED, CANCELLED }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private UUID siteId;

    @Column(name = "advance_number", nullable = false, length = 50, updatable = false)
    private String advanceNumber;

    @Column(name = "issued_to_user_id", nullable = false, updatable = false)
    private UUID issuedToUserId;

    @Column(name = "advance_date", nullable = false)
    private LocalDate advanceDate;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "payment_mode", nullable = false, length = 20)
    private String paymentMode;

    @Column(name = "reference_number", length = 80)
    private String referenceNumber;

    @Column(name = "purpose", nullable = false)
    private String purpose;

    /** Cleared by bills the holder produced. Moved only by an approved settlement. */
    @Column(name = "adjusted_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal adjustedAmount = BigDecimal.ZERO;

    @Column(name = "returned_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal returnedAmount = BigDecimal.ZERO;

    /** Generated in the database from the three above. Read-only here. */
    @Column(name = "balance_amount", precision = 18, scale = 2,
            insertable = false, updatable = false)
    private BigDecimal balanceAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_status", nullable = false, length = 20)
    private SettlementStatus settlementStatus = SettlementStatus.OPEN;

    @Column(name = "issued_by")
    private UUID issuedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "remarks")
    private String remarks;

    protected SiteAdvance() {
    }

    public SiteAdvance(UUID orgId, UUID projectId, UUID siteId, String advanceNumber,
                       UUID issuedToUserId, LocalDate advanceDate, BigDecimal amount,
                       String paymentMode, String purpose) {
        this.orgId = orgId;
        this.projectId = projectId;
        this.siteId = siteId;
        this.advanceNumber = advanceNumber;
        this.issuedToUserId = issuedToUserId;
        this.advanceDate = advanceDate;
        this.amount = amount;
        this.paymentMode = paymentMode;
        this.purpose = purpose;
    }

    /**
     * Applies an approved settlement. Called only from the settlement service, which has
     * already checked that the float can cover it.
     */
    public void settle(BigDecimal expensesAmount, BigDecimal returned, Instant at) {
        this.adjustedAmount = adjustedAmount.add(expensesAmount);
        this.returnedAmount = returnedAmount.add(returned);
        BigDecimal cleared = adjustedAmount.add(returnedAmount);
        if (cleared.compareTo(amount) >= 0) {
            this.settlementStatus = SettlementStatus.SETTLED;
            this.closedAt = at;
        } else if (cleared.signum() > 0) {
            this.settlementStatus = SettlementStatus.PARTIALLY_SETTLED;
        }
    }

    /** What is still in the holder's pocket, computed here for the same-transaction reader. */
    public BigDecimal outstanding() {
        return amount.subtract(adjustedAmount).subtract(returnedAmount);
    }

    public void cancel(Instant at, String reason) {
        this.settlementStatus = SettlementStatus.CANCELLED;
        this.closedAt = at;
        this.remarks = reason;
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

    public String getAdvanceNumber() {
        return advanceNumber;
    }

    public UUID getIssuedToUserId() {
        return issuedToUserId;
    }

    public LocalDate getAdvanceDate() {
        return advanceDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getPaymentMode() {
        return paymentMode;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public void setReferenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
    }

    public String getPurpose() {
        return purpose;
    }

    public BigDecimal getAdjustedAmount() {
        return adjustedAmount;
    }

    public BigDecimal getReturnedAmount() {
        return returnedAmount;
    }

    public BigDecimal getBalanceAmount() {
        return balanceAmount == null ? outstanding() : balanceAmount;
    }

    public SettlementStatus getSettlementStatus() {
        return settlementStatus;
    }

    public UUID getIssuedBy() {
        return issuedBy;
    }

    public void setIssuedBy(UUID issuedBy) {
        this.issuedBy = issuedBy;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }
}

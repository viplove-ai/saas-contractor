package in.nirman.modules.labour.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Wages handed to a worker on a payday, against what the ledger says he is owed.
 *
 * <p>The ledger has carried a {@code PAYMENT} entry type since V1 with nothing writing one.
 * This row is what a payment is posted from, and it is also the moment an advance is
 * <em>recovered</em>: the ledger netted the advance out of his wages the day it was approved,
 * but "recovered" is a statement about a payday — that the wages it stood against were
 * handed over with that much held back — and until there was a payday nothing could say
 * it.</p>
 *
 * <p>{@code advancesRecovered} is the record of what this payday closed, not a balance. The
 * balance is summed off the ledger as it always was (docs/09).</p>
 */
@Entity
@Table(name = "worker_payments")
public class WorkerPayment extends BaseEntity {

    /** No GOODS: a wage is paid in money. Ration against wages is an advance. */
    public enum PaymentMode { CASH, BANK, UPI, CHEQUE }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private UUID siteId;

    @Column(name = "worker_id", nullable = false, updatable = false)
    private UUID workerId;

    @Column(name = "payment_number", nullable = false, length = 50, updatable = false)
    private String paymentNumber;

    @Column(name = "payment_date", nullable = false, updatable = false)
    private LocalDate paymentDate;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", nullable = false, length = 20)
    private PaymentMode paymentMode = PaymentMode.CASH;

    @Column(name = "reference_number", length = 100)
    private String referenceNumber;

    @Column(name = "advances_recovered", nullable = false, precision = 18, scale = 2)
    private BigDecimal advancesRecovered = BigDecimal.ZERO;

    @Column(name = "remarks")
    private String remarks;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 15)
    private AttendanceRecord.Source source = AttendanceRecord.Source.ONLINE;

    protected WorkerPayment() {
    }

    public WorkerPayment(UUID id, UUID orgId, UUID projectId, UUID siteId, UUID workerId,
                         String paymentNumber, LocalDate paymentDate, BigDecimal amount) {
        setId(id);
        this.orgId = orgId;
        this.projectId = projectId;
        this.siteId = siteId;
        this.workerId = workerId;
        this.paymentNumber = paymentNumber;
        this.paymentDate = paymentDate;
        this.amount = amount;
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

    public UUID getWorkerId() {
        return workerId;
    }

    public String getPaymentNumber() {
        return paymentNumber;
    }

    public LocalDate getPaymentDate() {
        return paymentDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentMode getPaymentMode() {
        return paymentMode;
    }

    public void setPaymentMode(PaymentMode paymentMode) {
        this.paymentMode = paymentMode;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public void setReferenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
    }

    public BigDecimal getAdvancesRecovered() {
        return advancesRecovered;
    }

    public void setAdvancesRecovered(BigDecimal advancesRecovered) {
        this.advancesRecovered = advancesRecovered;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public void setSource(AttendanceRecord.Source source) {
        this.source = source;
    }
}

package in.nirman.modules.payroll.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One month of payroll for an organisation.
 *
 * <p>A run exists so that "has July been done" has an answer. Twenty payslips drawn one at a
 * time have no such answer: half a month is indistinguishable from a month where four people
 * happened to be paid nothing, and there is nothing to reconcile against the single transfer
 * that actually left the bank.</p>
 *
 * <p>It is <b>drawn, corrected, then finalised once</b>. While it is a draft its slips are
 * recomputed freely from the salary structures — a raise backdated to the first is picked up
 * by redrawing rather than by twenty corrections. Finalising ends that: from then on the
 * figures are what the documents say, because by then the documents have been printed and
 * handed over.</p>
 *
 * <p>There is no site on this row and no {@code PeriodLockGuard} call anywhere near it, and
 * both absences are deliberate. Salaried staff are the company's, not a site's — an engineer
 * moves between three jobs in a month and his pay does not follow him — so scoping payroll by
 * site would ask a question with no true answer. The period lock guards a <em>site's</em>
 * accounting month against a late entry rewriting a closed cost; a payroll run closes itself,
 * by finalising, which is the same protection owned by the register it belongs to.</p>
 */
@Entity
@Table(name = "payroll_runs")
public class PayrollRun extends BaseEntity {

    /**
     * DRAFT while the month is being collected, FINALISED once. There is no third state and
     * no way back: a run that could be reopened would be a set of printed payslips that can
     * still change, which is the one thing a payslip may not be.
     */
    public enum Status { DRAFT, FINALISED }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    /** Always the first of the month. The service will not let anything else be stored. */
    @Column(name = "period_month", nullable = false, updatable = false)
    private LocalDate periodMonth;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.DRAFT;

    /**
     * How many days this month is being paid against — the denominator every slip in the run
     * prorates on.
     *
     * <p>On the run and not on the member, because it is a decision about the <em>month</em>
     * and mixing conventions inside one month produces two people absent the same three days
     * losing different amounts. Most contractors' offices pay against 26; an office that pays
     * against the calendar sets the calendar's count.</p>
     */
    @Column(name = "payable_days", nullable = false)
    private int payableDays;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "finalised_at")
    private Instant finalisedAt;

    @Column(name = "finalised_by")
    private UUID finalisedBy;

    protected PayrollRun() {
    }

    public PayrollRun(UUID orgId, LocalDate periodMonth, int payableDays) {
        this.orgId = orgId;
        this.periodMonth = periodMonth.withDayOfMonth(1);
        this.payableDays = payableDays;
    }

    public boolean isDraft() {
        return status == Status.DRAFT;
    }

    /** Ends the month. By somebody, at a time — the check constraint insists on both. */
    public void finalise(UUID by, Instant at) {
        this.status = Status.FINALISED;
        this.finalisedBy = by;
        this.finalisedAt = at;
    }

    /** The last day the run covers, which is the calendar's answer and not {@code payableDays}. */
    public LocalDate periodEnd() {
        return periodMonth.withDayOfMonth(periodMonth.lengthOfMonth());
    }

    public UUID getOrgId() {
        return orgId;
    }

    public LocalDate getPeriodMonth() {
        return periodMonth;
    }

    public Status getStatus() {
        return status;
    }

    public int getPayableDays() {
        return payableDays;
    }

    public void setPayableDays(int payableDays) {
        this.payableDays = payableDays;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getFinalisedAt() {
        return finalisedAt;
    }

    public UUID getFinalisedBy() {
        return finalisedBy;
    }
}

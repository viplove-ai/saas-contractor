package in.nirman.modules.labour.service;

import in.nirman.modules.labour.domain.AttendanceRecord;
import in.nirman.modules.labour.domain.WorkerBalance;
import in.nirman.modules.labour.domain.WorkerLedgerEntry;
import in.nirman.modules.labour.domain.WorkerLedgerEntry.EntryType;
import in.nirman.modules.labour.domain.WorkerLedgerEntry.SourceType;
import in.nirman.modules.labour.repository.WorkerBalanceRepository;
import in.nirman.modules.labour.repository.WorkerLedgerEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Posts to the worker's ledger and keeps the derived balance in step.
 *
 * <p>Everything here runs inside the caller's transaction ({@code MANDATORY}) on purpose:
 * a ledger entry must commit with the business event that caused it, or a crash between
 * the two leaves a worker paid for attendance that was never verified.</p>
 *
 * <p><b>The double-payment guard.</b> Verifying an attendance row posts at most one
 * {@code WAGE_EARNED} and one {@code OT_EARNED} against it, ever. That is enforced twice
 * over: this service checks before inserting, and {@code uq_wle_attendance_posting} rejects
 * anything that slips past the check under a race. The check makes a repeat a clean no-op;
 * the constraint makes it impossible.</p>
 */
@Service
public class WorkerLedgerService {

    private static final Logger log = LoggerFactory.getLogger(WorkerLedgerService.class);

    private final WorkerLedgerEntryRepository entries;
    private final WorkerBalanceRepository balances;

    public WorkerLedgerService(WorkerLedgerEntryRepository entries, WorkerBalanceRepository balances) {
        this.entries = entries;
        this.balances = balances;
    }

    /**
     * Posts the wage and overtime a verified attendance row earned.
     *
     * @return the number of entries actually written — zero when this row has already been
     *         posted, which is the answer a re-verification should give
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int postAttendanceEarnings(AttendanceRecord record) {
        int written = 0;
        written += postIfAbsent(record, EntryType.WAGE_EARNED, record.getComputedWageAmount());
        written += postIfAbsent(record, EntryType.OT_EARNED, record.getComputedOtAmount());
        return written;
    }

    private int postIfAbsent(AttendanceRecord record, EntryType type, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return 0;   // an absent day, or a day with no overtime: nothing to owe
        }
        if (entries.existsBySourceTypeAndSourceIdAndEntryType(
                SourceType.ATTENDANCE, record.getId(), type)) {
            log.debug("Attendance {} already posted {} — skipping", record.getId(), type);
            return 0;
        }
        post(new WorkerLedgerEntry(record.getOrgId(), record.getProjectId(), record.getSiteId(),
                record.getWorkerId(), record.getAttendanceDate(), type,
                WorkerLedgerEntry.INCREASE, amount, SourceType.ATTENDANCE, record.getId(), null));
        return 1;
    }

    /**
     * Moves the ledger by the difference a correction made to an already-verified row.
     *
     * <p>The earnings themselves are never rewritten — {@code WAGE_EARNED} and
     * {@code OT_EARNED} are posted once per row and stay put, which is what keeps the
     * original verification auditable. What a correction adds is a separate
     * {@code ADJUSTMENT} carrying only the delta, so the running balance ends up where the
     * corrected day says it should be and the history still shows how it got there.</p>
     *
     * <p>Unlike the earnings, this deliberately does not skip when an entry already exists:
     * a day corrected twice owes two adjustments. The correction endpoint guards against
     * accidental repeats with an optimistic version check instead.</p>
     *
     * @param delta signed; positive means the correction earned the worker more
     * @return the entry written, or null when the correction moved no money
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public WorkerLedgerEntry postAttendanceAdjustment(AttendanceRecord record, BigDecimal delta,
                                                      String reason) {
        if (delta == null || delta.signum() == 0) {
            return null;
        }
        return post(new WorkerLedgerEntry(record.getOrgId(), record.getProjectId(),
                record.getSiteId(), record.getWorkerId(), record.getAttendanceDate(),
                EntryType.ADJUSTMENT,
                delta.signum() > 0 ? WorkerLedgerEntry.INCREASE : WorkerLedgerEntry.DECREASE,
                delta.abs(), SourceType.ATTENDANCE, record.getId(), reason));
    }

    /** Cash or goods handed to a worker: reduces what we still owe him. */
    @Transactional(propagation = Propagation.MANDATORY)
    public WorkerLedgerEntry postAdvance(UUID orgId, UUID projectId, UUID siteId, UUID workerId,
                                         LocalDate date, BigDecimal amount, UUID advanceId,
                                         String reason) {
        return post(new WorkerLedgerEntry(orgId, projectId, siteId, workerId, date,
                EntryType.ADVANCE, WorkerLedgerEntry.DECREASE, amount,
                SourceType.WORKER_ADVANCE, advanceId, reason));
    }

    /** Settling the balance at the end of a period. */
    @Transactional(propagation = Propagation.MANDATORY)
    public WorkerLedgerEntry postPayment(UUID orgId, UUID projectId, UUID siteId, UUID workerId,
                                         LocalDate date, BigDecimal amount, UUID paymentId,
                                         String reason) {
        return post(new WorkerLedgerEntry(orgId, projectId, siteId, workerId, date,
                EntryType.PAYMENT, WorkerLedgerEntry.DECREASE, amount,
                SourceType.PAYMENT, paymentId, reason));
    }

    /**
     * A correction. The schema requires a reason for these, because an adjustment is the
     * only entry a human invents rather than derives, and it must explain itself.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public WorkerLedgerEntry postAdjustment(UUID orgId, UUID projectId, UUID siteId, UUID workerId,
                                            LocalDate date, BigDecimal amount, short direction,
                                            String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("an adjustment must carry a reason");
        }
        return post(new WorkerLedgerEntry(orgId, projectId, siteId, workerId, date,
                EntryType.ADJUSTMENT, direction, amount, SourceType.MANUAL, null, reason));
    }

    /**
     * The one write path. Takes the row lock on the balance first, so concurrent postings
     * for the same worker serialise instead of overwriting each other's totals.
     */
    private WorkerLedgerEntry post(WorkerLedgerEntry entry) {
        WorkerBalance balance = balances.findForUpdate(entry.getWorkerId())
                .orElseGet(() -> balances.save(
                        new WorkerBalance(entry.getOrgId(), entry.getWorkerId())));
        balance.apply(entry);
        entry.setBalanceAfter(balance.getNetPayable());
        entries.save(entry);
        balances.save(balance);
        return entry;
    }
}

package in.nirman.modules.labour.service;

import in.nirman.common.BusinessException;
import in.nirman.common.PageResponse;
import in.nirman.common.PeriodLockGuard;
import in.nirman.common.PeriodLockService;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.labour.api.dto.AttendanceDtos.AttendanceEntry;
import in.nirman.modules.labour.api.dto.AttendanceDtos.AttendanceResponse;
import in.nirman.modules.labour.api.dto.AttendanceDtos.BulkAttendanceRequest;
import in.nirman.modules.labour.api.dto.AttendanceDtos.BulkAttendanceResponse;
import in.nirman.modules.labour.api.dto.AttendanceDtos.BulkAttendanceResponse.EntryOutcome;
import in.nirman.modules.labour.api.dto.AttendanceDtos.BulkAttendanceResponse.Outcome;
import in.nirman.modules.labour.api.dto.AttendanceDtos.CorrectAttendanceRequest;
import in.nirman.modules.labour.api.dto.AttendanceDtos.RosterEntry;
import in.nirman.modules.labour.api.dto.AttendanceDtos.RosterResponse;
import in.nirman.modules.labour.api.dto.AttendanceDtos.UpdateAttendanceRequest;
import in.nirman.modules.labour.api.dto.AttendanceDtos.VerifyAttendanceRequest;
import in.nirman.modules.labour.domain.AttendanceCalculator;
import in.nirman.modules.labour.domain.AttendanceCorrection;
import in.nirman.modules.labour.domain.AttendanceRecord;
import in.nirman.modules.labour.domain.AttendanceStatus;
import in.nirman.modules.labour.domain.LabourSettings;
import in.nirman.modules.labour.domain.WageRate;
import in.nirman.modules.labour.domain.WorkflowStatus;
import in.nirman.modules.labour.domain.Worker;
import in.nirman.modules.labour.repository.AttendanceCorrectionRepository;
import in.nirman.modules.labour.repository.AttendanceRecordRepository;
import in.nirman.modules.labour.repository.LabourSettingsRepository;
import in.nirman.modules.labour.repository.WageRateRepository;
import in.nirman.modules.labour.repository.WorkerRepository;
import in.nirman.modules.project.service.SiteLookup;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Attendance entry and its workflow: roster → bulk save → submit → verify.
 *
 * <p>Three properties this class is responsible for:</p>
 * <ul>
 *   <li><b>A re-sent batch creates one row.</b> The client generates the record id, so the
 *       same batch arriving three times over a bad connection is recognised and answered
 *       {@code UNCHANGED} rather than duplicated.</li>
 *   <li><b>The wage is frozen at verification.</b> Hours are recomputed on every save
 *       because they are facts about the clock, but the rate is resolved as at the
 *       attendance date and pinned to the row when it is verified. A later revision cannot
 *       reprice a verified day.</li>
 *   <li><b>Verifying twice cannot pay twice.</b> The ledger posting is idempotent, so a
 *       double click, a retried request or a re-verification after a correction all leave
 *       the worker owed exactly what he earned.</li>
 * </ul>
 */
@Service
@Transactional
public class AttendanceService {

    private final AttendanceRecordRepository records;
    private final WorkerRepository workers;
    private final WageRateRepository wageRates;
    private final LabourSettingsRepository labourSettings;
    private final WorkerLedgerService ledger;
    private final SiteLookup sites;
    private final SiteAccessGuard siteAccessGuard;
    private final PeriodLockGuard periodLockGuard;
    private final PeriodLockService periodLockService;
    private final AttendanceCorrectionRepository corrections;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public AttendanceService(AttendanceRecordRepository records, WorkerRepository workers,
                             WageRateRepository wageRates, LabourSettingsRepository labourSettings,
                             WorkerLedgerService ledger, SiteLookup sites,
                             SiteAccessGuard siteAccessGuard, PeriodLockGuard periodLockGuard,
                             PeriodLockService periodLockService,
                             AttendanceCorrectionRepository corrections,
                             CurrentUserProvider currentUser, AuditService audit) {
        this.records = records;
        this.workers = workers;
        this.wageRates = wageRates;
        this.labourSettings = labourSettings;
        this.ledger = ledger;
        this.sites = sites;
        this.siteAccessGuard = siteAccessGuard;
        this.periodLockGuard = periodLockGuard;
        this.periodLockService = periodLockService;
        this.corrections = corrections;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ roster

    /**
     * The muster roll for a site and day: everyone posted there, with whatever has already
     * been marked prefilled. One screen, one call — a supervisor on 2G cannot afford a
     * request per worker.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('attendance:create')")
    public RosterResponse roster(UUID siteId, LocalDate date) {
        siteAccessGuard.assertCanAccess(siteId);
        SiteLookup.SiteInfo site = sites.require(siteId);

        List<Worker> roster = workers.findRoster(orgId(), siteId, date);
        Map<UUID, AttendanceRecord> existing = records.findLiveForDay(siteId, date).stream()
                .collect(Collectors.toMap(AttendanceRecord::getWorkerId, Function.identity()));
        Map<UUID, WageRate> rates = ratesFor(roster.stream().map(Worker::getId).toList(), date);

        List<RosterEntry> entries = roster.stream()
                .map(worker -> {
                    WageRate rate = rates.get(worker.getId());
                    AttendanceRecord record = existing.get(worker.getId());
                    return new RosterEntry(worker.getId(), worker.getWorkerCode(),
                            worker.getFullName(), worker.getSkillCategoryId(),
                            worker.getLabourContractorId(),
                            rate == null ? null : rate.getNormalRate(),
                            rate == null ? null : rate.getOvertimeRate(),
                            record == null ? null : toResponse(record, worker.getFullName()));
                })
                .toList();

        return new RosterResponse(siteId, date, site.standardShiftHours(),
                settings().getOvertimeReasonRequiredAboveHours(),
                isPeriodLocked(siteId, date), entries);
    }

    // ------------------------------------------------------------------ entry

    /**
     * Saves a batch of marks. Each entry is judged on its own so one bad row cannot sink an
     * otherwise good roster — the supervisor gets a per-worker outcome rather than a single
     * failure for forty people.
     */
    @PreAuthorize("hasAuthority('attendance:create')")
    public BulkAttendanceResponse saveBulk(BulkAttendanceRequest request) {
        siteAccessGuard.assertCanAccess(request.siteId());
        periodLockGuard.assertOpen(request.siteId(), request.date(), PeriodLockGuard.Module.ATTENDANCE);
        SiteLookup.SiteInfo site = sites.require(request.siteId());

        List<UUID> workerIds = request.entries().stream().map(AttendanceEntry::workerId).toList();
        Map<UUID, Worker> workersById = workers
                .findByIdInAndOrgIdAndDeletedAtIsNull(workerIds, orgId()).stream()
                .collect(Collectors.toMap(Worker::getId, Function.identity()));
        Map<UUID, WageRate> rates = ratesFor(workerIds, request.date());
        BigDecimal otThreshold = settings().getOvertimeReasonRequiredAboveHours();

        List<EntryOutcome> outcomes = new ArrayList<>();
        int accepted = 0;
        int unchanged = 0;
        int rejected = 0;

        for (AttendanceEntry entry : request.entries()) {
            try {
                Outcome outcome = saveOne(entry, request, site, workersById, rates, otThreshold);
                outcomes.add(new EntryOutcome(entry.id(), entry.workerId(), outcome, null));
                if (outcome == Outcome.UNCHANGED) {
                    unchanged++;
                } else {
                    accepted++;
                }
            } catch (BusinessException e) {
                outcomes.add(new EntryOutcome(entry.id(), entry.workerId(), Outcome.REJECTED,
                        e.getMessage()));
                rejected++;
            }
        }

        audit.record("ATTENDANCE", request.siteId(), "BULK_SAVE", null,
                Map.of("date", request.date().toString(), "accepted", accepted,
                        "unchanged", unchanged, "rejected", rejected), null);
        return new BulkAttendanceResponse(accepted, unchanged, rejected, outcomes);
    }

    private Outcome saveOne(AttendanceEntry entry, BulkAttendanceRequest request,
                            SiteLookup.SiteInfo site, Map<UUID, Worker> workersById,
                            Map<UUID, WageRate> rates, BigDecimal otThreshold) {
        Worker worker = workersById.get(entry.workerId());
        if (worker == null) {
            throw BusinessException.notFound("Worker", entry.workerId());
        }

        Optional<AttendanceRecord> byId = records.findById(entry.id());
        if (byId.isPresent()) {
            AttendanceRecord existing = byId.get();
            // The offline replay: the device is re-sending a batch it already got through,
            // either because the response never arrived or because the queue drained twice.
            // Answering UNCHANGED rather than UPDATED lets the sync screen distinguish
            // "your edit landed" from "we already had this", which is the difference
            // between a useful count and a meaningless one.
            if (!existing.getWorkflowStatus().isEditable() || matches(existing, entry)) {
                return Outcome.UNCHANGED;
            }
            applyEntry(existing, entry, worker, site, rates, otThreshold);
            return Outcome.UPDATED;
        }

        // A different id for a worker already marked that day is a genuine duplicate, not a
        // replay: two people marked the same man, or one device re-created a lost draft.
        records.findLive(entry.workerId(), request.siteId(), request.date()).ifPresent(clash -> {
            throw BusinessException.conflict("attendance.duplicate",
                    worker.getFullName() + " is already marked for " + request.date()
                            + " at this site.");
        });

        AttendanceRecord record = new AttendanceRecord(entry.id(), orgId(), site.projectId(),
                site.id(), entry.workerId(), request.date(), entry.status());
        applyEntry(record, entry, worker, site, rates, otThreshold);
        records.save(record);
        return Outcome.CREATED;
    }

    /** True when the stored row already says exactly what this entry says. */
    private static boolean matches(AttendanceRecord record, AttendanceEntry entry) {
        return record.getStatus() == entry.status()
                && Objects.equals(record.getCheckInTime(), entry.checkInTime())
                && Objects.equals(record.getCheckOutTime(), entry.checkOutTime())
                && record.getBreakMinutes() == entry.breakMinutes()
                && sameHours(record.getEnteredHours(), entry.enteredHours())
                && Objects.equals(record.getOvertimeReason(), entry.overtimeReason())
                && Objects.equals(record.getBoqItemId(), entry.boqItemId())
                && Objects.equals(record.getWorkLocation(), entry.workLocation())
                && Objects.equals(record.getRemarks(), entry.remarks());
    }

    /**
     * Compares by value, not by scale. The stored figure comes back from numeric(6,2) as
     * 7.00 and a client sends 7 — {@code equals} calls those different, which would rewrite
     * an untouched row on every re-send and bump its version out from under the editor.
     */
    private static boolean sameHours(BigDecimal stored, BigDecimal sent) {
        return stored == null || sent == null
                ? stored == sent
                : stored.compareTo(sent) == 0;
    }

    /** Writes the day's facts onto a record and recomputes its hours and provisional money. */
    private void applyEntry(AttendanceRecord record, AttendanceEntry entry, Worker worker,
                            SiteLookup.SiteInfo site, Map<UUID, WageRate> rates,
                            BigDecimal otThreshold) {
        record.recordDay(entry.status(), entry.checkInTime(), entry.checkOutTime(),
                entry.breakMinutes(), entry.enteredHours(), entry.overtimeReason(),
                entry.boqItemId(), entry.workLocation(), entry.remarks());

        WageRate rate = rates.get(entry.workerId());
        AttendanceCalculator.Result result = calculate(record, worker, site, rate);

        // The threshold, not a blanket rule: on a seven-hour site nearly everyone books
        // some overtime daily, and demanding a reason for all of it makes the field
        // meaningless (docs/09).
        if (result.overtimeHours().compareTo(otThreshold) > 0
                && (entry.overtimeReason() == null || entry.overtimeReason().isBlank())) {
            throw new BusinessException("attendance.ot-reason-required",
                    worker.getFullName() + " has " + result.overtimeHours()
                            + " overtime hours, which needs a reason above " + otThreshold + ".");
        }
        record.applyHours(result);
    }

    @PreAuthorize("hasAuthority('attendance:create')")
    public AttendanceResponse update(UUID id, UpdateAttendanceRequest request) {
        AttendanceRecord record = requireRecord(id);
        siteAccessGuard.assertCanAccess(record.getSiteId());
        periodLockGuard.assertOpen(record.getSiteId(), record.getAttendanceDate(),
                PeriodLockGuard.Module.ATTENDANCE);
        if (!record.getWorkflowStatus().isEditable()) {
            throw new BusinessException("attendance.not-editable",
                    "This record is " + record.getWorkflowStatus().name().toLowerCase()
                            + " and can no longer be edited. Raise a correction instead.");
        }
        if (!record.getVersion().equals(request.version())) {
            throw new OptimisticLockingFailureException("Attendance " + id + " was changed by someone else");
        }

        Worker worker = requireWorker(record.getWorkerId());
        SiteLookup.SiteInfo site = sites.require(record.getSiteId());
        AttendanceEntry asEntry = new AttendanceEntry(id, record.getWorkerId(), request.status(),
                request.checkInTime(), request.checkOutTime(), request.breakMinutes(),
                request.enteredHours(), request.overtimeReason(), request.boqItemId(),
                request.workLocation(), request.remarks());
        applyEntry(record, asEntry, worker, site,
                ratesFor(List.of(record.getWorkerId()), record.getAttendanceDate()),
                settings().getOvertimeReasonRequiredAboveHours());

        return toResponse(record, worker.getFullName());
    }

    /**
     * Changes a row that has already been verified and paid.
     *
     * <p>Held apart from {@link #update} because the two are different acts. An update is a
     * supervisor changing his mind before anyone has acted on the row; this is an engineer
     * amending a day the worker has already been paid for, so it needs a stronger
     * permission, a stated reason, a field-by-field trail, and a ledger entry moving the
     * money by the difference.</p>
     *
     * <p>The recalculation uses the rates frozen onto the row at verification, never
     * today's. A correction is meant to fix what the day says, not to reprice it — and a
     * wage revision signed since then must not leak in through the back door.</p>
     */
    @PreAuthorize("hasAuthority('attendance:correct')")
    public AttendanceResponse correct(UUID id, CorrectAttendanceRequest request) {
        AttendanceRecord record = requireRecord(id);
        siteAccessGuard.assertCanAccess(record.getSiteId());
        // A closed month stays closed to corrections too: the lock is the whole guarantee.
        periodLockGuard.assertOpen(record.getSiteId(), record.getAttendanceDate(),
                PeriodLockGuard.Module.ATTENDANCE);
        if (record.getWorkflowStatus() != WorkflowStatus.VERIFIED) {
            throw new BusinessException("attendance.not-correctable",
                    "Only a verified row is corrected. This one is "
                            + record.getWorkflowStatus().name().toLowerCase()
                            + ", so edit it directly instead.");
        }
        if (!record.getVersion().equals(request.version())) {
            throw new OptimisticLockingFailureException("Attendance " + id + " was changed by someone else");
        }

        Worker worker = requireWorker(record.getWorkerId());
        SiteLookup.SiteInfo site = sites.require(record.getSiteId());

        BigDecimal wageBefore = nullToZero(record.getComputedWageAmount());
        BigDecimal otBefore = nullToZero(record.getComputedOtAmount());
        List<AttendanceCorrection> trail = describeChanges(record, request);

        record.recordDay(request.status(), request.checkInTime(), request.checkOutTime(),
                request.breakMinutes(), request.enteredHours(), request.overtimeReason(),
                request.boqItemId(), request.workLocation(), request.remarks());

        AttendanceCalculator.Result result = calculateAtFrozenRates(record, worker, site);
        BigDecimal otThreshold = settings().getOvertimeReasonRequiredAboveHours();
        if (result.overtimeHours().compareTo(otThreshold) > 0
                && (request.overtimeReason() == null || request.overtimeReason().isBlank())) {
            throw new BusinessException("attendance.ot-reason-required",
                    worker.getFullName() + " has " + result.overtimeHours()
                            + " overtime hours, which needs a reason above " + otThreshold + ".");
        }
        record.applyHours(result);

        BigDecimal delta = result.totalAmount().subtract(wageBefore.add(otBefore));
        ledger.postAttendanceAdjustment(record, delta, request.correctionReason());

        corrections.saveAll(trail);
        audit.record("ATTENDANCE", id, "CORRECT", null,
                Map.of("fields", trail.stream().map(AttendanceCorrection::getFieldName).toList(),
                        "ledgerDelta", delta.toPlainString()),
                request.correctionReason());
        return toResponse(record, worker.getFullName());
    }

    /**
     * The fields a correction moved, one row apiece. Only what actually changed is recorded
     * — a trail padded with unchanged fields is one nobody reads.
     */
    private List<AttendanceCorrection> describeChanges(AttendanceRecord record,
                                                       CorrectAttendanceRequest request) {
        Instant now = Instant.now();
        UUID by = currentUser.currentUserIdOrNull();
        List<AttendanceCorrection> trail = new ArrayList<>();
        BiConsumer<String, String[]> add = (field, values) -> {
            if (!Objects.equals(values[0], values[1])) {
                trail.add(AttendanceCorrection.applied(orgId(), record.getId(), field,
                        values[0], values[1], request.correctionReason(), by, now));
            }
        };
        add.accept("status", new String[]{
                str(record.getStatus()), str(request.status())});
        add.accept("enteredHours", new String[]{
                str(record.getEnteredHours()), str(request.enteredHours())});
        add.accept("checkInTime", new String[]{
                str(record.getCheckInTime()), str(request.checkInTime())});
        add.accept("checkOutTime", new String[]{
                str(record.getCheckOutTime()), str(request.checkOutTime())});
        add.accept("breakMinutes", new String[]{
                String.valueOf(record.getBreakMinutes()), String.valueOf(request.breakMinutes())});
        add.accept("overtimeReason", new String[]{
                record.getOvertimeReason(), request.overtimeReason()});
        return trail;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    // ------------------------------------------------------------------ workflow

    @PreAuthorize("hasAuthority('attendance:submit')")
    public int submit(UUID siteId, LocalDate date) {
        siteAccessGuard.assertCanAccess(siteId);
        periodLockGuard.assertOpen(siteId, date, PeriodLockGuard.Module.ATTENDANCE);

        Instant now = Instant.now();
        UUID by = currentUser.currentUserIdOrNull();
        // Not isEditable: a submitted row stays editable, and re-submitting it would stamp a
        // new submitted-at over the original and shuffle the engineer's queue for nothing.
        List<AttendanceRecord> drafts = records.findLiveForDay(siteId, date).stream()
                .filter(r -> r.getWorkflowStatus().isSubmittable())
                .toList();
        if (drafts.isEmpty()) {
            throw new BusinessException("attendance.nothing-to-submit",
                    "There is nothing left to submit for " + date + ".");
        }
        drafts.forEach(record -> record.submit(now, by));
        audit.record("ATTENDANCE", siteId, "SUBMIT", null,
                Map.of("date", date.toString(), "count", drafts.size()), null);
        return drafts.size();
    }

    /**
     * Engineer sign-off. Verifying resolves the rate that was in force on the attendance
     * date, pins it to the record, and posts the earnings to the worker's ledger.
     */
    @PreAuthorize("hasAuthority('attendance:verify')")
    public int verify(VerifyAttendanceRequest request) {
        List<AttendanceRecord> batch = records.findByIdInAndOrgId(request.ids(), orgId());
        if (batch.size() != Set.copyOf(request.ids()).size()) {
            throw BusinessException.notFound("Attendance", "one or more of the requested ids");
        }
        siteAccessGuard.assertCanAccessAll(batch.stream()
                .map(AttendanceRecord::getSiteId).collect(Collectors.toSet()));

        Instant now = Instant.now();
        UUID by = currentUser.currentUserIdOrNull();
        int affected = 0;

        for (AttendanceRecord record : batch) {
            periodLockGuard.assertOpen(record.getSiteId(), record.getAttendanceDate(),
                    PeriodLockGuard.Module.ATTENDANCE);
            if (record.getWorkflowStatus() != WorkflowStatus.SUBMITTED) {
                // Not an error: a second click on a batch already verified should be quiet.
                continue;
            }
            if (request.action() == VerifyAttendanceRequest.Action.REJECT) {
                record.reject(now, by, request.remarks());
            } else {
                freezeAndPost(record, now, by);
            }
            affected++;
        }

        audit.record("ATTENDANCE", null, request.action().name(), null,
                Map.of("ids", request.ids().stream().map(UUID::toString).toList(),
                        "affected", affected),
                request.remarks());
        return affected;
    }

    private void freezeAndPost(AttendanceRecord record, Instant now, UUID by) {
        Worker worker = requireWorker(record.getWorkerId());
        SiteLookup.SiteInfo site = sites.require(record.getSiteId());
        WageRate rate = wageRates.findEffectiveOn(record.getWorkerId(), record.getAttendanceDate())
                .orElseThrow(() -> new BusinessException("attendance.no-wage-rate",
                        worker.getFullName() + " has no wage rate effective on "
                                + record.getAttendanceDate() + ", so his pay cannot be computed."));

        record.freezeWage(rate.getId(), rate.getNormalRate(), rate.getOvertimeRate(),
                calculate(record, worker, site, rate));
        record.verify(now, by);
        ledger.postAttendanceEarnings(record);
    }

    /**
     * Closes a month for a site. Verified rows become {@code LOCKED}, which is terminal:
     * from here a mistake is corrected by an adjustment, never by an edit.
     *
     * <p>Anything still in draft or awaiting verification is reported back rather than
     * swept along. A month closed over unverified attendance is a month whose labour cost
     * is wrong, and the administrator should see that before deciding.</p>
     */
    @PreAuthorize("hasAuthority('attendance:lock')")
    public LockResult lockPeriod(UUID siteId, YearMonth yearMonth) {
        siteAccessGuard.assertCanAccess(siteId);
        LocalDate from = PeriodLockService.firstDayOf(yearMonth);
        LocalDate to = PeriodLockService.lastDayOf(yearMonth);

        long unfinished = records.findForPeriod(siteId, from, to).stream()
                .filter(r -> r.getWorkflowStatus() == WorkflowStatus.DRAFT
                        || r.getWorkflowStatus() == WorkflowStatus.SUBMITTED
                        || r.getWorkflowStatus() == WorkflowStatus.REJECTED)
                .count();

        boolean locked = periodLockService.lock(orgId(), siteId,
                PeriodLockGuard.Module.ATTENDANCE, yearMonth, currentUser.currentUserIdOrNull());
        if (!locked) {
            throw BusinessException.conflict("period.already-locked",
                    yearMonth + " is already locked for attendance at this site.");
        }

        Instant now = Instant.now();
        List<AttendanceRecord> verified = records.findVerifiedInPeriod(siteId, from, to);
        verified.forEach(record -> record.lock(now));

        audit.record("PERIOD_LOCK", siteId, "LOCK", null,
                Map.of("yearMonth", yearMonth.toString(), "module", "ATTENDANCE",
                        "lockedRecords", verified.size(), "unfinishedRecords", unfinished), null);
        return new LockResult(siteId, yearMonth.toString(), verified.size(), unfinished);
    }

    /** @param unfinishedRecords rows left in draft, submitted or rejected when the month closed */
    public record LockResult(UUID siteId, String yearMonth, int lockedRecords, long unfinishedRecords) {
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('attendance:create')")
    public PageResponse<AttendanceResponse> list(UUID siteId, UUID workerId, WorkflowStatus status,
                                                 LocalDate from, LocalDate to, Pageable pageable) {
        if (siteId != null) {
            siteAccessGuard.assertCanAccess(siteId);
        }
        boolean restricted = !currentUser.seesAllSites();
        Collection<UUID> visible = restricted ? currentUser.assignedSiteIds() : List.of();
        if (restricted && visible.isEmpty()) {
            return new PageResponse<>(List.of(), 0, pageable.getPageSize(), 0, 0, true, true);
        }
        Map<UUID, String> names = new HashMap<>();
        return PageResponse.from(
                records.search(orgId(), siteId, workerId, status, from, to, restricted, visible, pageable),
                record -> toResponse(record, names.computeIfAbsent(record.getWorkerId(),
                        id -> workers.findById(id).map(Worker::getFullName).orElse(null))));
    }

    // ------------------------------------------------------------------ internals

    private AttendanceCalculator.Result calculate(AttendanceRecord record, Worker worker,
                                                  SiteLookup.SiteInfo site, WageRate rate) {
        return AttendanceCalculator.calculate(new AttendanceCalculator.Input(
                record.getStatus(),
                record.getCheckInTime(),
                record.getCheckOutTime(),
                record.getBreakMinutes(),
                record.getEnteredHours(),
                site.standardShiftHours(),
                worker.getWageType(),
                rate == null ? BigDecimal.ZERO : rate.getNormalRate(),
                rate == null ? BigDecimal.ZERO : rate.getOvertimeRate(),
                site.monthlyWageDays()));
    }

    /**
     * Recalculates a verified row against the rates already pinned to it, so a correction
     * moves the hours without ever repricing the day.
     */
    private AttendanceCalculator.Result calculateAtFrozenRates(AttendanceRecord record,
                                                               Worker worker,
                                                               SiteLookup.SiteInfo site) {
        return AttendanceCalculator.calculate(new AttendanceCalculator.Input(
                record.getStatus(),
                record.getCheckInTime(),
                record.getCheckOutTime(),
                record.getBreakMinutes(),
                record.getEnteredHours(),
                site.standardShiftHours(),
                worker.getWageType(),
                nullToZero(record.getAppliedNormalRate()),
                nullToZero(record.getAppliedOtRate()),
                site.monthlyWageDays()));
    }

    private Map<UUID, WageRate> ratesFor(Collection<UUID> workerIds, LocalDate date) {
        if (workerIds.isEmpty()) {
            return Map.of();
        }
        return wageRates.findEffectiveOnFor(workerIds, date).stream()
                .collect(Collectors.toMap(WageRate::getWorkerId, Function.identity(),
                        (a, b) -> a));
    }

    private boolean isPeriodLocked(UUID siteId, LocalDate date) {
        try {
            periodLockGuard.assertOpen(siteId, date, PeriodLockGuard.Module.ATTENDANCE);
            return false;
        } catch (BusinessException e) {
            return true;
        }
    }

    /**
     * The org's labour policy, or the default one if nobody has written a row yet.
     *
     * <p>This used to throw, and the effect was that an organisation whose settings row had
     * never been inserted got a 422 on the muster itself — a supervisor with no workers on a
     * new site was told that "labour settings have not been configured", which is neither
     * something he can act on nor the thing actually stopping him. Nothing here reads more
     * than the overtime threshold, and that threshold has a default declared twice already
     * (the column default in V1 and the field initialiser on the entity). Returning it is the
     * same answer an inserted row would have given, so the screen loads and says what is
     * really missing, which is men.</p>
     */
    private LabourSettings settings() {
        return labourSettings.findByOrgId(orgId()).orElseGet(() -> new LabourSettings(orgId()));
    }

    private AttendanceRecord requireRecord(UUID id) {
        AttendanceRecord record = records.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Attendance", id));
        if (!Objects.equals(record.getOrgId(), orgId())) {
            throw BusinessException.notFound("Attendance", id);
        }
        return record;
    }

    private Worker requireWorker(UUID id) {
        return workers.findByIdAndOrgIdAndDeletedAtIsNull(id, orgId())
                .orElseThrow(() -> BusinessException.notFound("Worker", id));
    }

    static AttendanceResponse toResponse(AttendanceRecord r, String workerName) {
        return new AttendanceResponse(r.getId(), r.getSiteId(), r.getProjectId(), r.getWorkerId(),
                workerName, r.getAttendanceDate(), r.getStatus(), r.getCheckInTime(),
                r.getCheckOutTime(), r.getBreakMinutes(), r.getEnteredHours(),
                r.getWorkedHours(), r.getRegularHours(),
                r.getOvertimeHours(), r.getBoqItemId(), r.getWorkLocation(), r.getOvertimeReason(),
                r.getRemarks(), r.getAppliedNormalRate(), r.getAppliedOtRate(),
                r.getComputedWageAmount(), r.getComputedOtAmount(), r.getTotalAmount(),
                r.getWorkflowStatus(), r.getRejectionReason(), r.getVersion());
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }
}

package in.nirman.modules.labour.service;

import in.nirman.common.BusinessException;
import in.nirman.common.DocumentNumberService;
import in.nirman.common.PageResponse;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.labour.api.dto.WorkerDtos.AllocateRequest;
import in.nirman.modules.labour.api.dto.WorkerDtos.AllocationResponse;
import in.nirman.modules.labour.api.dto.WorkerDtos.CreateWorkerRequest;
import in.nirman.modules.labour.api.dto.WorkerDtos.ReviseWageRequest;
import in.nirman.modules.labour.api.dto.WorkerDtos.UpdateWorkerRequest;
import in.nirman.modules.labour.api.dto.WorkerDtos.WageRateResponse;
import in.nirman.modules.labour.api.dto.WorkerDtos.WorkerResponse;
import in.nirman.modules.labour.domain.WageRate;
import in.nirman.modules.labour.domain.WageType;
import in.nirman.modules.labour.domain.Worker;
import in.nirman.modules.labour.domain.WorkerSiteAllocation;
import in.nirman.modules.labour.repository.AttendanceRecordRepository;
import in.nirman.modules.labour.repository.WageRateRepository;
import in.nirman.modules.labour.repository.WorkerAdvanceRepository;
import in.nirman.modules.labour.repository.WorkerLedgerEntryRepository;
import in.nirman.modules.labour.repository.WorkerRepository;
import in.nirman.modules.labour.repository.WorkerSiteAllocationRepository;
import in.nirman.modules.project.service.SiteLookup;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The worker master, his pay history and where he is posted.
 *
 * <p>Two rules carry the weight here, and both exist so that history cannot move:</p>
 * <ul>
 *   <li>A wage change is a <b>revision</b>, never an edit. The open rate is closed the day
 *       before the new one starts. Nothing updates a rate in place, so the figure that
 *       applied on any past date stays recoverable.</li>
 *   <li>A worker has at most one open posting, enforced by {@code uq_alloc_open}. Moving
 *       him closes the old one first — which is what stops the same man appearing on two
 *       rosters on the same morning.</li>
 * </ul>
 */
@Service
@Transactional
public class WorkerService {

    private final WorkerRepository workers;
    private final WageRateRepository wageRates;
    private final WorkerSiteAllocationRepository allocations;
    // Read only, and only ever counted: what has been recorded against a man is what decides
    // whether he can be deleted. All three are this module's own tables, so no *Lookup is
    // needed — the boundary rule is about reaching into another module, not into this one.
    private final AttendanceRecordRepository attendance;
    private final WorkerAdvanceRepository workerAdvances;
    private final WorkerLedgerEntryRepository ledger;
    private final SiteLookup sites;
    private final SiteAccessGuard siteAccessGuard;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;
    private final DocumentNumberService documentNumbers;

    public WorkerService(WorkerRepository workers, WageRateRepository wageRates,
                         WorkerSiteAllocationRepository allocations,
                         AttendanceRecordRepository attendance,
                         WorkerAdvanceRepository workerAdvances,
                         WorkerLedgerEntryRepository ledger, SiteLookup sites,
                         SiteAccessGuard siteAccessGuard, CurrentUserProvider currentUser,
                         AuditService audit, DocumentNumberService documentNumbers) {
        this.workers = workers;
        this.wageRates = wageRates;
        this.allocations = allocations;
        this.attendance = attendance;
        this.workerAdvances = workerAdvances;
        this.ledger = ledger;
        this.sites = sites;
        this.siteAccessGuard = siteAccessGuard;
        this.currentUser = currentUser;
        this.audit = audit;
        this.documentNumbers = documentNumbers;
    }

    /**
     * An unfiltered list means "the men I can see", not "every man in the company": for a
     * site-scoped role it narrows to whoever is posted to their sites today. Asking for a
     * site is a filter on top of that scope, never a way around it.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('worker:read')")
    public PageResponse<WorkerResponse> list(UUID siteId, UUID contractorId, UUID skillId,
                                             Boolean active, String q, Pageable pageable) {
        if (siteId != null) {
            siteAccessGuard.assertCanAccess(siteId);
        }
        boolean restricted = !currentUser.seesAllSites();
        Collection<UUID> visible = restricted ? currentUser.assignedSiteIds() : List.of();
        if (restricted && visible.isEmpty()) {
            // IN () is not valid SQL, and a user posted nowhere has nobody to see anyway.
            return new PageResponse<>(List.of(), 0, pageable.getPageSize(), 0, 0, true, true);
        }
        return PageResponse.from(
                workers.search(orgId(), siteId, LocalDate.now(), contractorId, skillId, active,
                        blankToEmpty(q), restricted, visible, pageable),
                this::toResponse);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('worker:read')")
    public WorkerResponse get(UUID id) {
        return toResponse(requireWorker(id));
    }

    /**
     * Takes a man on, and asks for as little as the roll actually needs.
     *
     * <p>Three of the fields defend themselves rather than the caller. His number is the
     * server's to assign unless one was given, because a supervisor inventing one at the
     * gate is how two men end up sharing a number. His employment and wage basis default to
     * contract labour on a daily wage. And his overtime rate, if nobody stated one, is the
     * plain hourly rate implied by the day's wage and the site's own shift — the site
     * already knows where its working hours end, so nobody has to type where overtime
     * begins.</p>
     */
    @PreAuthorize("hasAuthority('worker:write')")
    public WorkerResponse create(CreateWorkerRequest request) {
        UUID org = orgId();
        LocalDate from = request.joiningDate() == null ? LocalDate.now() : request.joiningDate();
        String code = blankToNull(request.workerCode());
        if (code == null) {
            code = documentNumbers.next(org, DocumentNumberService.DocType.WORKER, from);
        } else if (workers.existsByOrgIdAndWorkerCode(org, code)) {
            throw BusinessException.conflict("worker.code-taken",
                    "A worker with code '" + code + "' already exists.");
        }
        WageType wageType = request.wageType() == null ? WageType.DAILY : request.wageType();
        Worker worker = new Worker(org, code, request.fullName(), wageType);
        worker.setMobile(request.mobile());
        worker.setSkillCategoryId(request.skillCategoryId());
        worker.setEmploymentType(request.employmentType() == null
                ? Worker.EmploymentType.CONTRACT : request.employmentType());
        worker.setLabourSupplierId(request.labourSupplierId());
        worker.setJoiningDate(from);
        worker.setAadhaarLast4(request.aadhaarLast4());
        worker.setBankAccountNo(request.bankAccountNo());
        worker.setBankIfsc(request.bankIfsc());
        worker.setBankName(request.bankName());
        workers.save(worker);

        // A worker with no rate cannot be paid and a worker with no posting appears on no
        // roster, so both are accepted here rather than forcing three calls to be useful.
        if (request.normalRate() != null) {
            // Setting pay is wage:write wherever it happens. Without this, the rate field on
            // the creation call would be a way round the permission that guards revisions —
            // and a supervisor could open his own crew on any rate he liked.
            if (!currentUser.hasPermission("wage:write")) {
                throw BusinessException.forbidden(
                        "You can add the worker, but his rate has to be set by the office.");
            }
            wageRates.save(new WageRate(org, worker.getId(), request.normalRate(),
                    overtimeRate(request, wageType), from));
        }
        if (request.siteId() != null) {
            siteAccessGuard.assertCanAccess(request.siteId());
            allocations.save(new WorkerSiteAllocation(org, worker.getId(), request.siteId(), from));
        }

        audit.record("WORKER", worker.getId(), "CREATE", null,
                Map.of("workerCode", worker.getWorkerCode(), "fullName", worker.getFullName()), null);
        return toResponse(worker);
    }

    @PreAuthorize("hasAuthority('worker:write')")
    public WorkerResponse update(UUID id, UpdateWorkerRequest request) {
        Worker worker = requireWorker(id);
        if (!worker.getVersion().equals(request.version())) {
            throw new OptimisticLockingFailureException("Worker " + id + " was changed by someone else");
        }
        Map<String, Object> before = Map.of("fullName", worker.getFullName(),
                "wageType", worker.getWageType().name(), "active", worker.isActive());
        worker.setFullName(request.fullName());
        worker.setMobile(request.mobile());
        worker.setSkillCategoryId(request.skillCategoryId());
        worker.setEmploymentType(request.employmentType());
        worker.setLabourSupplierId(request.labourSupplierId());
        worker.setWageType(request.wageType());
        worker.setJoiningDate(request.joiningDate());
        worker.setExitDate(request.exitDate());
        worker.setAadhaarLast4(request.aadhaarLast4());
        worker.setBankAccountNo(request.bankAccountNo());
        worker.setBankIfsc(request.bankIfsc());
        worker.setBankName(request.bankName());
        worker.setActive(request.active());
        audit.record("WORKER", id, "UPDATE", before,
                Map.of("fullName", worker.getFullName(), "wageType", worker.getWageType().name(),
                        "active", worker.isActive()), null);
        return toResponse(worker);
    }

    /**
     * Takes a man off the books, which is not the same as ending his employment.
     *
     * <p>Deletion here is for a row that should not exist — the same man entered twice at the
     * gate under two numbers, a name typed into the wrong site. A man who actually worked and
     * has stopped is marked <em>Inactive</em>, and stays visible on purpose: his months carry wages
     * that have already been reported, and hiding him would quietly change what the site cost.
     * {@link #assertDeletable} is what keeps the two apart, and it names what is holding him so
     * the answer is actionable rather than a flat refusal.</p>
     *
     * <p>His pay history and his postings stay where they are. They are the scaffolding of a
     * worker rather than records against him, they are unreachable once he is deleted, and
     * removing them would be the one part of this act that could not be undone by hand.</p>
     */
    @PreAuthorize("hasAuthority('worker:delete')")
    public WorkerResponse delete(UUID id, String reason) {
        // Also the IDOR fence: an engineer deletes on his own sites, not on an id he guessed.
        Worker worker = requireWorker(id);
        assertDeletable(worker);
        Instant at = Instant.now();
        worker.delete(at, currentUser.currentUserIdOrNull(), reason);
        audit.record("WORKER", id, "DELETE",
                Map.of("workerCode", worker.getWorkerCode(), "fullName", worker.getFullName()),
                Map.of("deletedAt", at.toString(), "reason", reason), reason);
        return toResponse(worker);
    }

    /**
     * Refuses a worker anything has been recorded against, in the words the refusal will be
     * read in.
     *
     * <p>Ordered by how much it matters: attendance first, because that is what freezes a wage
     * rate into last month's labour cost. Advances and ledger postings follow — a man who has
     * been paid has a balance, and a balance for a worker nobody can open is a figure that can
     * never be settled.</p>
     */
    private void assertDeletable(Worker worker) {
        List<String> found = new ArrayList<>();
        long days = attendance.countByWorkerId(worker.getId());
        if (days > 0) {
            found.add(days + (days == 1 ? " attendance record" : " attendance records"));
        }
        long advances = workerAdvances.countByWorkerId(worker.getId());
        if (advances > 0) {
            found.add(advances + (advances == 1 ? " advance" : " advances"));
        }
        long postings = ledger.countByWorkerId(worker.getId());
        if (postings > 0) {
            found.add(postings + (postings == 1 ? " ledger entry" : " ledger entries"));
        }
        if (found.isEmpty()) {
            return;
        }
        throw new BusinessException("worker.has-records",
                "This worker has " + join(found) + " recorded against him. A man who has worked "
                        + "cannot be taken off the books — mark him Inactive instead, which stops "
                        + "him appearing on the roll, keeps his figures, and can be undone when "
                        + "he returns.");
    }

    /** "3 attendance records and 1 advance", not "3 attendance records, 1 advance". */
    private static String join(List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1))
                + " and " + parts.get(parts.size() - 1);
    }

    // ------------------------------------------------------------------ wage rates

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('wage:read')")
    public List<WageRateResponse> wageHistory(UUID workerId) {
        requireWorker(workerId);
        return wageRates.findByWorkerIdOrderByEffectiveFromDesc(workerId).stream()
                .map(WorkerService::toResponse)
                .toList();
    }

    /**
     * Closes the open rate the day before the new one begins and opens the replacement.
     * Historical attendance is untouched: verified rows carry their own frozen rate, so a
     * revision cannot reprice a month that has already been settled.
     */
    @PreAuthorize("hasAuthority('wage:write')")
    public WageRateResponse reviseWage(UUID workerId, ReviseWageRequest request) {
        Worker worker = requireWorker(workerId);
        wageRates.findByWorkerIdAndEffectiveToIsNull(workerId).ifPresent(open -> {
            if (!open.getEffectiveFrom().isBefore(request.effectiveFrom())) {
                throw new BusinessException("wage.not-after-current",
                        "The new rate must start after the current one, which began on "
                                + open.getEffectiveFrom() + ".");
            }
            // Must reach the database before the insert below, or uq_wage_open sees two
            // open rows and rejects the revision.
            open.closeOn(request.effectiveFrom().minusDays(1));
            wageRates.saveAndFlush(open);
        });

        WageRate revised = new WageRate(worker.getOrgId(), workerId, request.normalRate(),
                request.overtimeRate(), request.effectiveFrom());
        revised.setRemarks(request.remarks());
        wageRates.save(revised);

        audit.record("WAGE_RATE", revised.getId(), "REVISE", null,
                Map.of("workerId", workerId.toString(),
                        "normalRate", request.normalRate(),
                        "overtimeRate", request.overtimeRate(),
                        "effectiveFrom", request.effectiveFrom().toString()),
                request.remarks());
        return toResponse(revised);
    }

    // ------------------------------------------------------------------ allocations

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('worker:read')")
    public List<AllocationResponse> allocationHistory(UUID workerId) {
        requireWorker(workerId);
        return allocations.findByWorkerIdOrderByEffectiveFromDesc(workerId).stream()
                .map(WorkerService::toResponse)
                .toList();
    }

    /**
     * Moves a worker to a site, closing his previous posting the day before.
     *
     * <p><b>The guard is on where he is, not on where he is going.</b> A transfer is a
     * handover between two supervisors: the sender must hold the site the man is posted to
     * now — he has to be yours before you can give him away — while the destination only
     * has to be a live site in the company. Guarding the destination instead would mean a
     * transfer could only ever be made by someone who already held both sites, which is to
     * say almost never, and the men would move on paper by nobody and appear on no roster.</p>
     *
     * <p>A worker with no open posting is a different act: that is a first posting, not a
     * handover, so it must be to a site the caller actually holds. Otherwise "create a
     * worker, then post him anywhere" would be a way around the fence.</p>
     *
     * <p>Crossing into another project is the same operation — sites carry the project, and
     * men are lent between contracts often enough that forbidding it would only mean the
     * move went unrecorded.</p>
     */
    @PreAuthorize("hasAuthority('worker:write')")
    public AllocationResponse allocate(UUID workerId, AllocateRequest request) {
        Worker worker = requireWorker(workerId);
        UUID currentSiteId = allocations.findByWorkerIdAndEffectiveToIsNull(workerId)
                .map(WorkerSiteAllocation::getSiteId)
                .orElse(null);
        siteAccessGuard.assertCanAccess(currentSiteId == null ? request.siteId() : currentSiteId);
        if (!sites.isLiveInOrg(request.siteId())) {
            throw BusinessException.notFound("Site", request.siteId());
        }

        allocations.findByWorkerIdAndEffectiveToIsNull(workerId).ifPresent(open -> {
            if (open.getSiteId().equals(request.siteId())) {
                throw BusinessException.conflict("allocation.unchanged",
                        "This worker is already posted to that site.");
            }
            if (!open.getEffectiveFrom().isBefore(request.effectiveFrom())) {
                throw new BusinessException("allocation.not-after-current",
                        "The move must start after the current posting, which began on "
                                + open.getEffectiveFrom() + ".");
            }
            // Must be flushed before the insert below, or uq_alloc_open rejects the pair.
            open.closeOn(request.effectiveFrom().minusDays(1));
            allocations.saveAndFlush(open);
        });

        WorkerSiteAllocation moved = new WorkerSiteAllocation(worker.getOrgId(), workerId,
                request.siteId(), request.effectiveFrom());
        allocations.save(moved);
        // Both ends recorded: a man leaving one site and arriving at another is one event,
        // and the site he left is the question anyone auditing this will ask first.
        audit.record("WORKER", workerId, currentSiteId == null ? "ALLOCATE" : "TRANSFER", null,
                Map.of("fromSiteId", String.valueOf(currentSiteId),
                        "siteId", request.siteId().toString(),
                        "effectiveFrom", request.effectiveFrom().toString()), null);
        return toResponse(moved);
    }

    // ------------------------------------------------------------------ internals

    private Worker requireWorker(UUID id) {
        Worker worker = workers.findByIdAndOrgIdAndDeletedAtIsNull(id, orgId())
                .orElseThrow(() -> BusinessException.notFound("Worker", id));
        assertVisible(worker);
        return worker;
    }

    /**
     * The IDOR fence for one worker. A site-scoped user reaches a man through a posting: if
     * he has ever been posted to one of their sites they may open him, and otherwise not.
     *
     * <p>Two deliberate choices. The check spans <em>every</em> allocation rather than
     * today's, because a supervisor still has to be able to open the man whose name is on
     * last month's muster roll for their site. And a worker with no allocation at all is
     * visible to everyone: he has just been created and has not been posted anywhere yet,
     * so the alternative would be an engineer unable to open the worker he just added.</p>
     */
    private void assertVisible(Worker worker) {
        if (currentUser.seesAllSites()) {
            return;
        }
        List<WorkerSiteAllocation> postings =
                allocations.findByWorkerIdOrderByEffectiveFromDesc(worker.getId());
        if (postings.isEmpty()) {
            return;
        }
        Set<UUID> mine = currentUser.assignedSiteIds();
        if (postings.stream().noneMatch(posting -> mine.contains(posting.getSiteId()))) {
            throw BusinessException.forbidden("This worker is not posted to any of your sites.");
        }
    }

    private WorkerResponse toResponse(Worker worker) {
        LocalDate today = LocalDate.now();
        WageRateResponse currentRate = wageRates.findEffectiveOn(worker.getId(), today)
                .map(WorkerService::toResponse)
                .orElse(null);
        UUID currentSite = allocations.findEffectiveOn(worker.getId(), today)
                .map(WorkerSiteAllocation::getSiteId)
                .orElse(null);
        return new WorkerResponse(worker.getId(), worker.getWorkerCode(), worker.getFullName(),
                worker.getMobile(), worker.getPhotoAttachmentId(), worker.getSkillCategoryId(),
                worker.getEmploymentType(), worker.getLabourSupplierId(), worker.getWageType(),
                worker.getJoiningDate(), worker.getExitDate(), worker.getAadhaarLast4(),
                worker.getBankAccountNo(), worker.getBankIfsc(), worker.getBankName(),
                worker.isActive(), currentRate, currentSite, worker.getVersion());
    }

    private static WageRateResponse toResponse(WageRate rate) {
        return new WageRateResponse(rate.getId(), rate.getWorkerId(), rate.getNormalRate(),
                rate.getOvertimeRate(), rate.getEffectiveFrom(), rate.getEffectiveTo(),
                rate.getRemarks());
    }

    private static AllocationResponse toResponse(WorkerSiteAllocation allocation) {
        return new AllocationResponse(allocation.getId(), allocation.getWorkerId(),
                allocation.getSiteId(), allocation.getEffectiveFrom(), allocation.getEffectiveTo());
    }

    /**
     * What an hour past the site's working day is worth.
     *
     * <p>Nobody is asked for this. The site already carries where its regular hours end, and
     * the field data settled what the hour beyond them pays: the plain hourly rate, with no
     * premium — OT income ÷ OT hours came back equal to wage ÷ shift (assumption 7). So a
     * daily wage of 625 on a seven-hour site is 89.2857 an hour, and that is the overtime
     * rate. A caller that states its own rate keeps it.</p>
     *
     * <p>Only a daily wage can be divided this way. An hourly worker is already paid by the
     * hour, and a monthly one needs the site's {@code monthlyWageDays} before the division
     * means anything — neither is a guess worth making here, so both wait for the office to
     * state a rate.</p>
     */
    private BigDecimal overtimeRate(CreateWorkerRequest request, WageType wageType) {
        if (request.overtimeRate() != null) {
            return request.overtimeRate();
        }
        if (wageType == WageType.HOURLY) {
            return request.normalRate();
        }
        if (wageType != WageType.DAILY || request.siteId() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal shiftHours = sites.require(request.siteId()).standardShiftHours();
        return request.normalRate().divide(shiftHours, 4, RoundingMode.HALF_UP);
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }

    /** No search term travels as an empty string, never null: see {@link WorkerRepository#search}. */
    private static String blankToEmpty(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

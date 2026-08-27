package in.nirman.modules.dpr.service;

import in.nirman.common.BusinessException;
import in.nirman.common.DocumentNumberService;
import in.nirman.common.PageResponse;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.dpr.api.dto.DprDtos.AttachPhotoRequest;
import in.nirman.modules.dpr.api.dto.DprDtos.CreateDprRequest;
import in.nirman.modules.dpr.api.dto.DprDtos.DprResponse;
import in.nirman.modules.dpr.api.dto.DprDtos.MachineryInput;
import in.nirman.modules.dpr.api.dto.DprDtos.PlantRateInput;
import in.nirman.modules.dpr.api.dto.DprDtos.SetPlantRatesRequest;
import in.nirman.modules.dpr.api.dto.DprDtos.UpdateDprRequest;
import in.nirman.modules.dpr.api.dto.DprDtos.VerifyDprRequest;
import in.nirman.modules.dpr.api.dto.DprDtos.WorkItemInput;
import in.nirman.modules.dpr.domain.DailyProgressReport;
import in.nirman.modules.dpr.domain.DailyProgressReport.NonOperationalCause;
import in.nirman.modules.dpr.domain.DailyProgressReport.Workflow;
import in.nirman.modules.dpr.domain.DprLabour;
import in.nirman.modules.dpr.domain.DprMachinery;
import in.nirman.modules.dpr.domain.DprPhoto;
import in.nirman.modules.dpr.domain.DprWorkItem;
import in.nirman.modules.dpr.repository.DailyProgressReportRepository;
import in.nirman.modules.dpr.repository.DprLabourRepository;
import in.nirman.modules.dpr.repository.DprMachineryRepository;
import in.nirman.modules.dpr.repository.DprPhotoRepository;
import in.nirman.modules.dpr.repository.DprWorkItemRepository;
import in.nirman.modules.dpr.service.DprPrefillService.Rollup;
import in.nirman.modules.labour.service.LabourLookup;
import in.nirman.modules.project.service.BoqLookup;
import in.nirman.modules.project.service.BoqProgress;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Daily progress reports: draft, submit, verify, approve.
 *
 * <p>Seven rules carry the weight.</p>
 *
 * <p><b>The report has two halves and two authors.</b> The supervisor says what the day
 * <i>was</i> — whether the site worked, in what conditions, and what plant stood on the site —
 * and hands it over; the engineer says what was <i>built</i> and signs. The line runs where
 * claiming does: a quantity is a claim against the contract and a mixer's running hours are
 * not, which is why plant sits on the supervisor's side of it. That is not organisational
 * tidiness: a quantity on
 * this report becomes a claim against the contract the moment it is verified, so the man who
 * measures it and the man who signs for it are the same man, and the supervisor's half is
 * frozen before either happens. {@code dpr:draft} and {@code dpr:verify} already named those two
 * people, so no new permission was needed to draw the line — only enforcement, which lives in
 * {@link #update}.</p>
 *
 * <p><b>The day's account stays with the site until the report is signed.</b> The handover used
 * to freeze it, and a supervisor who noticed at seven in the evening that the weather was wrong
 * or that a second mixer had stood there all day had nowhere to put it. Any {@code dpr:draft}
 * holder posted to the site may still write that half of a SUBMITTED report — the same
 * supervisor or the one who relieved him, because a site is a shift roster. The engineer may
 * not: he signs somebody else's account of a day he may not have been on. The <b>figures</b>
 * still freeze at the handover, which is a different promise and unchanged.</p>
 *
 * <p><b>The engineer's signature is not the end of the document; the office's approval is.</b>
 * {@code dpr:approve} accepts a VERIFIED report, and it claims nothing — the quantities reached
 * the measurement book when the engineer signed, and this is the countersignature on figures
 * that already count.</p>
 *
 * <p><b>A day the site did not work is a different document, not an empty one.</b> It carries a
 * cause off a closed list and no work items at all, and it is submitted and signed like any
 * other report. A missing report says nothing — it might be rain, it might be a supervisor who
 * forgot — and only a report that says "no work, rain" can be counted towards a claim for
 * time.</p>
 *
 * <p><b>One report per site per day.</b> Enforced by {@code uq_dpr_site_date} and checked here
 * first so the answer is a sentence naming the existing report rather than a constraint
 * violation. A second report for the same day is not a new document, it is somebody who could
 * not find the first one.</p>
 *
 * <p><b>The rolled-up figures are refreshed while the report is a draft and frozen at
 * submission.</b> A stale draft is simply wrong, so every save re-reads labour, inventory and
 * expense through the same code path the prefill uses. Once submitted, nothing recomputes it:
 * the report is a document that gets printed and signed, and a correction to an underlying
 * record must show up as a <i>difference</i> rather than by rewriting what somebody signed.</p>
 *
 * <p><b>Verification is what claims work against the contract.</b> A supervisor describes the
 * day; measured quantities become dated entries in the measurement book only when the engineer
 * signs, through {@link BoqProgress}. This is why {@code boq:progress:record} is a separate
 * permission from {@code boq:write} — entering the contract and claiming work against it are
 * different acts.</p>
 *
 * <p><b>Verifying twice does not claim the work twice.</b> The posting is idempotent on
 * {@code (dpr_id, boq_item_id)}, the same guarantee the labour module gives about paying a
 * worker twice, and for the same reason: a double click must not move money.</p>
 */
@Service
@Transactional
public class DprService {

    /** The audit trail's name for this document family. */
    public static final String ENTITY_TYPE = "DPR";

    private final DailyProgressReportRepository reports;
    private final DprWorkItemRepository workItems;
    private final DprLabourRepository labourLines;
    private final DprMachineryRepository machinery;
    private final DprPhotoRepository photos;
    private final DprPrefillService prefill;
    private final BoqProgress boqProgress;
    private final BoqLookup boqItems;
    private final SiteLookup sites;
    private final SiteAccessGuard siteAccessGuard;
    private final DocumentNumberService documentNumbers;
    private final DprResponses responses;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public DprService(DailyProgressReportRepository reports, DprWorkItemRepository workItems,
                      DprLabourRepository labourLines, DprMachineryRepository machinery,
                      DprPhotoRepository photos, DprPrefillService prefill,
                      BoqProgress boqProgress, BoqLookup boqItems, SiteLookup sites,
                      SiteAccessGuard siteAccessGuard, DocumentNumberService documentNumbers,
                      DprResponses responses, CurrentUserProvider currentUser, AuditService audit) {
        this.reports = reports;
        this.workItems = workItems;
        this.labourLines = labourLines;
        this.machinery = machinery;
        this.photos = photos;
        this.prefill = prefill;
        this.boqProgress = boqProgress;
        this.boqItems = boqItems;
        this.sites = sites;
        this.siteAccessGuard = siteAccessGuard;
        this.documentNumbers = documentNumbers;
        this.responses = responses;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('dpr:draft')")
    public PageResponse<DprResponse> list(UUID siteId, Workflow status, LocalDate from,
                                          LocalDate to, Pageable pageable) {
        if (siteId != null) {
            siteAccessGuard.assertCanAccess(siteId);
        }
        boolean restricted = !currentUser.seesAllSites();
        Collection<UUID> visible = restricted ? currentUser.assignedSiteIds() : List.of();
        if (restricted && visible.isEmpty()) {
            return new PageResponse<>(List.of(), 0, pageable.getPageSize(), 0, 0, true, true);
        }
        return PageResponse.from(reports.search(orgId(), siteId, status, from, to, restricted,
                visible, pageable), responses::toResponse);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('dpr:draft')")
    public DprResponse get(UUID id) {
        DailyProgressReport report = require(id);
        siteAccessGuard.assertCanAccess(report.getSiteId());
        return responses.toResponse(report);
    }

    /** The report entity, for the PDF renderer. Guarded the same way {@link #get} is. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('dpr:draft')")
    public DailyProgressReport requireForRender(UUID id) {
        DailyProgressReport report = require(id);
        siteAccessGuard.assertCanAccess(report.getSiteId());
        return report;
    }

    // ------------------------------------------------------------------ draft

    /**
     * Opens the day's report. Idempotent on the client-generated id, so an offline device that
     * re-sends the same draft three times creates one report.
     */
    @PreAuthorize("hasAuthority('dpr:draft')")
    public DprResponse create(CreateDprRequest request) {
        siteAccessGuard.assertCanAccess(request.siteId());
        if (request.reportDate().isAfter(LocalDate.now())) {
            throw new BusinessException("dpr.future-date",
                    "A daily report cannot be written for " + request.reportDate()
                            + ", which has not happened yet.");
        }

        var replay = reports.findByIdAndOrgId(request.id(), orgId());
        if (replay.isPresent()) {
            // A phone can be holding a report that was deleted from the office while it had
            // no signal. Re-sending it must not quietly resurrect it, and must not collide
            // with its own primary key either — so it is told what happened to it.
            if (replay.get().isDeleted()) {
                throw BusinessException.conflict("dpr.deleted",
                        "%s was deleted%s. Write the day again if it still needs a report — this "
                                .formatted(replay.get().getDprNumber(),
                                        replay.get().getDeletedReason() == null ? ""
                                                : ": " + replay.get().getDeletedReason())
                                + "one is not coming back.");
            }
            return responses.toResponse(replay.get());   // the offline replay
        }
        reports.findBySiteIdAndReportDateAndDeletedAtIsNull(request.siteId(), request.reportDate())
                .ifPresent(existing -> {
                    throw BusinessException.conflict("dpr.already-exists",
                            "%s already covers %s at this site. Open it rather than starting a second one."
                                    .formatted(existing.getDprNumber(), request.reportDate()));
                });

        // Absent means the day was worked, which is what every report written before the
        // question existed meant, and what an offline client on an older build still means.
        boolean operational = request.siteOperational() == null || request.siteOperational();
        assertCauseGiven(operational, request.nonOperationalCause(), request.nonOperationalNote());
        assertNoWorkClaimedOnALostDay(operational, request.workItems());
        boolean writesEngineerHalf = carriesEngineerHalf(request.workItems(),
                request.workSummary(), request.delays(), request.safetyObservations(),
                request.qualityObservations(), request.instructionsReceived(),
                request.managementAttention(), request.nextDayPlan());
        assertMayWriteEngineerHalf(writesEngineerHalf);

        SiteLookup.SiteInfo site = sites.require(request.siteId());
        String number = documentNumbers.next(orgId(), DocumentNumberService.DocType.DPR,
                request.reportDate());
        DailyProgressReport report = new DailyProgressReport(request.id(), orgId(),
                site.projectId(), site.id(), request.reportDate(), number,
                currentUser.currentUserIdOrNull());
        report.recordOperationalStatus(operational, request.nonOperationalCause(),
                request.nonOperationalNote());
        report.recordConditions(request.weather(), request.temperatureC(),
                request.workingHoursLost());
        report.recordNarrative(request.workSummary(), request.delays(),
                request.safetyObservations(), request.qualityObservations(),
                request.instructionsReceived(), request.managementAttention(),
                request.nextDayPlan());
        reports.save(report);

        replaceWorkItems(report, request.workItems());
        replaceMachinery(report.getId(), request.machinery());
        refreshSnapshot(report);

        audit.record(ENTITY_TYPE, report.getId(), "CREATE", null,
                Map.of("dprNumber", number, "siteId", site.id().toString(),
                        "reportDate", request.reportDate().toString(),
                        "siteOperational", operational), null);
        return responses.toResponse(report);
    }

    /**
     * Edits the report, applying only the half the caller owns.
     *
     * <p>While it is a draft it is the supervisor's, and he writes the day's conditions. Once it
     * has been handed over it is the engineer's, and he writes the work and the observations on
     * top of a supervisor's half that is now frozen along with the figures. The two halves are
     * applied separately rather than checked and applied together, which is what stops a
     * supervisor's save — sending an empty work list because his screen has no work step — from
     * quietly deleting lines the engineer put on a report that was sent back to him.</p>
     */
    @PreAuthorize("hasAuthority('dpr:draft')")
    public DprResponse update(UUID id, UpdateDprRequest request) {
        DailyProgressReport report = require(id);
        siteAccessGuard.assertCanAccess(report.getSiteId());
        assertWritable(report);
        if (!report.getVersion().equals(request.version())) {
            throw new OptimisticLockingFailureException("DPR " + id + " was changed by someone else");
        }

        /*
          Who is writing which half, and it is no longer decided by the workflow state alone.

          Before the handover the report is one person's and he writes all of it he is allowed
          to. After it, the two halves are held by two people at once: the engineer has the
          work and the observations, and the day's account stays with whoever stood on the site
          — the same supervisor or the one who relieved him, because a site is a shift roster
          and the man who can see the second mixer is the man there now.

          The engineer is still refused it. He signs somebody else's account of a day he may
          not have been on, and an engineer who could quietly rewrite the weather he is signing
          under is not countersigning anything.
        */
        boolean beforeHandover = report.getWorkflowStatus().isEditable();
        boolean writesTheDaysAccount = beforeHandover || !currentUser.hasPermission("dpr:verify");
        // Null means "leave it as it is", not "the site worked". A flag that defaulted to true
        // on a field a client omitted would turn a rained-off day into a working one on the
        // next save, which is the one mistake this column exists to make impossible.
        boolean operational = request.siteOperational() == null
                ? report.isSiteOperational() : request.siteOperational();
        if (writesTheDaysAccount) {
            assertCauseGiven(operational, request.nonOperationalCause(),
                    request.nonOperationalNote());
            // The engineer can have put work on this report before sending it back, and the
            // supervisor's half does not touch work items — so without this the day could end up
            // saying nobody worked while carrying lines that claim otherwise. Refused rather
            // than resolved by deleting his lines: if a quantity was measured, the site worked,
            // and that is the answer to change.
            if (!operational && !workItems.findByDprIdOrderBySortOrder(id).isEmpty()) {
                throw new BusinessException("dpr.work-already-recorded",
                        "Report " + report.getDprNumber() + " already has work recorded against "
                                + "it, so it cannot be marked as a day the site did not work. "
                                + "A quantity measured here is claimed against the contract.");
            }
            report.recordOperationalStatus(operational, request.nonOperationalCause(),
                    request.nonOperationalNote());
            report.recordConditions(request.weather(), request.temperatureC(),
                    request.workingHoursLost());
            // Plant is the supervisor's, beside the weather rather than beside the claim. A
            // mixer that ran six hours and stood for two is something he watched happen; it
            // measures against no contract line and nothing is billed off it.
            replaceMachinery(id, request.machinery());
        } else {
            assertSupervisorHalfUntouched(report, request);
        }

        boolean writesEngineerHalf = carriesEngineerHalf(request.workItems(),
                request.workSummary(), request.delays(), request.safetyObservations(),
                request.qualityObservations(), request.instructionsReceived(),
                request.managementAttention(), request.nextDayPlan());
        assertMayWriteEngineerHalf(writesEngineerHalf);
        if (currentUser.hasPermission("dpr:verify")) {
            assertNoWorkClaimedOnALostDay(report.isSiteOperational(), request.workItems());
            report.recordNarrative(request.workSummary(), request.delays(),
                    request.safetyObservations(), request.qualityObservations(),
                    request.instructionsReceived(), request.managementAttention(),
                    request.nextDayPlan());
            replaceWorkItems(report, request.workItems());
        }

        /*
          A submitted report's figures are the document's own from the moment it was handed
          over — neither the engineer adding what was built nor the supervisor correcting the
          weather re-opens the day's arithmetic. A muster corrected afterwards shows up as a
          difference between the report and today's records, which is information; a report
          whose totals moved after it was sent is a document nobody can rely on.
        */
        if (beforeHandover) {
            refreshSnapshot(report);
        }

        audit.record(ENTITY_TYPE, id, "UPDATE", null,
                Map.of("dprNumber", report.getDprNumber(),
                        "half", writesTheDaysAccount ? "supervisor" : "engineer",
                        "workItems", request.workItems() == null ? 0 : request.workItems().size()),
                null);
        return responses.toResponse(report);
    }

    /** Links an uploaded site photograph to the day's report. */
    @PreAuthorize("hasAuthority('dpr:draft')")
    public DprResponse attachPhoto(UUID id, AttachPhotoRequest request) {
        DailyProgressReport report = require(id);
        siteAccessGuard.assertCanAccess(report.getSiteId());
        // Signed is signed, whether or not the office has countersigned it since: a
        // photograph added afterwards would not be part of what anybody put their name to.
        if (!report.getWorkflowStatus().acceptsTheDaysAccount()) {
            throw new BusinessException("dpr.not-attachable",
                    "Report " + report.getDprNumber() + " has been "
                            + report.getWorkflowStatus().name().toLowerCase()
                            + ". A photograph added now would not be part of what was signed.");
        }
        if (photos.existsByDprIdAndAttachmentId(id, request.attachmentId())) {
            return responses.toResponse(report);   // the retried upload
        }
        int nextOrder = photos.findByDprIdOrderBySortOrder(id).size();
        photos.save(new DprPhoto(id, request.attachmentId(), request.caption(), request.takenAt(),
                nextOrder));

        audit.record(ENTITY_TYPE, id, "ATTACH_PHOTO", null,
                Map.of("attachmentId", request.attachmentId().toString()), null);
        return responses.toResponse(report);
    }

    /**
     * Takes a report off the register, and gives its day back.
     *
     * <p>The wizard's "start fresh" empties a draft and writes over it, which is right when
     * the day happened and was written up badly. It is no answer to a report that should not
     * exist — the wrong site, a Sunday opened out of habit — because an emptied draft still
     * holds a DPR number, still sits in the register, and still occupies the day so nobody
     * can write it properly later.</p>
     *
     * <p>Only a draft or a report the engineer sent back. A submitted report is on somebody's
     * desk and the answer to it is to send it back first; a verified one has posted measured
     * quantities to the measurement book, and that is exactly the history that does not move.
     * The reason is required for the reason it is required to delete a project: six months on,
     * a document that vanished without an explanation is indistinguishable from data loss.</p>
     */
    @PreAuthorize("hasAuthority('dpr:delete')")
    public DprResponse delete(UUID id, String reason) {
        DailyProgressReport report = require(id);
        siteAccessGuard.assertCanAccess(report.getSiteId());
        if (!report.getWorkflowStatus().isEditable()) {
            throw new BusinessException("dpr.not-deletable",
                    "Report " + report.getDprNumber() + " has been "
                            + report.getWorkflowStatus().name().toLowerCase()
                            + " and cannot be deleted. "
                            + (report.getWorkflowStatus() == Workflow.SUBMITTED
                            ? "Ask the engineer to send it back first."
                            : "Its figures are what was signed, and its measured lines are "
                            + "in the measurement book."));
        }

        report.delete(Instant.now(), currentUser.currentUserIdOrNull(), reason);
        audit.record(ENTITY_TYPE, id, "DELETE",
                Map.of("dprNumber", report.getDprNumber(),
                        "workflowStatus", report.getWorkflowStatus().name()),
                Map.of("reportDate", report.getReportDate().toString(),
                        "siteId", report.getSiteId().toString()), reason);
        return responses.toResponse(report);
    }

    // ------------------------------------------------------------------ workflow

    /**
     * Hands the report to the engineer, and freezes the supervisor's half of it.
     *
     * <p>This is a handover rather than a finished document. The supervisor is saying that what
     * the day was is now settled — the muster, the material, the bills and, on a day the site
     * did not work, the cause — and from here the report is the engineer's to complete and
     * sign.</p>
     *
     * <p>The snapshot is recomputed once more immediately before it is frozen, so the document
     * says what the records said at the moment it was handed over rather than at the moment the
     * draft happened to be last touched. Nothing is asked about work done, because on this side
     * of the handover nobody has written any: the check that a report says <i>something</i> has
     * moved to {@link #decide}, where the man who would be signing it is standing.</p>
     *
     * <p><b>One photograph, and it is asked here.</b> Everything else on the supervisor's half
     * is a figure the office could in principle reconstruct from another register — the muster
     * has the men, the store has the lorry, the bill book has the cartage. The photograph is
     * the only thing on the whole document that is evidence rather than assertion, and it is
     * the only thing that cannot be produced from a desk: it says the man was standing on the
     * site. So it is asked at the handover, which is the moment he says the day's account is
     * settled, and it is asked on a day the site did not work as much as on a day it did — a
     * flooded site photographed on the ninth of July is what an extension of time is granted
     * on, and "no work, rain" with nothing behind it is a sentence the department can refuse.
     * Reports handed over before this rule existed are untouched by it; it bites at submission
     * and nowhere else.</p>
     */
    @PreAuthorize("hasAuthority('dpr:draft')")
    public DprResponse submit(UUID id) {
        DailyProgressReport report = require(id);
        siteAccessGuard.assertCanAccess(report.getSiteId());
        if (!report.getWorkflowStatus().isEditable()) {
            throw new BusinessException("dpr.not-submittable",
                    "Report " + report.getDprNumber() + " is already "
                            + report.getWorkflowStatus().name().toLowerCase() + ".");
        }
        if (photos.findByDprIdOrderBySortOrder(id).isEmpty()) {
            throw new BusinessException("dpr.photograph-required",
                    "Report " + report.getDprNumber() + " has no photograph on it. Add at "
                            + "least one picture of the site before handing the day over — it "
                            + "is the only part of the report that shows the day rather than "
                            + "describing it.");
        }

        refreshSnapshot(report);
        report.submit(Instant.now(), currentUser.currentUserIdOrNull());

        audit.record(ENTITY_TYPE, id, "SUBMIT", null,
                Map.of("dprNumber", report.getDprNumber(),
                        "labourCost", nullToZero(report.getLabourCost()),
                        "materialConsumedValue", nullToZero(report.getMaterialConsumedValue()),
                        "costIncurred", nullToZero(report.getExpenseAmount())), null);
        return responses.toResponse(report);
    }

    /**
     * The engineer's decision. Verifying is what posts the report's measured quantities to the
     * measurement book; rejecting sends it back editable, with the reason on the record.
     */
    @PreAuthorize("hasAuthority('dpr:verify')")
    public DprResponse decide(UUID id, VerifyDprRequest request) {
        DailyProgressReport report = require(id);
        siteAccessGuard.assertCanAccess(report.getSiteId());
        if (report.getWorkflowStatus() != Workflow.SUBMITTED) {
            throw new BusinessException("dpr.not-verifiable",
                    "Report " + report.getDprNumber() + " is "
                            + report.getWorkflowStatus().name().toLowerCase()
                            + ", so there is nothing waiting to be signed.");
        }

        // The check that used to sit on submission, moved to where it belongs. A working day
        // that claims nothing and describes nothing is not a report, and the engineer is the
        // one who would be signing it — he owns the half that is missing, so telling him is
        // telling the person who can fix it. A day the site did not work is exempt: its cause
        // is the whole of what it has to say, and demanding a work line as well would be
        // asking him to describe brickwork that nobody laid.
        if (report.isSiteOperational() && request.action() == VerifyDprRequest.Action.VERIFY
                && workItems.findByDprIdOrderBySortOrder(id).isEmpty()
                && isBlank(report.getWorkSummary())) {
            throw new BusinessException("dpr.nothing-reported",
                    "Report " + report.getDprNumber() + " has no work recorded on it, so there "
                            + "is nothing to sign for. Add what was built, or send it back if "
                            + "the site did not work.");
        }

        Instant now = Instant.now();
        UUID by = currentUser.currentUserIdOrNull();
        if (request.action() == VerifyDprRequest.Action.REJECT) {
            if (isBlank(request.remarks())) {
                throw new BusinessException("dpr.rejection-reason-required",
                        "Sending a report back needs a reason the preparer can act on.");
            }
            report.reject(now, by, request.remarks());
            audit.record(ENTITY_TYPE, id, "REJECT", null,
                    Map.of("dprNumber", report.getDprNumber()), request.remarks());
            return responses.toResponse(report);
        }

        report.verify(now, by);
        BoqProgress.PostResult posted = boqProgress.postFromDpr(id, report.getSiteId(),
                report.getReportDate(), claimsOf(id));

        audit.record(ENTITY_TYPE, id, "VERIFY", null,
                Map.of("dprNumber", report.getDprNumber(), "progressPosted", posted.posted(),
                        "progressSkipped", posted.skipped(),
                        "overClaimed", posted.overClaimed().stream().map(UUID::toString).toList()),
                request.remarks());
        return responses.toResponse(report);
    }

    /**
     * The office's final approval, on a report the engineer has already signed.
     *
     * <p><b>It does not claim anything.</b> The measured quantities reached the measurement book
     * at verification and are still there; this is the countersignature on a document whose
     * figures already count. Holding the claim back until the office acted would put the
     * contract's progress in the hands of somebody who was not on the site — the man who
     * measured the work is the man who can answer for it, and that is why he is the one who
     * claims it.</p>
     *
     * <p>What it is for is the gap above him. A signed report used to be the end of the line,
     * so the office first met a fortnight's figures in a monthly return and had no recorded
     * moment of having accepted them. Now it has one, with a name and a time on it.</p>
     *
     * <p>Its own permission. Verifying is the engineer saying what was built and approving is
     * the office accepting it; an organisation that got the second by granting the first would
     * have a two-signature document carrying one signature.</p>
     */
    @PreAuthorize("hasAuthority('dpr:approve')")
    public DprResponse approve(UUID id) {
        DailyProgressReport report = require(id);
        siteAccessGuard.assertCanAccess(report.getSiteId());
        if (report.getWorkflowStatus() != Workflow.VERIFIED) {
            throw new BusinessException("dpr.not-approvable",
                    "Report " + report.getDprNumber() + " is "
                            + report.getWorkflowStatus().name().toLowerCase()
                            + (report.getWorkflowStatus() == Workflow.APPROVED
                            ? ", so it has been approved already."
                            : ", so there is nothing signed here for the office to accept."));
        }

        report.approve(Instant.now(), currentUser.currentUserIdOrNull());
        audit.record(ENTITY_TYPE, id, "APPROVE", null,
                Map.of("dprNumber", report.getDprNumber()), null);
        return responses.toResponse(report);
    }

    /**
     * What the plant on the report is charged at, filled by whoever the report went to.
     *
     * <p><b>Not the supervisor's, and not a field on the update.</b> He records what stood on
     * the site and for how long, because he is the man who watched it; the hire agreement is
     * not his and a rate box on his screen is a number guessed at seven in the evening. This
     * is the same line V15 and V24 draw elsewhere — the field may name a thing and never
     * value it — and it needs a separate call to hold it, because the plant rows themselves
     * are the supervisor's half and travel in {@link #update}.</p>
     *
     * <p><b>No new permission.</b> {@code dpr:verify} and {@code dpr:approve} already name
     * the two people a handed-over report goes to, and pricing the plant on it is part of
     * reading the day and signing for it rather than a third act. Minting one would let an
     * organisation grant the pricing to somebody who cannot open the report it sits on.</p>
     *
     * <p><b>After the handover and before the document closes.</b> A draft has not been given
     * to anybody yet and its plant list is still changing under the man typing it; an
     * approved report is finished, and a figure that can move afterwards is not a signed
     * document. Between those two the rate may be put on, corrected, or taken off again —
     * including by the office on a report the engineer has already signed, because the
     * engineer signs what was built and the rate is the office's own fact.</p>
     */
    @PreAuthorize("hasAnyAuthority('dpr:verify', 'dpr:approve')")
    public DprResponse priceThePlant(UUID id, SetPlantRatesRequest request) {
        DailyProgressReport report = require(id);
        siteAccessGuard.assertCanAccess(report.getSiteId());
        Workflow status = report.getWorkflowStatus();
        if (status != Workflow.SUBMITTED && status != Workflow.VERIFIED) {
            throw new BusinessException("dpr.not-priceable",
                    "Report " + report.getDprNumber() + " is " + status.name().toLowerCase()
                            + (status == Workflow.APPROVED
                            ? ", so it is finished and its figures stand as they were accepted."
                            : ", so it has not been handed over yet. What the plant costs is "
                                    + "settled on a report somebody has sent in."));
        }

        Map<UUID, DprMachinery> rows = machinery.findByDprId(id).stream()
                .collect(Collectors.toMap(DprMachinery::getId, row -> row));
        Instant now = Instant.now();
        UUID by = currentUser.currentUserIdOrNull();
        for (PlantRateInput input : request.rates()) {
            DprMachinery row = rows.get(input.machineryId());
            if (row == null) {
                // Named rather than ignored: a rate quietly dropped is a machine somebody
                // believes is priced, and the id belongs to another report or to a row a
                // correction has already replaced.
                throw BusinessException.notFound("Plant row", input.machineryId());
            }
            if (input.rate() != null && input.basis() == null) {
                throw new BusinessException("dpr.rate-needs-a-basis",
                        "A rate for " + row.getMachineryName() + " with no unit is a figure "
                                + "nobody can multiply. Say whether it is by the hour or by "
                                + "the day.");
            }
            row.priceAt(input.rate(), input.basis(), now, by);
        }

        audit.record(ENTITY_TYPE, id, "PRICE_PLANT", null,
                Map.of("dprNumber", report.getDprNumber(), "rows", request.rates().size()), null);
        return responses.toResponse(report);
    }

    // ------------------------------------------------------------------ internals

    /** The measured rows only. A row that describes work without measuring it claims nothing. */
    private List<BoqProgress.Claim> claimsOf(UUID dprId) {
        return workItems.findByDprIdOrderBySortOrder(dprId).stream()
                .filter(DprWorkItem::isMeasured)
                .map(line -> new BoqProgress.Claim(line.getBoqItemId(), line.getQuantity(),
                        "DPR " + line.getActivity()))
                .toList();
    }

    /**
     * Re-reads the day from labour, inventory and expense and writes it onto the report.
     *
     * <p>Goes through {@link DprPrefillService#rollup} rather than computing its own totals,
     * which is the point: the figures the wizard showed and the figures the document freezes
     * come from one place, so they cannot disagree.</p>
     */
    private void refreshSnapshot(DailyProgressReport report) {
        Rollup rollup = prefill.rollup(report.getSiteId(), report.getReportDate());
        LabourLookup.LabourDay labour = rollup.labour();
        LabourLookup.OutsourcedDay outsourced = rollup.outsourced();

        report.applySnapshot(labour.presentCount(), outsourced.headCount(), outsourced.manHours(),
                labour.regularHours(), labour.overtimeHours(),
                labour.cost(), rollup.material().receivedValue(), rollup.material().consumedValue(),
                rollup.expense().costIncurred());

        // The labour table is rebuilt rather than merged: it is a snapshot of the muster, and
        // a trade that left the site has to leave the table with it.
        labourLines.deleteByDprId(report.getId());
        labourLines.flush();
        labour.groups().forEach(group -> labourLines.save(new DprLabour(report.getId(),
                group.skillCategoryId(), group.labourSupplierId(), group.headCount(),
                group.regularHours(), group.overtimeHours())));
        // The contractor's gang, on the same table and flagged apart. Their man-hours go in
        // the regular column when the site recorded them and zero when it did not — the flag
        // is what keeps the two kinds of row from being added together, not the hours, which
        // is why it has to travel with the row rather than being inferred from a zero. There
        // is still no overtime here: nobody clocked these men, so there is no shift to be
        // over.
        outsourced.groups().forEach(group -> labourLines.save(new DprLabour(report.getId(),
                group.skillCategoryId(), group.labourSupplierId(), group.headCount(),
                group.manHours() == null ? BigDecimal.ZERO : group.manHours(),
                BigDecimal.ZERO, true)));
    }

    private void replaceWorkItems(DailyProgressReport report, List<WorkItemInput> lines) {
        if (lines == null) {
            return;
        }
        workItems.deleteByDprId(report.getId());
        workItems.flush();
        int order = 0;
        List<DprWorkItem> saved = new ArrayList<>();
        for (WorkItemInput line : lines) {
            if (line.boqItemId() != null) {
                BoqLookup.BoqItemInfo item = boqItems.requireChargeable(line.boqItemId());
                if (!item.projectId().equals(report.getProjectId())) {
                    throw new BusinessException("dpr.boq-other-project",
                            "Item " + item.itemNumber() + " belongs to a different project.");
                }
            }
            saved.add(new DprWorkItem(report.getId(), line.boqItemId(), line.activity(),
                    line.workLocation(), line.quantity(), line.unitId(), line.remarks(), order++));
        }
        assertOneRowPerWorkItem(saved);
        workItems.saveAll(saved);
    }

    /**
     * One row per BOQ line on a report.
     *
     * <p>Not a cosmetic rule. Progress posting is idempotent on {@code (dpr_id, boq_item_id)},
     * so two rows claiming the same line would silently post the first and skip the second —
     * the report would say 30 cum and the contract would receive 18. Refusing it here is what
     * keeps the document and the measurement book saying the same thing.</p>
     */
    private static void assertOneRowPerWorkItem(List<DprWorkItem> lines) {
        List<UUID> seen = new ArrayList<>();
        for (DprWorkItem line : lines) {
            if (line.getBoqItemId() == null) {
                continue;   // free-text rows are not claims and may repeat
            }
            if (seen.contains(line.getBoqItemId())) {
                throw new BusinessException("dpr.duplicate-work-item",
                        "The same BOQ line appears twice on this report. Combine the quantities "
                                + "into one row, so the contract is claimed exactly what was built.");
            }
            seen.add(line.getBoqItemId());
        }
    }

    /**
     * The plant table, rebuilt from what the supervisor now says stood on the site.
     *
     * <p>Rebuilt rather than merged, like the labour table and for the same reason: a machine
     * that went away has to leave the report with it. But the rate on a row is not the
     * supervisor's and must not be deleted by his correction — the day's account stays open
     * to him until the signature, so the second mixer that arrived at four is entered on a
     * report the office may already have priced. So the rates are carried across, matched on
     * the machine's name, which is the only identity a rebuilt row has.</p>
     *
     * <p>A machine renamed loses its rate, and that is the right answer: what was priced was
     * "JCB 3DX", and carrying its figure onto whatever the name became would price a machine
     * nobody quoted for. Whoever holds the report sees an unpriced row and says what it
     * costs.</p>
     */
    private void replaceMachinery(UUID dprId, List<MachineryInput> plant) {
        if (plant == null) {
            return;
        }
        Map<String, DprMachinery> priced = machinery.findByDprId(dprId).stream()
                .filter(row -> row.getHireRate() != null)
                .collect(Collectors.toMap(row -> nameKey(row.getMachineryName()), row -> row,
                        // Two rows under one name is a report that already could not say
                        // which mixer is which; keep the first and price the rest afresh.
                        (first, second) -> first));
        machinery.deleteByDprId(dprId);
        machinery.flush();
        plant.forEach(entry -> {
            DprMachinery row = new DprMachinery(dprId, entry.machineryName(),
                    entry.count() <= 0 ? 1 : entry.count(), entry.hoursUsed(), entry.idleHours(),
                    entry.remarks());
            DprMachinery wasPriced = priced.get(nameKey(entry.machineryName()));
            if (wasPriced != null) {
                row.carryRateFrom(wasPriced);
            }
            machinery.save(row);
        });
    }

    /** "JCB 3dx " and "jcb 3DX" are the same machine to everybody except a string compare. */
    private static String nameKey(String name) {
        return name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Whether this report is still open to being written, and to whom.
     *
     * <p>A draft or a returned report is the supervisor's. A submitted one is the engineer's —
     * it is on his desk precisely so he can put the work done and the observations on it, and a
     * handover he could not write to would leave the report permanently half-finished. A
     * verified one belongs to nobody: it is the document that was signed.</p>
     */
    private void assertWritable(DailyProgressReport report) {
        Workflow status = report.getWorkflowStatus();
        if (status.acceptsTheDaysAccount()) {
            return;
        }
        throw new BusinessException("dpr.not-editable",
                "Report " + report.getDprNumber() + " has been " + status.name().toLowerCase()
                        + " and is not yours to edit. Its figures are what was signed.");
    }

    /**
     * A day the site did not work has to say why.
     *
     * <p>The database keeps the same promise through {@code ck_dpr_operational_cause}; this is
     * here so the answer is a sentence a supervisor can act on rather than a constraint
     * violation, and so "other" cannot stand in for an explanation on its own.</p>
     */
    private static void assertCauseGiven(boolean operational, NonOperationalCause cause,
                                         String note) {
        if (operational) {
            return;
        }
        if (cause == null) {
            throw new BusinessException("dpr.cause-required",
                    "A day the site did not work has to say why. A lost day with no cause on it "
                            + "cannot be counted towards a claim for time.");
        }
        if (cause.requiresNote() && isBlank(note)) {
            throw new BusinessException("dpr.cause-note-required",
                    "\"Other\" says nothing on its own. Write what stopped the work.");
        }
    }

    /**
     * Nothing is claimed against the contract on a day the site did not work.
     *
     * <p>Only the work items are refused, and not the machinery or the narrative. A work item is
     * the row that reaches the measurement book, so a report claiming brickwork on a day it also
     * says nobody worked is a contradiction that ends in a bill; a mixer standing idle in the
     * rain is just a fact about the day, and there is no reason to argue with it.</p>
     */
    private static void assertNoWorkClaimedOnALostDay(boolean operational,
                                                      List<WorkItemInput> lines) {
        if (operational || lines == null || lines.isEmpty()) {
            return;
        }
        throw new BusinessException("dpr.non-operational-work",
                "This report says the site did not work that day, so it cannot also record work "
                        + "done. Say the site worked, or take the work lines off.");
    }

    /**
     * Whether the request carries anything belonging to the engineer's half of the report.
     *
     * <p>An empty list and a blank box are not an attempt to write: a supervisor's screen has no
     * work step, so his save sends empty ones, and treating that as a write would either refuse
     * every save he makes or — worse — let it through and delete lines the engineer had already
     * put on a report that came back to him.</p>
     *
     * <p>Machinery is not asked about here. It moved to the supervisor's half with the rest of
     * what he watched happen, and it is applied in {@link #update}'s first branch.</p>
     */
    private static boolean carriesEngineerHalf(List<WorkItemInput> lines, String... narrative) {
        if (lines != null && !lines.isEmpty()) {
            return true;
        }
        for (String value : narrative) {
            if (!isBlank(value)) {
                return true;
            }
        }
        return false;
    }

    private void assertMayWriteEngineerHalf(boolean writes) {
        if (writes && !currentUser.hasPermission("dpr:verify")) {
            throw BusinessException.forbidden(
                    "Work done and the day's observations are the engineer's part of the report. "
                            + "Record what the day was and hand it over — a measured quantity "
                            + "here becomes a claim against the contract when it is signed.");
        }
    }

    /**
     * The supervisor's half stops moving when he hands the report over.
     *
     * <p>Refused rather than ignored. The conditions, the operational status and the plant on
     * site are his statement about a day he was standing on, frozen along with the figures at
     * the same moment and for the same reason — and an engineer who could quietly rewrite them
     * would be signing his own account of somebody else's day.</p>
     */
    private static void assertSupervisorHalfUntouched(DailyProgressReport report,
                                                      UpdateDprRequest request) {
        if (request.siteOperational() == null && request.nonOperationalCause() == null
                && isBlank(request.nonOperationalNote()) && request.weather() == null
                && request.temperatureC() == null && request.workingHoursLost() == null
                && (request.machinery() == null || request.machinery().isEmpty())) {
            return;
        }
        throw new BusinessException("dpr.supervisor-half-frozen",
                "What the day was is settled: report " + report.getDprNumber() + " was handed "
                        + "over and its conditions were frozen with its figures. Send it back to "
                        + "the supervisor if they are wrong.");
    }

    private DailyProgressReport require(UUID id) {
        DailyProgressReport report = reports.findByIdAndOrgIdAndDeletedAtIsNull(id, orgId())
                .orElseThrow(() -> BusinessException.notFound("DPR", id));
        if (!Objects.equals(report.getOrgId(), orgId())) {
            throw BusinessException.notFound("DPR", id);
        }
        return report;
    }

    private static java.math.BigDecimal nullToZero(java.math.BigDecimal value) {
        return value == null ? java.math.BigDecimal.ZERO : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }
}

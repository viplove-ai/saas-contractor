package in.nirman.modules.dpr.service;

import in.nirman.common.BusinessException;
import in.nirman.common.DocumentNumberService;
import in.nirman.common.PageResponse;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.dpr.api.dto.DprDtos.AttachPhotoRequest;
import in.nirman.modules.dpr.api.dto.DprDtos.CreateDprRequest;
import in.nirman.modules.dpr.api.dto.DprDtos.DprResponse;
import in.nirman.modules.dpr.api.dto.DprDtos.MachineryInput;
import in.nirman.modules.dpr.api.dto.DprDtos.UpdateDprRequest;
import in.nirman.modules.dpr.api.dto.DprDtos.VerifyDprRequest;
import in.nirman.modules.dpr.api.dto.DprDtos.WorkItemInput;
import in.nirman.modules.dpr.domain.DailyProgressReport;
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

/**
 * Daily progress reports: draft, submit, verify.
 *
 * <p>Four rules carry the weight.</p>
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

        SiteLookup.SiteInfo site = sites.require(request.siteId());
        String number = documentNumbers.next(orgId(), DocumentNumberService.DocType.DPR,
                request.reportDate());
        DailyProgressReport report = new DailyProgressReport(request.id(), orgId(),
                site.projectId(), site.id(), request.reportDate(), number,
                currentUser.currentUserIdOrNull());
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
                        "reportDate", request.reportDate().toString()), null);
        return responses.toResponse(report);
    }

    @PreAuthorize("hasAuthority('dpr:draft')")
    public DprResponse update(UUID id, UpdateDprRequest request) {
        DailyProgressReport report = require(id);
        siteAccessGuard.assertCanAccess(report.getSiteId());
        assertEditable(report);
        if (!report.getVersion().equals(request.version())) {
            throw new OptimisticLockingFailureException("DPR " + id + " was changed by someone else");
        }

        report.recordConditions(request.weather(), request.temperatureC(),
                request.workingHoursLost());
        report.recordNarrative(request.workSummary(), request.delays(),
                request.safetyObservations(), request.qualityObservations(),
                request.instructionsReceived(), request.managementAttention(),
                request.nextDayPlan());
        replaceWorkItems(report, request.workItems());
        replaceMachinery(id, request.machinery());
        refreshSnapshot(report);

        audit.record(ENTITY_TYPE, id, "UPDATE", null,
                Map.of("dprNumber", report.getDprNumber(),
                        "workItems", request.workItems() == null ? 0 : request.workItems().size()),
                null);
        return responses.toResponse(report);
    }

    /** Links an uploaded site photograph to the day's report. */
    @PreAuthorize("hasAuthority('dpr:draft')")
    public DprResponse attachPhoto(UUID id, AttachPhotoRequest request) {
        DailyProgressReport report = require(id);
        siteAccessGuard.assertCanAccess(report.getSiteId());
        if (report.getWorkflowStatus() == Workflow.VERIFIED) {
            throw new BusinessException("dpr.not-attachable",
                    "Report " + report.getDprNumber() + " has been verified. A photograph added "
                            + "now would not be part of what was signed.");
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
     * Sends the report for the engineer's signature, and freezes its figures.
     *
     * <p>The snapshot is recomputed once more here, immediately before it is frozen, so the
     * document says what the records said at the moment it was sent rather than at the moment
     * the draft happened to be last touched.</p>
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
        if (workItems.findByDprIdOrderBySortOrder(id).isEmpty() && isBlank(report.getWorkSummary())) {
            throw new BusinessException("dpr.nothing-reported",
                    "A daily report needs either a line of work done or a written summary of the "
                            + "day. A report that says nothing cannot be verified.");
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

        report.applySnapshot(labour.presentCount(), outsourced.headCount(), labour.regularHours(),
                labour.overtimeHours(),
                labour.cost(), rollup.material().receivedValue(), rollup.material().consumedValue(),
                rollup.expense().costIncurred());

        // The labour table is rebuilt rather than merged: it is a snapshot of the muster, and
        // a trade that left the site has to leave the table with it.
        labourLines.deleteByDprId(report.getId());
        labourLines.flush();
        labour.groups().forEach(group -> labourLines.save(new DprLabour(report.getId(),
                group.skillCategoryId(), group.labourContractorId(), group.headCount(),
                group.regularHours(), group.overtimeHours())));
        // The contractor's gang, on the same table and flagged apart. Hours are zero because
        // nobody clocked them, not because they stood idle — which is exactly why the flag
        // has to travel with the row rather than being inferred from the zero.
        outsourced.groups().forEach(group -> labourLines.save(new DprLabour(report.getId(),
                group.skillCategoryId(), group.labourContractorId(), group.headCount(),
                BigDecimal.ZERO, BigDecimal.ZERO, true)));
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

    private void replaceMachinery(UUID dprId, List<MachineryInput> plant) {
        if (plant == null) {
            return;
        }
        machinery.deleteByDprId(dprId);
        machinery.flush();
        plant.forEach(entry -> machinery.save(new DprMachinery(dprId, entry.machineryName(),
                entry.count() <= 0 ? 1 : entry.count(), entry.hoursUsed(), entry.idleHours(),
                entry.remarks())));
    }

    private static void assertEditable(DailyProgressReport report) {
        if (!report.getWorkflowStatus().isEditable()) {
            throw new BusinessException("dpr.not-editable",
                    "Report " + report.getDprNumber() + " has been "
                            + report.getWorkflowStatus().name().toLowerCase()
                            + " and can no longer be edited. Its figures are what was signed.");
        }
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

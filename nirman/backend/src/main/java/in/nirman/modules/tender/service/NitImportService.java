package in.nirman.modules.tender.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.attachment.api.dto.AttachmentDtos.AttachmentResponse;
import in.nirman.modules.attachment.domain.Attachment;
import in.nirman.modules.attachment.service.AttachmentService;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.project.service.ProjectProvisioning;
import in.nirman.modules.project.service.ProjectProvisioning.ImportedBoqLine;
import in.nirman.modules.tender.api.dto.NitDtos.ConfirmedBoqLine;
import in.nirman.modules.tender.api.dto.NitDtos.CreateFromNitRequest;
import in.nirman.modules.tender.api.dto.NitDtos.NitDocumentResponse;
import in.nirman.modules.tender.api.dto.NitDtos.NitFields;
import in.nirman.modules.tender.api.dto.NitDtos.NitImportResponse;
import in.nirman.modules.tender.api.dto.NitDtos.NitPreviewResponse;
import in.nirman.modules.tender.api.dto.NitDtos.InterimMinimumTerm;
import in.nirman.modules.tender.api.dto.NitDtos.MilestoneTerm;
import in.nirman.modules.tender.api.dto.NitDtos.PreviewBoqLine;
import in.nirman.modules.tender.api.dto.NitDtos.ScheduleTerms;
import in.nirman.modules.tender.domain.NitDocument;
import in.nirman.modules.tender.domain.NitInterimMinimum;
import in.nirman.modules.tender.domain.NitMilestone;
import in.nirman.modules.tender.parser.AllowedTime;
import in.nirman.modules.tender.parser.BoqClassifier;
import in.nirman.modules.tender.parser.BoqLine;
import in.nirman.modules.tender.parser.BoqReconciler;
import in.nirman.modules.tender.parser.NitExtraction;
import in.nirman.modules.tender.parser.NitPdfParser;
import in.nirman.modules.tender.parser.ScheduleFExtractor;
import in.nirman.modules.tender.repository.NitDocumentRepository;
import in.nirman.modules.tender.repository.NitInterimMinimumRepository;
import in.nirman.modules.tender.repository.NitMilestoneRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reading a Notice Inviting Tender, and turning a reviewed reading into a project.
 *
 * <p>Two steps on purpose. The reader is good but not certain — it works from a scanned
 * government PDF with no schema — so nothing about the project is written until a person has
 * seen what was extracted and corrected it. {@link #preview} persists only the uploaded file;
 * {@link #createFromNit} takes back what the user confirmed and writes the project, its
 * schedule and the tender record in one transaction.</p>
 */
@Service
@Transactional
public class NitImportService {

    /** {@code projects.code} accepts only these characters. */
    private static final Pattern NOT_CODE_SAFE = Pattern.compile("[^A-Za-z0-9._-]+");

    private static final int MAX_LINES = 2000;

    private final ProjectProvisioning projects;
    private final NitDocumentRepository nitDocuments;
    private final NitMilestoneRepository nitMilestones;
    private final NitInterimMinimumRepository interimMinimums;
    private final AttachmentService attachments;
    private final BoqUnitResolver unitResolver;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public NitImportService(ProjectProvisioning projects, NitDocumentRepository nitDocuments,
                            NitMilestoneRepository nitMilestones,
                            NitInterimMinimumRepository interimMinimums,
                            AttachmentService attachments, BoqUnitResolver unitResolver,
                            CurrentUserProvider currentUser, AuditService audit) {
        this.projects = projects;
        this.nitDocuments = nitDocuments;
        this.nitMilestones = nitMilestones;
        this.interimMinimums = interimMinimums;
        this.attachments = attachments;
        this.unitResolver = unitResolver;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    /**
     * Reads an uploaded notice and returns what it says, persisting nothing but the file.
     *
     * <p>The PDF is stored now rather than on confirm so a site connection uploads fifteen
     * megabytes once. Until the import is confirmed the attachment is unclaimed, which is
     * what lets the client discard it if the user changes their mind.</p>
     */
    @PreAuthorize("hasAuthority('project:write') and hasAuthority('boq:write')")
    public NitPreviewResponse preview(MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("nit.unreadable", "The upload could not be read.",
                    HttpStatus.BAD_REQUEST);
        }

        NitExtraction extraction = NitPdfParser.parse(content,
                file.getOriginalFilename() == null ? "tender.pdf" : file.getOriginalFilename());

        // Only store the file once it has parsed. A rejected upload should leave nothing
        // behind in object storage for somebody to clean up later.
        AttachmentResponse stored = attachments.upload(file, "NIT_DOCUMENT", null,
                Attachment.Kind.DOCUMENT);

        List<BoqLine> reconciled = BoqReconciler.reconcile(extraction.boqItems(),
                extraction.boqTotal(), componentTotals(extraction));
        List<PreviewBoqLine> lines = toPreviewLines(reconciled);

        BigDecimal derivedTotal = lines.stream()
                .map(PreviewBoqLine::derivedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<String> warnings = new ArrayList<>(extraction.warnings());
        addImportWarnings(warnings, lines, extraction);

        return new NitPreviewResponse(
                stored.id(), stored.fileName(), extraction.pageCount(),
                suggestedCode(extraction.nitNo()),
                truncate(extraction.workName(), 200),
                truncate(extraction.nitNo(), 80),
                truncate(extraction.nitNo(), 120),
                extraction.estimatedCost(),
                toFields(extraction),
                toScheduleTerms(extraction.scheduleF()),
                lines,
                extraction.boqTotal(),
                derivedTotal,
                warnings);
    }

    /**
     * Writes the reviewed import: the project, its schedule, and the tender it came from.
     *
     * <p>The values used are the ones sent back by the client, not a re-parse of the stored
     * PDF. By this point a person has read the preview and corrected it, and their reading
     * beats the parser's.</p>
     */
    @PreAuthorize("hasAuthority('project:write') and hasAuthority('boq:write')")
    public NitImportResponse createFromNit(CreateFromNitRequest request) {
        List<ConfirmedBoqLine> confirmed =
                request.boqLines() == null ? List.of() : request.boqLines();
        if (confirmed.size() > MAX_LINES) {
            throw new BusinessException("nit.too-many-lines",
                    "A schedule of more than " + MAX_LINES + " lines cannot be imported in one go.",
                    HttpStatus.BAD_REQUEST);
        }

        List<ImportedBoqLine> lines = new ArrayList<>(confirmed.size());
        for (int i = 0; i < confirmed.size(); i++) {
            ConfirmedBoqLine line = confirmed.get(i);
            lines.add(new ImportedBoqLine(line.itemNumber(), line.description(),
                    unitResolver.resolveCode(line.unitCode()), line.quantity(), line.rate(),
                    line.workPart(), line.category(), line.synthetic(), i));
        }

        ProjectProvisioning.ProvisionResult provisioned = projects.createWithBoq(
                new ProjectProvisioning.ProvisionRequest(request.project(), lines, "NIT_IMPORT",
                        request.billingOnly()));

        NitDocument document = saveNitDocument(request, provisioned);

        audit.record("NIT_DOCUMENT", document.getId(), "CREATE", null,
                Map.of("projectId", provisioned.project().id(),
                        "nitNo", String.valueOf(document.getNitNo()),
                        "boqLines", provisioned.lineCount()), null);

        return new NitImportResponse(provisioned.project(), document.getId(),
                provisioned.lineCount(), provisioned.boqValue());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('project:read')")
    public NitDocumentResponse forProject(UUID projectId) {
        NitDocument document = nitDocuments
                .findByProjectIdAndOrgIdAndDeletedAtIsNull(projectId, currentUser.currentOrgId())
                .orElseThrow(() -> BusinessException.notFound("NIT document", projectId));
        return new NitDocumentResponse(document.getId(), document.getProjectId(),
                document.getAttachmentId(), document.getFileName(), document.getPageCount(),
                document.getParserVersion(),
                new NitFields(document.getNitNo(), document.getWorkName(),
                        document.getEstimatedCost(), document.getCivilEstimatedCost(),
                        document.getElectricalEstimatedCost(), document.getEmdAmount(),
                        document.getCompletionPeriod(), document.getSubmissionClosingLocal(),
                        document.getBidOpeningLocal(), document.getDivision(),
                        document.getLocation(), document.getBidType(),
                        document.getContractorEligibility(), document.getSimilarWorkCriteria(),
                        document.getPerformanceGuaranteePercent(),
                        document.getSecurityDepositPercent(), document.getCivilDsrYear(),
                        document.getCivilCostIndexPercent(), document.getElectricalDsrYear(),
                        document.getElectricalCostIndexPercent()),
                storedTerms(document),
                document.getBoqTotal(), document.getExtractedItemCount(), document.getWarnings());
    }

    // ------------------------------------------------------------------ preview assembly

    private NitDocument saveNitDocument(CreateFromNitRequest request,
                                        ProjectProvisioning.ProvisionResult provisioned) {
        AttachmentResponse attachment = attachments.claim(request.attachmentId(),
                provisioned.project().id());

        NitDocument document = new NitDocument(currentUser.currentOrgId(),
                provisioned.project().id(), attachment.fileName(),
                Math.max(1, request.pageCount()));
        document.attachTo(attachment.id(), attachment.checksumSha256());

        NitFields fields = request.fields();
        if (fields != null) {
            document.applyFields(fields.nitNo(), fields.workName(), fields.estimatedCost(),
                    fields.civilEstimatedCost(), fields.electricalEstimatedCost(),
                    fields.emdAmount(), fields.completionPeriod(), fields.submissionClosing(),
                    fields.bidOpening(), fields.division(), fields.location(), fields.bidType(),
                    fields.contractorEligibility(), fields.similarWorkCriteria(),
                    fields.performanceGuaranteePercent(), fields.securityDepositPercent(),
                    fields.civilDsrYear(), fields.civilCostIndexPercent(),
                    fields.electricalDsrYear(), fields.electricalCostIndexPercent());
        }
        ScheduleTerms terms = request.scheduleTerms() == null
                ? ScheduleTerms.EMPTY : request.scheduleTerms();
        document.applyGuaranteeTerms(terms.apgThresholdPercent(), terms.apgMethod(),
                terms.apgPercent());
        document.applyScheduleTerms(
                terms.completionValue() == null || terms.completionUnit() == null ? null
                        : new AllowedTime(terms.completionValue(), terms.completionUnit()),
                terms.startReckoningDays(), terms.clause7aApplicable());

        document.recordSchedule(provisioned.boqValue(), provisioned.lineCount(),
                request.warnings());
        NitDocument saved = nitDocuments.save(document);
        saveScheduleTerms(saved.getId(), terms);
        return saved;
    }

    /**
     * The milestone table and the Clause 7 thresholds, written as the document's children.
     *
     * <p>Deduplicated on the way in by sequence and by work part. The unique indexes would
     * refuse a repeat anyway, but a client echoing a list back is the wrong place to discover
     * that through a constraint violation — and a repeated milestone is a reading error, not a
     * reason to lose the whole import.</p>
     */
    private void saveScheduleTerms(UUID documentId, ScheduleTerms terms) {
        Map<Integer, NitMilestone> milestones = new LinkedHashMap<>();
        for (MilestoneTerm milestone : terms.milestonesOrEmpty()) {
            AllowedTime time = milestone.timeAllowedValue() == null
                    || milestone.timeAllowedUnit() == null ? null
                    : new AllowedTime(milestone.timeAllowedValue(), milestone.timeAllowedUnit());
            milestones.putIfAbsent(milestone.sequence(), new NitMilestone(documentId,
                    milestone.sequence(), milestone.description(), time,
                    milestone.financialPercent(), milestone.withheldPercent(),
                    milestone.physical()));
        }
        if (!milestones.isEmpty()) {
            nitMilestones.saveAll(milestones.values());
        }

        Map<String, NitInterimMinimum> minimums = new LinkedHashMap<>();
        for (InterimMinimumTerm minimum : terms.interimMinimumsOrEmpty()) {
            String workPart = truncate(minimum.workPart(), 40);
            minimums.putIfAbsent(workPart == null ? "" : workPart,
                    new NitInterimMinimum(documentId, workPart, minimum.amount()));
        }
        if (!minimums.isEmpty()) {
            interimMinimums.saveAll(minimums.values());
        }
    }

    /** The stored contractual terms, reassembled from the document and its two child tables. */
    private ScheduleTerms storedTerms(NitDocument document) {
        List<MilestoneTerm> milestones =
                nitMilestones.findByNitDocumentIdOrderBySequenceNoAsc(document.getId()).stream()
                        .map(milestone -> new MilestoneTerm(milestone.getSequenceNo(),
                                milestone.getDescription(),
                                milestone.getTimeAllowed() == null
                                        ? null : milestone.getTimeAllowed().value(),
                                milestone.getTimeAllowed() == null
                                        ? null : milestone.getTimeAllowed().unit(),
                                milestone.getFinancialPercent(), milestone.getWithheldPercent(),
                                milestone.isPhysical()))
                        .toList();
        List<InterimMinimumTerm> minimums =
                interimMinimums.findByNitDocumentId(document.getId()).stream()
                        .map(minimum -> new InterimMinimumTerm(minimum.getWorkPart(),
                                minimum.getAmount()))
                        .toList();
        AllowedTime completion = document.getCompletionTime();
        return new ScheduleTerms(
                completion == null ? null : completion.value(),
                completion == null ? null : completion.unit(),
                document.getStartReckoningDays(), document.getClause7aApplicable(),
                document.getApgThresholdPercent(), document.getApgMethod(),
                document.getApgPercent(), milestones, minimums);
    }

    private static ScheduleTerms toScheduleTerms(ScheduleFExtractor.ScheduleF scheduleF) {
        if (scheduleF == null) {
            return ScheduleTerms.EMPTY;
        }
        List<MilestoneTerm> milestones = scheduleF.milestones().stream()
                .map(milestone -> new MilestoneTerm(milestone.sequence(), milestone.description(),
                        milestone.timeAllowed() == null ? null : milestone.timeAllowed().value(),
                        milestone.timeAllowed() == null ? null : milestone.timeAllowed().unit(),
                        milestone.financialPercent(), milestone.withheldPercent(),
                        milestone.physical()))
                .toList();
        List<InterimMinimumTerm> minimums = scheduleF.interimMinimums().stream()
                .map(minimum -> new InterimMinimumTerm(minimum.workPart(), minimum.amount()))
                .toList();
        var guarantee = scheduleF.additionalGuarantee();
        return new ScheduleTerms(
                scheduleF.completionTime() == null ? null : scheduleF.completionTime().value(),
                scheduleF.completionTime() == null ? null : scheduleF.completionTime().unit(),
                scheduleF.startReckoningDays(), scheduleF.clause7aApplicable(),
                guarantee == null ? null : guarantee.thresholdPercent(),
                guarantee == null ? null : guarantee.method(),
                guarantee == null ? null : guarantee.percent(),
                milestones, minimums);
    }

    /**
     * Numbers the schedule so no two lines collide.
     *
     * <p>A composite tender restarts numbering at 1.1.1 for each schedule, and
     * {@code uq_boq_project_number} does not care that the two came from different tables on
     * different pages. Prefixing by work part keeps them apart and keeps the original number
     * legible — {@code C/1.1.1} still reads as item 1.1.1 of the civil schedule.</p>
     */
    private List<PreviewBoqLine> toPreviewLines(List<BoqLine> items) {
        Set<String> used = new LinkedHashSet<>();
        List<PreviewBoqLine> lines = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            BoqLine item = items.get(i);
            String workPart = BoqClassifier.workPart(item);
            String original = item.itemNo() == null ? String.valueOf(i + 1) : item.itemNo();
            String numbered = uniqueItemNumber(original, workPart, item.synthetic(), used);
            BoqUnitResolver.ResolvedUnit unit = unitResolver.resolve(item.unit());

            lines.add(new PreviewBoqLine(i, numbered, item.description(), item.quantity(),
                    item.unit(), unit.code(), unit.recognised(), item.rate(), item.amount(),
                    item.derivedAmount(), workPart, BoqClassifier.classify(item),
                    item.synthetic(), !numbered.equals(original)));
        }
        return lines;
    }

    private static String uniqueItemNumber(String original, String workPart, boolean synthetic,
                                           Set<String> used) {
        String candidate = synthetic ? original : prefix(workPart) + original;
        candidate = truncate(candidate, 40);
        if (used.add(candidate)) {
            return candidate;
        }
        for (int suffix = 2; suffix < 1000; suffix++) {
            String tail = "-" + suffix;
            String retry = truncate(candidate, 40 - tail.length()) + tail;
            if (used.add(retry)) {
                return retry;
            }
        }
        throw new IllegalStateException("cannot make item number unique: " + original);
    }

    private static String prefix(String workPart) {
        return BoqLine.ELECTRICAL.equals(workPart) ? "E/" : "C/";
    }

    private static Map<String, BigDecimal> componentTotals(NitExtraction extraction) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        totals.put(BoqLine.CIVIL, extraction.civilEstimatedCost());
        totals.put(BoqLine.ELECTRICAL, extraction.electricalEstimatedCost());
        return totals;
    }

    private static void addImportWarnings(List<String> warnings, List<PreviewBoqLine> lines,
                                          NitExtraction extraction) {
        long renumbered = lines.stream().filter(PreviewBoqLine::renumbered).count();
        if (renumbered > 0) {
            warnings.add(("%d item numbers were prefixed by work part so each line is unique "
                    + "within the project.").formatted(renumbered));
        }
        long invented = lines.stream().filter(line -> !line.unitRecognised()).count();
        if (invented > 0) {
            warnings.add(("%d lines use a unit not in your master data; it will be added on "
                    + "import. Check these before saving.").formatted(invented));
        }
        long synthetic = lines.stream().filter(PreviewBoqLine::synthetic).count();
        if (synthetic > 0) {
            warnings.add(("The extracted rows fall short of the stated total, so %d "
                    + "reconciliation line(s) were added. Nothing can be charged against "
                    + "them.").formatted(synthetic));
        }
        long misprinted = lines.stream()
                .filter(line -> line.amount() != null && line.derivedAmount() != null)
                .filter(line -> line.amount().subtract(line.derivedAmount()).abs()
                        .compareTo(BigDecimal.ONE) > 0)
                .count();
        if (misprinted > 0) {
            warnings.add(("%d lines have a printed amount that differs from quantity × rate; "
                    + "the quantity and rate are what will be saved.").formatted(misprinted));
        }
        if (extraction.estimatedCost() == null) {
            warnings.add("No contract value was found, so it has been left blank.");
        }

        /*
          Schedule F is reported here rather than by the parser because the parser's own warning
          list is held field for field against the Python reference, which never read Schedule F.
          These are the three absences that stop a plan being built from the contract instead of
          from assumptions, so each says what is lost rather than merely what is missing.
        */
        ScheduleFExtractor.ScheduleF scheduleF = extraction.scheduleF();
        if (scheduleF.milestones().isEmpty()) {
            warnings.add("No table of milestones was found. Work can still be planned, but "
                    + "against a default phasing rather than the one this contract is judged on.");
        }
        if (scheduleF.completionTime() == null) {
            warnings.add("The time allowed for completion was not found in Schedule F. "
                    + "Check it before planning any dates.");
        }
        if (scheduleF.interimMinimums().isEmpty()) {
            warnings.add("The Clause 7 minimum value of work for an interim bill was not found, "
                    + "so the billing rhythm cannot be worked out. Check Schedule F.");
        }
    }

    private static NitFields toFields(NitExtraction e) {
        return new NitFields(truncate(e.nitNo(), 120), e.workName(), e.estimatedCost(),
                e.civilEstimatedCost(), e.electricalEstimatedCost(), e.emdAmount(),
                truncate(e.completionPeriod(), 80), e.submissionClosing(), e.bidOpening(),
                truncate(e.division(), 120), truncate(e.location(), 300),
                truncate(e.bidType(), 40), e.contractorEligibility(), e.similarWorkCriteria(),
                e.performanceGuaranteePercent(), e.securityDepositPercent(), e.civilDsrYear(),
                e.civilCostIndexPercent(), e.electricalDsrYear(), e.electricalCostIndexPercent());
    }

    /**
     * A first guess at a project code, from the NIT number. {@code 23/EE/ACD/CPWD/Almora/2026-27}
     * becomes {@code 23-EE-ACD-CPWD-Almora-2026-27} — recognisable, and legal for the column.
     * The user is expected to shorten it.
     */
    private static String suggestedCode(String nitNo) {
        if (nitNo == null || nitNo.isBlank()) {
            return null;
        }
        String code = NOT_CODE_SAFE.matcher(nitNo.strip().replace('/', '-')).replaceAll("-");
        code = code.replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        return code.isEmpty() ? null : truncate(code, 40).toUpperCase(Locale.ROOT);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max).strip();
    }
}

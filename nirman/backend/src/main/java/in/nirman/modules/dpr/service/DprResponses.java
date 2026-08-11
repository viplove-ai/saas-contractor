package in.nirman.modules.dpr.service;

import in.nirman.modules.attachment.repository.AttachmentRepository;
import in.nirman.modules.dpr.api.dto.DprDtos.DprResponse;
import in.nirman.modules.dpr.api.dto.DprDtos.LabourLine;
import in.nirman.modules.dpr.api.dto.DprDtos.MachineryResponse;
import in.nirman.modules.dpr.api.dto.DprDtos.PhotoResponse;
import in.nirman.modules.dpr.api.dto.DprDtos.WorkItemResponse;
import in.nirman.modules.dpr.domain.DailyProgressReport;
import in.nirman.modules.dpr.domain.DprLabour;
import in.nirman.modules.dpr.domain.DprMachinery;
import in.nirman.modules.dpr.domain.DprPhoto;
import in.nirman.modules.dpr.domain.DprWorkItem;
import in.nirman.modules.dpr.repository.DprLabourRepository;
import in.nirman.modules.dpr.repository.DprMachineryRepository;
import in.nirman.modules.dpr.repository.DprPhotoRepository;
import in.nirman.modules.dpr.repository.DprWorkItemRepository;
import in.nirman.modules.identity.repository.UserRepository;
import in.nirman.modules.masterdata.domain.LabourContractor;
import in.nirman.modules.masterdata.domain.SkillCategory;
import in.nirman.modules.masterdata.repository.LabourContractorRepository;
import in.nirman.modules.masterdata.repository.SkillCategoryRepository;
import in.nirman.modules.project.repository.BoqProgressEntryRepository;
import in.nirman.modules.project.service.BoqLookup;
import in.nirman.modules.project.service.SiteLookup;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Turns a report and its four child tables into the API shape, with names attached.
 *
 * <p>Held apart from the service for the same reason {@code ExpenseResponses} is: the write
 * paths, the list and the PDF renderer all need the assembled shape, and a service that also
 * assembled it would be the place where two of those three quietly diverged.</p>
 */
@Component
@Transactional(readOnly = true)
public class DprResponses {

    private final DprWorkItemRepository workItems;
    private final DprLabourRepository labourLines;
    private final DprMachineryRepository machinery;
    private final DprPhotoRepository photos;
    private final BoqProgressEntryRepository progressEntries;
    private final AttachmentRepository attachments;
    private final UserRepository users;
    private final SkillCategoryRepository skillCategories;
    private final LabourContractorRepository contractors;
    private final BoqLookup boqItems;
    private final SiteLookup sites;

    public DprResponses(DprWorkItemRepository workItems, DprLabourRepository labourLines,
                        DprMachineryRepository machinery, DprPhotoRepository photos,
                        BoqProgressEntryRepository progressEntries,
                        AttachmentRepository attachments, UserRepository users,
                        SkillCategoryRepository skillCategories,
                        LabourContractorRepository contractors, BoqLookup boqItems,
                        SiteLookup sites) {
        this.workItems = workItems;
        this.labourLines = labourLines;
        this.machinery = machinery;
        this.photos = photos;
        this.progressEntries = progressEntries;
        this.attachments = attachments;
        this.users = users;
        this.skillCategories = skillCategories;
        this.contractors = contractors;
        this.boqItems = boqItems;
        this.sites = sites;
    }

    public DprResponse toResponse(DailyProgressReport report) {
        List<DprWorkItem> lines = workItems.findByDprIdOrderBySortOrder(report.getId());
        Map<UUID, BoqLookup.BoqItemInfo> named = boqItems.byIds(lines.stream()
                .map(DprWorkItem::getBoqItemId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet()));

        boolean frozen = report.getWorkflowStatus() != DailyProgressReport.Workflow.DRAFT
                && report.getWorkflowStatus() != DailyProgressReport.Workflow.REJECTED;
        int posted = progressEntries.findByDprIdOrderByCreatedAt(report.getId()).size();

        return new DprResponse(report.getId(), report.getDprNumber(), report.getSiteId(),
                siteName(report.getSiteId()), report.getProjectId(), report.getReportDate(),
                report.getWeather(), report.getTemperatureC(), report.getWorkingHoursLost(),
                report.getLabourPresentCount(), report.getOutsourcedHeadCount(),
                report.getOutsourcedManHours(),
                report.getLabourRegularHours(),
                report.getLabourOvertimeHours(), report.getLabourCost(),
                report.getMaterialReceivedValue(), report.getMaterialConsumedValue(),
                report.getExpenseAmount(), dayCost(report), frozen,
                report.getWorkSummary(), report.getDelays(), report.getSafetyObservations(),
                report.getQualityObservations(), report.getInstructionsReceived(),
                report.getManagementAttention(), report.getNextDayPlan(),
                report.getWorkflowStatus(), report.getPreparedBy(),
                userName(report.getPreparedBy()), report.getSubmittedAt(), report.getVerifiedBy(),
                userName(report.getVerifiedBy()), report.getVerifiedAt(),
                report.getRejectionReason(),
                report.getWorkflowStatus() == DailyProgressReport.Workflow.VERIFIED ? posted : null,
                report.getVersion(),
                lines.stream().map(line -> toWorkItem(line, named)).toList(),
                labourFor(report.getId()),
                machinery.findByDprId(report.getId()).stream().map(DprResponses::toMachinery).toList(),
                photosFor(report.getId()));
    }

    /**
     * Labour cost, material consumed and cost incurred — and deliberately not material
     * received or total booked.
     *
     * <p>This is the one total on the report that adds up, and it adds up precisely because of
     * what it leaves out: material received is inventory rather than cost, and total booked
     * double-counts both material purchases and wage payments (docs/09).</p>
     */
    private static BigDecimal dayCost(DailyProgressReport report) {
        return nullToZero(report.getLabourCost())
                .add(nullToZero(report.getMaterialConsumedValue()))
                .add(nullToZero(report.getExpenseAmount()));
    }

    private static WorkItemResponse toWorkItem(DprWorkItem line,
                                               Map<UUID, BoqLookup.BoqItemInfo> named) {
        BoqLookup.BoqItemInfo item = line.getBoqItemId() == null
                ? null : named.get(line.getBoqItemId());
        return new WorkItemResponse(line.getId(), line.getBoqItemId(),
                item == null ? null : item.itemNumber(), line.getActivity(),
                line.getWorkLocation(), line.getQuantity(), line.getUnitId(), line.getRemarks(),
                line.getSortOrder(), line.isMeasured());
    }

    private static MachineryResponse toMachinery(DprMachinery plant) {
        return new MachineryResponse(plant.getId(), plant.getMachineryName(), plant.getCount(),
                plant.getHoursUsed(), plant.getIdleHours(), plant.getRemarks());
    }

    /** The frozen labour table, with the trade and contractor names put back on it. */
    public List<LabourLine> labourFor(UUID dprId) {
        List<DprLabour> lines = labourLines.findByDprId(dprId);
        if (lines.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> skillNames = skillNames(lines);
        Map<UUID, String> contractorNames = contractorNames(lines);
        return lines.stream()
                .map(line -> new LabourLine(line.getSkillCategoryId(),
                        line.getSkillCategoryId() == null
                                ? null : skillNames.get(line.getSkillCategoryId()),
                        line.getLabourContractorId(),
                        line.getLabourContractorId() == null
                                ? null : contractorNames.get(line.getLabourContractorId()),
                        line.getHeadCount(), line.getRegularHours(), line.getOvertimeHours(),
                        line.isOutsourced()))
                // Ours first, then the contractor's, each alphabetical by trade: the report
                // reads as two blocks because they are two different kinds of fact.
                .sorted(java.util.Comparator.comparing(LabourLine::outsourced)
                        .thenComparing(line ->
                                line.skillCategoryName() == null ? "￿" : line.skillCategoryName()))
                .toList();
    }

    private Map<UUID, String> skillNames(List<DprLabour> lines) {
        Set<UUID> ids = lines.stream().map(DprLabour::getSkillCategoryId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of()
                : skillCategories.findAllById(ids).stream()
                        .collect(Collectors.toMap(SkillCategory::getId, SkillCategory::getName));
    }

    private Map<UUID, String> contractorNames(List<DprLabour> lines) {
        Set<UUID> ids = lines.stream().map(DprLabour::getLabourContractorId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of()
                : contractors.findAllById(ids).stream()
                        .collect(Collectors.toMap(LabourContractor::getId,
                                LabourContractor::getName));
    }

    private List<PhotoResponse> photosFor(UUID dprId) {
        return photos.findByDprIdOrderBySortOrder(dprId).stream()
                .map(photo -> attachments.findById(photo.getAttachmentId())
                        .map(file -> new PhotoResponse(photo.getId(), photo.getAttachmentId(),
                                photo.getCaption(), photo.getTakenAt(), file.getFileName(),
                                file.getContentType(), file.getSizeBytes(), photo.getSortOrder()))
                        .orElseGet(() -> new PhotoResponse(photo.getId(), photo.getAttachmentId(),
                                photo.getCaption(), photo.getTakenAt(), null, null, 0,
                                photo.getSortOrder())))
                .toList();
    }

    private String siteName(UUID siteId) {
        return sites.require(siteId).name();
    }

    private String userName(UUID userId) {
        return userId == null ? null
                : users.findById(userId).map(user -> user.getFullName()).orElse(null);
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}

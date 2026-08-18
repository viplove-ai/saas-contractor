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
import in.nirman.modules.masterdata.domain.Vendor;
import in.nirman.modules.masterdata.domain.SkillCategory;
import in.nirman.modules.masterdata.repository.VendorRepository;
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
    private final VendorRepository suppliers;
    private final BoqLookup boqItems;
    private final SiteLookup sites;

    public DprResponses(DprWorkItemRepository workItems, DprLabourRepository labourLines,
                        DprMachineryRepository machinery, DprPhotoRepository photos,
                        BoqProgressEntryRepository progressEntries,
                        AttachmentRepository attachments, UserRepository users,
                        SkillCategoryRepository skillCategories,
                        VendorRepository suppliers, BoqLookup boqItems,
                        SiteLookup sites) {
        this.workItems = workItems;
        this.labourLines = labourLines;
        this.machinery = machinery;
        this.photos = photos;
        this.progressEntries = progressEntries;
        this.attachments = attachments;
        this.users = users;
        this.skillCategories = skillCategories;
        this.suppliers = suppliers;
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
                report.isSiteOperational(), report.getNonOperationalCause(),
                report.getNonOperationalNote(),
                report.getWeather(), report.getTemperatureC(), report.getWorkingHoursLost(),
                report.getLabourPresentCount(), report.getOutsourcedHeadCount(),
                report.getOutsourcedManHours(), menOnSite(report),
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
                report.getApprovedBy(), userName(report.getApprovedBy()), report.getApprovedAt(),
                report.getRejectionReason(),
                // The count of what this report put into the measurement book, which happened
                // at the signature. An approved report still carries it: the office accepting
                // the document did not claim anything, and hiding the figure once it does
                // would read as the claim having been withdrawn.
                report.getWorkflowStatus() == DailyProgressReport.Workflow.VERIFIED
                        || report.getWorkflowStatus() == DailyProgressReport.Workflow.APPROVED
                        ? posted : null,
                report.getVersion(),
                lines.stream().map(line -> toWorkItem(line, named)).toList(),
                labourFor(report.getId()),
                machinery.findByDprId(report.getId()).stream().map(DprResponses::toMachinery).toList(),
                photosFor(report.getId()));
    }

    /**
     * Every man on the site: the muster roll's and the suppliers' gangs added.
     *
     * <p>Computed here rather than stored, exactly as {@link #dayCost} is. The two columns it
     * adds stay frozen and separate on the row — that separation is what keeps a wage off a
     * supplier's man — and this is the one figure that answers the question the department
     * actually asks, which is how many men stood on the site.</p>
     *
     * <p>A null present count is a report with no muster behind it, not zero men of ours; it
     * contributes nothing here and the supplier's count is then the whole answer.</p>
     */
    private static int menOnSite(DailyProgressReport report) {
        Integer own = report.getLabourPresentCount();
        return (own == null ? 0 : own) + report.getOutsourcedHeadCount();
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
                plant.getHoursUsed(), plant.getIdleHours(), plant.getRemarks(),
                plant.getHireRate(), plant.getRateBasis(), plant.hireAmount(),
                plant.getRateSetAt());
    }

    /** The frozen labour table, with the trade and contractor names put back on it. */
    public List<LabourLine> labourFor(UUID dprId) {
        List<DprLabour> lines = labourLines.findByDprId(dprId);
        if (lines.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> skillNames = skillNames(lines);
        Map<UUID, String> supplierNames = supplierNames(lines);
        return lines.stream()
                .map(line -> new LabourLine(line.getSkillCategoryId(),
                        line.getSkillCategoryId() == null
                                ? null : skillNames.get(line.getSkillCategoryId()),
                        line.getLabourSupplierId(),
                        line.getLabourSupplierId() == null
                                ? null : supplierNames.get(line.getLabourSupplierId()),
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

    private Map<UUID, String> supplierNames(List<DprLabour> lines) {
        Set<UUID> ids = lines.stream().map(DprLabour::getLabourSupplierId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of()
                : suppliers.findAllById(ids).stream()
                        .collect(Collectors.toMap(Vendor::getId,
                                Vendor::getName));
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

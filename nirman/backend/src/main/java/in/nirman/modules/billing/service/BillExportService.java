package in.nirman.modules.billing.service;

import in.nirman.modules.billing.api.dto.BillingDtos.BillItemResponse;
import in.nirman.modules.billing.api.dto.BillingDtos.BillResponse;
import in.nirman.modules.billing.domain.Agreement;
import in.nirman.modules.billing.domain.MeasurementLine;
import in.nirman.modules.billing.domain.MeasurementSheet;
import in.nirman.modules.billing.repository.AgreementRepository;
import in.nirman.modules.billing.repository.MeasurementLineRepository;
import in.nirman.modules.billing.repository.MeasurementSheetRepository;
import in.nirman.modules.masterdata.service.UnitLookup;
import in.nirman.modules.project.service.BoqLookup;
import in.nirman.modules.project.service.ProjectLookup;
import in.nirman.modules.reporting.RaBillWorkbookWriter;
import in.nirman.modules.reporting.RaBillWorkbookWriter.Header;
import in.nirman.modules.reporting.RaBillWorkbookWriter.Item;
import in.nirman.modules.reporting.RaBillWorkbookWriter.Line;
import in.nirman.modules.reporting.RaBillWorkbookWriter.SheetBlock;
import in.nirman.security.CurrentUserProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The bill as the workbook that goes to the division.
 *
 * <p>Reads the bill exactly as the API returns it, so a passed bill exports its frozen snapshot
 * and an open one exports what it currently claims — neither computed twice by two different
 * pieces of code. The workbook itself is assembled in {@code reporting}, the only module that
 * knows Apache POI exists.</p>
 *
 * <p><b>An open bill is deliberately exportable.</b> The engineer takes the file to the
 * department to have his measurements checked before he submits, which is what a draft is for;
 * refusing until it was passed would send him back to a spreadsheet to do it. The file says
 * DRAFT in its own name, because a file outlives the screen it came from.</p>
 *
 * <p><b>The previous bill crosses as figures, not as a link.</b> Its quantity and amount are
 * read from that bill's frozen snapshot and written into this workbook as numbers. A workbook
 * that reached into last month's file is exactly the arrangement that left the source document
 * pointing at a rate table nobody had on the machine.</p>
 */
@Service
public class BillExportService {

    private final RaBillService bills;
    private final MeasurementSheetRepository sheets;
    private final MeasurementLineRepository lines;
    private final AgreementRepository agreements;
    private final BoqLookup boqItems;
    private final ProjectLookup projects;
    private final UnitLookup units;
    private final RaBillWorkbookWriter writer;
    private final CurrentUserProvider currentUser;

    public BillExportService(RaBillService bills, MeasurementSheetRepository sheets,
                             MeasurementLineRepository lines, AgreementRepository agreements,
                             BoqLookup boqItems, ProjectLookup projects, UnitLookup units,
                             RaBillWorkbookWriter writer, CurrentUserProvider currentUser) {
        this.bills = bills;
        this.sheets = sheets;
        this.lines = lines;
        this.agreements = agreements;
        this.boqItems = boqItems;
        this.projects = projects;
        this.units = units;
        this.writer = writer;
        this.currentUser = currentUser;
    }

    public record Exported(byte[] body, String fileName) {
    }

    @PreAuthorize("hasAuthority('billing:read')")
    @Transactional(readOnly = true)
    public Exported export(UUID billId) {
        BillResponse bill = bills.get(billId);
        Optional<Agreement> agreement =
                agreements.findByOrgIdAndProjectId(currentUser.currentOrgId(), bill.projectId());

        byte[] body = writer.write(headerFor(bill, agreement), itemsFor(bill));
        return new Exported(body, fileNameFor(bill));
    }

    private Header headerFor(BillResponse bill, Optional<Agreement> agreement) {
        return new Header(
                projects.contract(bill.projectId())
                        .map(ProjectLookup.ProjectContract::name).orElse(null),
                agreement.map(Agreement::getContractorName).orElse(null),
                agreement.map(Agreement::getAgreementNo).orElse(null),
                agreement.map(Agreement::getDivision).orElse(null),
                bill.title(),
                bill.cutoffDate(),
                designated(agreement.map(Agreement::getMeasuredByName).orElse(null),
                        agreement.map(Agreement::getMeasuredByDesignation).orElse(null)),
                designated(agreement.map(Agreement::getPreparedByName).orElse(null),
                        agreement.map(Agreement::getPreparedByDesignation).orElse(null)),
                designated(agreement.map(Agreement::getCheckedByName).orElse(null),
                        agreement.map(Agreement::getCheckedByDesignation).orElse(null)),
                agreement.map(Agreement::getExecutiveEngineer).orElse(null),
                agreement.map(Agreement::getCmbNo).orElse(null),
                agreement.map(Agreement::getDeviationLimitPct).orElse(null));
    }

    /**
     * Each item on the bill, with the measurement pages behind it.
     *
     * <p>Ordered as the bill orders it, so the abstract, the measurement book and the deviation
     * statement all walk the same list — which is what lets the second and third point at the
     * first by cell reference instead of carrying their own copies.</p>
     */
    private List<Item> itemsFor(BillResponse bill) {
        List<MeasurementSheet> claimed =
                sheets.findByRaBillIdOrderByBoqItemIdAscMeasuredOnAsc(bill.id());
        Map<UUID, List<MeasurementSheet>> sheetsByItem = claimed.stream()
                .collect(Collectors.groupingBy(MeasurementSheet::getBoqItemId));
        Map<UUID, List<MeasurementLine>> linesBySheet = claimed.isEmpty() ? Map.of()
                : lines.findBySheetIdInOrderBySheetIdAscLineNoAsc(
                                claimed.stream().map(MeasurementSheet::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(MeasurementLine::getSheetId));

        // One lookup for every unit on the bill rather than one per item: the abstract prints
        // the code beside each quantity and there is no reason to ask the master data list
        // times.
        Map<UUID, UUID> unitIdByItem = new java.util.LinkedHashMap<>();
        for (BillItemResponse row : bill.items()) {
            UUID unitId = boqItems.requireChargeable(row.boqItemId()).unitId();
            if (unitId != null) {
                unitIdByItem.put(row.boqItemId(), unitId);
            }
        }
        Map<UUID, String> unitCodes = units.codesByIds(unitIdByItem.values());

        List<Item> items = new ArrayList<>();
        for (BillItemResponse row : bill.items()) {
            List<SheetBlock> blocks = new ArrayList<>();
            for (MeasurementSheet sheet : sheetsByItem.getOrDefault(row.boqItemId(), List.of())) {
                List<Line> sheetLines = linesBySheet.getOrDefault(sheet.getId(), List.of()).stream()
                        .map(line -> new Line(line.getLocation(), line.getNos(), line.getMult(),
                                line.getLength(), line.getBreadth(), line.getHeight(),
                                line.isDeduction()))
                        .toList();
                blocks.add(new SheetBlock(sheet.getSheetSerial(), sheet.getMeasuredOn(),
                        sheet.getLocationNote(), sheet.getUnitWeight(), sheetLines));
            }
            UUID unitId = unitIdByItem.get(row.boqItemId());
            items.add(new Item(row.itemNumber(), row.description(),
                    unitId == null ? null : unitCodes.get(unitId),
                    row.contractQuantity(), row.rate(),
                    row.qtyToDate().subtract(row.qtySincePrevious()), row.amountPrevious(),
                    blocks));
        }
        return items;
    }

    private static String designated(String name, String designation) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return designation == null || designation.isBlank() ? name : name + ", " + designation;
    }

    /** A draft says so in its own file name, because a file outlives the screen it came from. */
    private static String fileNameFor(BillResponse bill) {
        String suffix = bill.frozen() ? "" : "-DRAFT";
        return "%s%s-%s.xlsx".formatted(bill.title(), suffix, bill.cutoffDate())
                .replaceAll("[^A-Za-z0-9._-]", "-");
    }
}

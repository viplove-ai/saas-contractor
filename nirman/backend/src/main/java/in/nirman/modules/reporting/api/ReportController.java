package in.nirman.modules.reporting.api;

import in.nirman.common.BusinessException;
import in.nirman.modules.labour.api.dto.LabourReportDtos.AttendanceRegisterReport;
import in.nirman.modules.labour.api.dto.LabourReportDtos.WageSummaryReport;
import in.nirman.modules.inventory.api.dto.InventoryReportDtos.ConsumptionReport;
import in.nirman.modules.inventory.api.dto.InventoryReportDtos.LowStockReport;
import in.nirman.modules.inventory.api.dto.InventoryReportDtos.StockPositionReport;
import in.nirman.modules.inventory.api.dto.InventoryReportDtos.TransferRegisterReport;
import in.nirman.modules.inventory.api.dto.InventoryReportDtos.WastageReport;
import in.nirman.modules.expense.api.dto.ExpenseReportDtos.AdvanceBalancesReport;
import in.nirman.modules.expense.api.dto.ExpenseReportDtos.ExpenseRegisterReport;
import in.nirman.modules.expense.api.dto.ExpenseReportDtos.PayableAgeingReport;
import in.nirman.modules.expense.service.ExpenseReportService;
import in.nirman.modules.inventory.service.InventoryReportService;
import in.nirman.modules.labour.service.LabourReportService;
import in.nirman.modules.reporting.ExcelWorkbookWriter;
import in.nirman.modules.reporting.ExcelWorkbookWriter.Caption;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /reports/{name}} with {@code ?format=json|xlsx}, as docs/05 specifies.
 *
 * <p>The reporting module owns no tables. It asks each business module's service for the
 * projection and only decides how to render it, which is why the permission checks that
 * matter live on {@link LabourReportService} rather than here — this class cannot be the
 * thing that decides who may see a payroll.</p>
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports", description = "Operational and financial reports, as JSON or xlsx")
public class ReportController {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final LabourReportService labourReports;
    private final InventoryReportService inventoryReports;
    private final ExpenseReportService expenseReports;
    private final ExcelWorkbookWriter excel;

    public ReportController(LabourReportService labourReports,
                            InventoryReportService inventoryReports,
                            ExpenseReportService expenseReports, ExcelWorkbookWriter excel) {
        this.labourReports = labourReports;
        this.inventoryReports = inventoryReports;
        this.expenseReports = expenseReports;
        this.excel = excel;
    }

    @GetMapping("/attendance-register")
    @Operation(summary = "Muster register: one row per worker, one column per day, no money")
    public ResponseEntity<?> attendanceRegister(
            @RequestParam UUID siteId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "json") String format) {
        requireSaneRange(from, to);
        AttendanceRegisterReport report = labourReports.attendanceRegister(siteId, from, to);
        if (!isSpreadsheet(format)) {
            return ResponseEntity.ok(report);
        }
        return spreadsheet(renderRegister(report),
                "attendance-register-%s-%s".formatted(report.siteName(), from));
    }

    @GetMapping("/wage-summary")
    @Operation(summary = "Earned, drawn and payable per worker for a period")
    public ResponseEntity<?> wageSummary(
            @RequestParam UUID siteId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "json") String format) {
        requireSaneRange(from, to);
        WageSummaryReport report = labourReports.wageSummary(siteId, from, to);
        if (!isSpreadsheet(format)) {
            return ResponseEntity.ok(report);
        }
        return spreadsheet(renderWageSummary(report),
                "wage-summary-%s-%s".formatted(report.siteName(), from));
    }

    // ------------------------------------------------------------------ inventory

    @GetMapping("/stock-position")
    @Operation(summary = "What each store holds, at what average, worth what")
    public ResponseEntity<?> stockPosition(
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(defaultValue = "json") String format) {
        StockPositionReport report = inventoryReports.stockPosition(siteId, storeId);
        if (!isSpreadsheet(format)) {
            return ResponseEntity.ok(report);
        }
        List<String> headers = List.of("Store", "Code", "Material", "Unit", "Quantity",
                "Avg rate", "Value", "In transit", "Reorder level", "Low");
        List<List<Object>> rows = new ArrayList<>(report.rows().stream()
                .map(row -> List.<Object>of(orDash(row.storeName()), orDash(row.materialCode()),
                        orDash(row.materialName()), orDash(row.baseUnitCode()), row.quantityBase(),
                        row.movingAvgRate(), row.stockValue(), row.inTransitQtyBase(),
                        row.minStockLevel(), row.low() ? "YES" : ""))
                .toList());
        rows.add(List.of("", "", "TOTAL", "", "", "", report.totalValue(), "", "", ""));
        return spreadsheet(excel.write("Stock position",
                        List.of(new Caption("Scope", report.scopeName()),
                                new Caption("As at", report.asOf().toString())),
                        headers, rows),
                "stock-position-%s-%s".formatted(report.scopeName(), report.asOf()));
    }

    @GetMapping("/material-consumption")
    @Operation(summary = "Issued and wasted per material over a period, kept as separate columns")
    public ResponseEntity<?> materialConsumption(
            @RequestParam UUID siteId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "json") String format) {
        requireSaneRange(from, to);
        ConsumptionReport report = inventoryReports.consumption(siteId, from, to);
        if (!isSpreadsheet(format)) {
            return ResponseEntity.ok(report);
        }
        List<String> headers = List.of("Code", "Material", "Unit", "Issued qty", "Issued value",
                "Wasted qty", "Wasted value", "Total qty", "Total value", "Closing stock");
        List<List<Object>> rows = new ArrayList<>(report.rows().stream()
                .map(row -> List.<Object>of(orDash(row.materialCode()), orDash(row.materialName()),
                        orDash(row.baseUnitCode()), row.issuedQty(), row.issuedValue(),
                        row.wastedQty(), row.wastedValue(), row.totalConsumedQty(),
                        row.totalConsumedValue(), row.closingQty()))
                .toList());
        rows.add(List.of("", "TOTAL", "", "", "", "", "", "", report.totalConsumedValue(), ""));
        return spreadsheet(excel.write("Material consumption",
                        captions(report.siteName(), from, to), headers, rows),
                "material-consumption-%s-%s".formatted(report.siteName(), from));
    }

    @GetMapping("/low-stock")
    @Operation(summary = "Materials below their own reorder level, worst shortfall first")
    public ResponseEntity<?> lowStock(
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(defaultValue = "json") String format) {
        LowStockReport report = inventoryReports.lowStock(siteId, storeId);
        if (!isSpreadsheet(format)) {
            return ResponseEntity.ok(report);
        }
        List<String> headers = List.of("Store", "Code", "Material", "Unit", "In stock",
                "Reorder level", "Short by", "On its way");
        List<List<Object>> rows = report.rows().stream()
                .map(row -> List.<Object>of(orDash(row.storeName()), orDash(row.materialCode()),
                        orDash(row.materialName()), orDash(row.baseUnitCode()), row.quantityBase(),
                        row.minStockLevel(), row.shortfall(), row.inTransitQty()))
                .toList();
        return spreadsheet(excel.write("Low stock",
                        List.of(new Caption("Scope", report.scopeName()),
                                new Caption("As at", report.asOf().toString())),
                        headers, rows),
                "low-stock-%s-%s".formatted(report.scopeName(), report.asOf()));
    }

    @GetMapping("/transfer-register")
    @Operation(summary = "Transfers over a period. Anything still in transit is counted at neither end.")
    public ResponseEntity<?> transferRegister(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "json") String format) {
        requireSaneRange(from, to);
        TransferRegisterReport report = inventoryReports.transferRegister(from, to);
        if (!isSpreadsheet(format)) {
            return ResponseEntity.ok(report);
        }
        List<String> headers = List.of("Number", "Date", "From", "To", "Status", "Lines",
                "Dispatched", "Received", "Shortage", "Value");
        List<List<Object>> rows = new ArrayList<>(report.rows().stream()
                .map(row -> List.<Object>of(row.transferNumber(), row.transferDate(),
                        orDash(row.fromStoreName()), orDash(row.toStoreName()),
                        row.status().name(), row.lineCount(), row.dispatchedQty(),
                        row.receivedQty(), row.shortageQty(), row.value()))
                .toList());
        rows.add(List.of("", "", "", "", "TOTAL", "", "", "", report.totalShortageQty(), ""));
        return spreadsheet(excel.write("Transfer register",
                        List.of(new Caption("Period", from + " to " + to)), headers, rows),
                "transfer-register-%s".formatted(from));
    }

    @GetMapping("/wastage")
    @Operation(summary = "Wastage and damage with the reason each one carried")
    public ResponseEntity<?> wastage(
            @RequestParam UUID siteId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "json") String format) {
        requireSaneRange(from, to);
        WastageReport report = inventoryReports.wastage(siteId, from, to);
        if (!isSpreadsheet(format)) {
            return ResponseEntity.ok(report);
        }
        List<String> headers = List.of("Date", "Store", "Code", "Material", "Unit", "Type",
                "Quantity", "Value", "Reason");
        List<List<Object>> rows = new ArrayList<>(report.rows().stream()
                .map(row -> List.<Object>of(row.txnDate(), orDash(row.storeName()),
                        orDash(row.materialCode()), orDash(row.materialName()),
                        orDash(row.baseUnitCode()), row.txnType().name(), row.quantityBase(),
                        row.value(), orDash(row.reason())))
                .toList());
        rows.add(List.of("", "", "", "TOTAL", "", "", "", report.totalValue(), ""));
        return spreadsheet(excel.write("Wastage",
                        captions(report.siteName(), from, to), headers, rows),
                "wastage-%s-%s".formatted(report.siteName(), from));
    }

    // ------------------------------------------------------------------ expenses and cash

    @GetMapping("/expense-register")
    @Operation(summary = "Everything booked at a site, split so material purchase and wage disbursement are not counted as cost twice")
    public ResponseEntity<?> expenseRegister(
            @RequestParam(required = false) UUID siteId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "json") String format) {
        requireSaneRange(from, to);
        ExpenseRegisterReport report = expenseReports.register(siteId, from, to);
        if (!isSpreadsheet(format)) {
            return ResponseEntity.ok(report);
        }
        List<String> headers = List.of("Number", "Date", "Category", "Vendor", "Description",
                "Bill", "Before tax", "GST", "Total", "Paid", "Payable", "Status", "Counts as");
        List<List<Object>> rows = new ArrayList<>(report.rows().stream()
                .map(row -> List.<Object>of(row.expenseNumber(), row.expenseDate(),
                        orDash(row.categoryName()), orDash(row.vendorName()), row.description(),
                        orDash(row.billNumber()), row.amountBeforeTax(), row.gstAmount(),
                        row.totalAmount(), row.paidAmount(), row.payableAmount(),
                        row.workflowStatus().name(), countsAs(row.materialPurchase(),
                                row.wageSettlement())))
                .toList());
        // The four figures, spelled out where nobody can total the wrong column by hand.
        rows.add(List.of("", "", "", "", "TOTAL BOOKED", "", "", "", report.totalBooked(),
                report.totalPaid(), report.totalPayable(), "", ""));
        rows.add(List.of("", "", "", "", "Cost incurred", "", "", "", report.costIncurred(),
                "", "", "", "adds to project cost"));
        rows.add(List.of("", "", "", "", "Material purchase", "", "", "",
                report.materialPurchases(), "", "", "", "becomes inventory"));
        rows.add(List.of("", "", "", "", "Labour disbursement", "", "", "",
                report.labourDisbursements(), "", "", "", "settles wages already costed"));
        return spreadsheet(excel.write("Expense register",
                        List.of(new Caption("Site", report.siteName()),
                                new Caption("Period", from + " to " + to),
                                new Caption("Note", report.caveat())),
                        headers, rows),
                "expense-register-%s-%s".formatted(report.siteName(), from));
    }

    @GetMapping("/payable-ageing")
    @Operation(summary = "What is owed and for how long, bucketed 0-30, 31-60, 61-90 and beyond")
    public ResponseEntity<?> payableAgeing(
            @RequestParam(required = false) UUID vendorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(defaultValue = "json") String format) {
        PayableAgeingReport report = expenseReports.payableAgeing(vendorId, asOf);
        if (!isSpreadsheet(format)) {
            return ResponseEntity.ok(report);
        }
        List<String> headers = List.of("Vendor", "Expense", "Date", "Days", "Bucket",
                "Total", "Paid", "Payable");
        List<List<Object>> rows = new ArrayList<>(report.rows().stream()
                .map(row -> List.<Object>of(orDash(row.vendorName()), row.expenseNumber(),
                        row.expenseDate(), row.daysOutstanding(), row.bucket(),
                        row.totalAmount(), row.paidAmount(), row.payableAmount()))
                .toList());
        rows.add(List.of("", "", "", "", "0-30", "", "", report.current()));
        rows.add(List.of("", "", "", "", "31-60", "", "", report.days31to60()));
        rows.add(List.of("", "", "", "", "61-90", "", "", report.days61to90()));
        rows.add(List.of("", "", "", "", "90+", "", "", report.over90()));
        rows.add(List.of("", "", "", "", "TOTAL", "", "", report.totalPayable()));
        return spreadsheet(excel.write("Payable ageing",
                        List.of(new Caption("As at", report.asOf().toString())), headers, rows),
                "payable-ageing-%s".formatted(report.asOf()));
    }

    @GetMapping("/advance-balances")
    @Operation(summary = "Site floats still in somebody's pocket, longest outstanding first")
    public ResponseEntity<?> advanceBalances(
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(defaultValue = "json") String format) {
        AdvanceBalancesReport report = expenseReports.advanceBalances(siteId, userId, asOf);
        if (!isSpreadsheet(format)) {
            return ResponseEntity.ok(report);
        }
        List<String> headers = List.of("Advance", "Site", "Held by", "Issued", "Days",
                "Amount", "Bills produced", "Cash returned", "Still held");
        List<List<Object>> rows = new ArrayList<>(report.rows().stream()
                .map(row -> List.<Object>of(row.advanceNumber(), orDash(row.siteName()),
                        orDash(row.heldByName()), row.advanceDate(), row.daysOutstanding(),
                        row.amount(), row.adjustedAmount(), row.returnedAmount(),
                        row.balanceAmount()))
                .toList());
        rows.add(List.of("", "", "TOTAL", "", "", "", "", "", report.totalOutstanding()));
        return spreadsheet(excel.write("Advance balances",
                        List.of(new Caption("As at", report.asOf().toString())), headers, rows),
                "advance-balances-%s".formatted(report.asOf()));
    }

    /**
     * The one-word answer to "does this row add to what the project cost".
     *
     * <p>A labour supplier's bill at a site with no muster reads "Cost", and correctly: the
     * row settles no wage the project has counted, because nothing there was counted.</p>
     */
    private static String countsAs(boolean materialPurchase, boolean wageSettlement) {
        if (materialPurchase) {
            return "Inventory";
        }
        return wageSettlement ? "Wage settlement" : "Cost";
    }

    // ------------------------------------------------------------------ rendering

    private byte[] renderRegister(AttendanceRegisterReport report) {
        List<String> headers = new ArrayList<>(List.of("Code", "Worker"));
        report.days().forEach(day -> headers.add(String.valueOf(day.getDayOfMonth())));
        headers.addAll(List.of("Present", "Half", "Absent", "Leave", "Regular hrs", "OT hrs"));

        List<List<Object>> rows = report.rows().stream()
                .map(row -> {
                    List<Object> values = new ArrayList<>();
                    values.add(row.workerCode());
                    values.add(row.workerName());
                    report.days().forEach(day -> values.add(row.marks().get(day)));
                    values.addAll(List.of(row.presentDays(), row.halfDays(), row.absentDays(),
                            row.leaveDays(), row.regularHours(), row.overtimeHours()));
                    return values;
                })
                .toList();

        return excel.write("Attendance register", captions(report.siteName(),
                report.from(), report.to()), headers, rows);
    }

    private byte[] renderWageSummary(WageSummaryReport report) {
        List<String> headers = List.of("Code", "Worker", "Present", "Half", "Regular hrs",
                "OT hrs", "Wage", "Overtime", "Total earned", "Advance drawn", "Net payable");

        List<List<Object>> rows = new ArrayList<>(report.rows().stream()
                .map(row -> List.<Object>of(
                        orDash(row.workerCode()), orDash(row.workerName()),
                        row.presentDays(), row.halfDays(), row.regularHours(), row.overtimeHours(),
                        row.wageAmount(), row.overtimeAmount(), row.totalEarned(),
                        row.advanceDrawn(), row.netPayable()))
                .toList());

        // The line the sheet is actually read for.
        rows.add(List.of("", "TOTAL", "", "",
                report.totals().regularHours(), report.totals().overtimeHours(),
                "", "", report.totals().totalEarned(),
                report.totals().advanceDrawn(), report.totals().netPayable()));

        return excel.write("Wage summary", captions(report.siteName(),
                report.from(), report.to()), headers, rows);
    }

    private static List<Caption> captions(String siteName, LocalDate from, LocalDate to) {
        return List.of(new Caption("Site", siteName),
                new Caption("Period", from + " to " + to));
    }

    private static ResponseEntity<byte[]> spreadsheet(byte[] body, String baseName) {
        String fileName = baseName.replaceAll("[^A-Za-z0-9._-]", "-") + ".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX_CONTENT_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(body);
    }

    private static boolean isSpreadsheet(String format) {
        return "xlsx".equalsIgnoreCase(format);
    }

    /**
     * A register is a grid with one column per day, so an unbounded range is a request that
     * either times out or returns something nobody can read.
     */
    private static void requireSaneRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new BusinessException("report.range", "The end date is before the start date.");
        }
        if (from.plusDays(366).isBefore(to)) {
            throw new BusinessException("report.range-too-wide",
                    "Reports cover at most one year at a time.");
        }
    }

    private static Object orDash(String value) {
        return value == null ? "—" : value;
    }
}

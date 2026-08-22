package in.nirman.modules.reporting;

import in.nirman.common.BusinessException;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The running account bill as the department expects to receive it: a workbook of linked
 * sheets, with live formulas and real cross-sheet references.
 *
 * <p><b>Why formulas and not values.</b> What rotted the spreadsheet this replaces was not that
 * it computed — it was that it was carried forward bill to bill by copy and paste, until two
 * hundred and eighty-two of its references pointed at nothing and its rate table pointed at a
 * workbook that was not on the machine. Generated fresh from the ledger every time, the
 * formulas cannot decay: there is no previous copy to inherit from. What they buy is a document
 * an Assistant Engineer can click into and see {@code =ROUND(C11*E11*F11*I11*K11,2)} — which is
 * how he checks it, and a page of frozen numbers gives him nothing to check.</p>
 *
 * <p><b>The structure mirrors the paper.</b> The measurement book carries a block per sheet with
 * its own {@code =SUM}; the abstract carries one B/F row per block, each referencing that
 * block's total cell, and totals them. That is the shape the department reads, and it is also
 * what makes every figure traceable in one click down to somebody's handwriting on a numbered
 * page.</p>
 *
 * <p>Lives in {@code reporting} because this is the only module that knows Apache POI exists —
 * the same boundary {@link ExcelWorkbookWriter} keeps.</p>
 */
@Component
public class RaBillWorkbookWriter {

    private static final String MB = "Measurement Book";
    private static final String ABSTRACT = "Abstract of Cost";

    /** What prints across the head of every sheet: the tender, not this bill. */
    public record Header(
            String workName,
            String contractorName,
            String agreementNo,
            String division,
            String billTitle,
            LocalDate cutoffDate,
            String measuredBy,
            String preparedBy,
            String checkedBy,
            String executiveEngineer,
            String cmbNo,
            BigDecimal deviationLimitPct) {
    }

    /** One ruled row of one measurement sheet. Null dimensions stay out of the product. */
    public record Line(
            String location,
            BigDecimal nos,
            BigDecimal mult,
            BigDecimal length,
            BigDecimal breadth,
            BigDecimal height,
            boolean deduction) {
    }

    /** One measurement sheet — a page of the book, with its own hand-worked total. */
    public record SheetBlock(
            String sheetSerial,
            LocalDate measuredOn,
            String locationNote,
            /** Sections only: the tested kg/m the summed length is taken against. */
            BigDecimal unitWeight,
            List<Line> lines) {
    }

    /**
     * One contract item and everything measured against it on this bill.
     *
     * @param previousQuantity what earlier passed bills already paid for. Carried as a figure
     *                         rather than a reference because the bill it came from is a
     *                         different document, and a workbook that reached into last month's
     *                         file is exactly the arrangement that broke.
     */
    public record Item(
            String itemNumber,
            String description,
            String unit,
            BigDecimal agreementQuantity,
            BigDecimal rate,
            BigDecimal previousQuantity,
            BigDecimal previousAmount,
            List<SheetBlock> sheets) {
    }

    public byte[] write(Header header, List<Item> items) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Styles styles = new Styles(workbook);

            writeFrontPage(workbook, styles, header);
            List<List<String>> blockTotals = writeMeasurementBook(workbook, styles, header, items);
            AbstractRefs refs = writeAbstract(workbook, styles, header, items, blockTotals);
            writeBillForm(workbook, styles, header, refs);
            writeRecovery(workbook, styles, header, refs);
            writeDeviation(workbook, styles, header, items, refs);

            workbook.setActiveSheet(0);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException("billing.export-failed",
                    "The bill could not be written as a spreadsheet.");
        }
    }

    // ------------------------------------------------------------------ front page

    private static void writeFrontPage(Workbook workbook, Styles styles, Header header) {
        Sheet sheet = workbook.createSheet("Front Page");
        int r = 1;
        r = caption(sheet, styles.title, r, "COMPUTERISED MEASUREMENT BOOK", 6) + 1;
        r = caption(sheet, styles.subtitle, r,
                "MEASUREMENT & ABSTRACT OF COST — " + orDash(header.billTitle()), 6) + 1;
        r = caption(sheet, styles.caption, r,
                "Name of work: " + orDash(header.workName()), 6);
        r = caption(sheet, styles.caption, r,
                "Name of contractor: " + orDash(header.contractorName()), 6);
        r = caption(sheet, styles.caption, r,
                "Agreement No: " + orDash(header.agreementNo()), 6);
        r = caption(sheet, styles.caption, r, "Division: " + orDash(header.division()), 6);
        r = caption(sheet, styles.caption, r, "C.M.B. No: " + orDash(header.cmbNo()), 6) + 1;
        r = caption(sheet, styles.subtitle, r, "CERTIFICATE", 6);
        r = caption(sheet, styles.caption, r,
                "Certified that this computerised measurement book carries pages 1 to "
                        + "____ in all, and that every page is in serial order.", 6) + 2;

        Row signatures = sheet.createRow(r);
        text(signatures, 1, "Measurements by: " + orDash(header.measuredBy()), styles.caption);
        text(signatures, 3, "Prepared by: " + orDash(header.preparedBy()), styles.caption);
        text(signatures, 5, "Checked by: " + orDash(header.checkedBy()), styles.caption);

        sheet.setColumnWidth(0, 4 * 256);
        for (int c = 1; c <= 6; c++) {
            sheet.setColumnWidth(c, 22 * 256);
        }
    }

    // ------------------------------------------------------------------ measurement book

    /**
     * Every measured page, laid out as the book lays them out, and the address of each block's
     * total so the abstract can point at it.
     *
     * @return per item, the cell address of each of its blocks' totals — {@code 'Measurement
     *         Book'!J14} and the like
     */
    private static List<List<String>> writeMeasurementBook(Workbook workbook, Styles styles,
                                                           Header header, List<Item> items) {
        Sheet sheet = workbook.createSheet(MB);
        int r = writeHeaderBlock(sheet, styles, header, "MEASUREMENT SHEETS", 9);
        List<List<String>> perItem = new ArrayList<>();

        for (Item item : items) {
            List<String> blockRefs = new ArrayList<>();
            for (SheetBlock block : item.sheets()) {
                r = caption(sheet, styles.subtitle, r,
                        "Item No. " + orDash(item.itemNumber()) + " — " + orDash(item.description()),
                        9);
                r = caption(sheet, styles.caption, r,
                        "Sheet No. " + orDash(block.sheetSerial())
                                + "        Date of measurement: "
                                + (block.measuredOn() == null ? "—" : block.measuredOn())
                                + (isBlank(block.locationNote()) ? ""
                                        : "        " + block.locationNote()), 9);

                Row head = sheet.createRow(r++);
                String[] columns = {"Sl", "Particulars", "Nos", "×", "L", "B", "H", "Contents",
                        "Unit"};
                for (int c = 0; c < columns.length; c++) {
                    Cell cell = head.createCell(c);
                    cell.setCellValue(columns[c]);
                    cell.setCellStyle(styles.tableHead);
                }

                int firstLineRow = r;
                int sl = 1;
                for (Line line : block.lines()) {
                    Row row = sheet.createRow(r++);
                    number(row, 0, BigDecimal.valueOf(sl++), styles.serial);
                    text(row, 1, line.deduction()
                            ? "(-) " + orBlank(line.location()) : orBlank(line.location()),
                            styles.cell);
                    number(row, 2, line.nos(), styles.dimension);
                    number(row, 3, line.mult(), styles.dimension);
                    number(row, 4, line.length(), styles.dimension);
                    number(row, 5, line.breadth(), styles.dimension);
                    number(row, 6, line.height(), styles.dimension);
                    // The row's own arithmetic, live — this is the cell the AE clicks into.
                    formula(row, 7, contentsFormula(row.getRowNum() + 1, line), styles.quantity);
                    text(row, 8, item.unit(), styles.cell);
                }
                int lastLineRow = r - 1;

                Row total = sheet.createRow(r++);
                text(total, 6, "Total", styles.totalLabel);
                String totalCell;
                if (block.lines().isEmpty()) {
                    number(total, 7, BigDecimal.ZERO, styles.totalQuantity);
                    totalCell = "H" + (total.getRowNum() + 1);
                } else {
                    formula(total, 7, "SUM(H%d:H%d)".formatted(firstLineRow + 1, lastLineRow + 1),
                            styles.totalQuantity);
                    totalCell = "H" + (total.getRowNum() + 1);
                }
                text(total, 8, item.unit(), styles.totalLabel);

                // A section sheet measures a length and is paid by weight: the rows stay a
                // length and the claim is that length times the tested kg/m, on its own row so
                // the two can never be confused for one another.
                if (block.unitWeight() != null) {
                    Row weight = sheet.createRow(r++);
                    text(weight, 6, "Unit weight (kg/m)", styles.cell);
                    number(weight, 7, block.unitWeight(), styles.dimension);
                    String weightCell = "H" + (weight.getRowNum() + 1);

                    Row weighted = sheet.createRow(r++);
                    text(weighted, 6, "Total weight", styles.totalLabel);
                    formula(weighted, 7, "ROUND(%s*%s,2)".formatted(totalCell, weightCell),
                            styles.totalQuantity);
                    text(weighted, 8, "kg", styles.totalLabel);
                    totalCell = "H" + (weighted.getRowNum() + 1);
                }

                blockRefs.add("'" + MB + "'!" + totalCell);
                r++;
            }
            perItem.add(blockRefs);
        }

        sheet.setColumnWidth(0, 5 * 256);
        sheet.setColumnWidth(1, 42 * 256);
        for (int c = 2; c <= 7; c++) {
            sheet.setColumnWidth(c, 12 * 256);
        }
        sheet.setColumnWidth(8, 9 * 256);
        return perItem;
    }

    /**
     * {@code =ROUND(C11*E11*F11*I11*K11,2)}, with only the dimensions that were given.
     *
     * <p>A blank dimension is left out of the product rather than multiplied in as a zero. A
     * linear item has no breadth, and a formula that multiplied by an empty cell would make the
     * whole row nothing — which is the difference between "not applicable" and "measured none".</p>
     */
    private static String contentsFormula(int excelRow, Line line) {
        StringBuilder product = new StringBuilder("C").append(excelRow).append("*D").append(excelRow);
        if (line.length() != null) {
            product.append("*E").append(excelRow);
        }
        if (line.breadth() != null) {
            product.append("*F").append(excelRow);
        }
        if (line.height() != null) {
            product.append("*G").append(excelRow);
        }
        String rounded = "ROUND(" + product + ",2)";
        return line.deduction() ? "-" + rounded : rounded;
    }

    // ------------------------------------------------------------------ abstract of cost

    /**
     * Where the abstract put the figures the rest of the workbook reads.
     *
     * @param itemTotalRows the 1-based row of each item's Total line, in item order, so the
     *                      deviation statement can point at the executed quantity rather than
     *                      carry a second copy of it. Two sheets holding the same number
     *                      independently is how a workbook starts disagreeing with itself.
     */
    private record AbstractRefs(String grossToDate, String previousTotal, String sinceTotal,
                                List<Integer> itemTotalRows) {
    }

    private static AbstractRefs writeAbstract(Workbook workbook, Styles styles, Header header,
                                              List<Item> items, List<List<String>> blockTotals) {
        Sheet sheet = workbook.createSheet(ABSTRACT);
        int r = writeHeaderBlock(sheet, styles, header, "ABSTRACT OF COST", 8);

        String[] columns = {"Item No.", "Description", "Agt. Qty", "Executed Qty", "Unit", "Rate",
                "Amount up to date", "Amount of previous bill", "Since previous"};
        Row head = sheet.createRow(r++);
        head.setHeightInPoints(30);
        for (int c = 0; c < columns.length; c++) {
            Cell cell = head.createCell(c);
            cell.setCellValue(columns[c]);
            cell.setCellStyle(styles.tableHead);
        }

        List<Integer> totalRows = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            List<String> refs = i < blockTotals.size() ? blockTotals.get(i) : List.of();

            Row title = sheet.createRow(r++);
            text(title, 0, item.itemNumber(), styles.bold);
            text(title, 1, item.description(), styles.wrapped);

            int firstQtyRow = r;
            // What earlier passed bills already paid for, as a figure. The bill it came from is
            // a different document, and a workbook reaching into last month's file is precisely
            // the arrangement that produced 282 broken references.
            Row carried = sheet.createRow(r++);
            text(carried, 1, "B/F previous bills", styles.cell);
            number(carried, 3, orZero(item.previousQuantity()), styles.quantity);

            for (String ref : refs) {
                Row line = sheet.createRow(r++);
                text(line, 1, "B/F P-      /CMB", styles.cell);
                formula(line, 3, ref, styles.quantity);
            }
            int lastQtyRow = r - 1;

            Row total = sheet.createRow(r++);
            text(total, 2, "Total", styles.totalLabel);
            formula(total, 3, "SUM(D%d:D%d)".formatted(firstQtyRow + 1, lastQtyRow + 1),
                    styles.totalQuantity);
            number(total, 2, item.agreementQuantity(), styles.quantity);
            text(total, 4, item.unit(), styles.cell);
            number(total, 5, item.rate(), styles.money);
            int excelRow = total.getRowNum() + 1;
            formula(total, 6, "ROUND(F%d*D%d,2)".formatted(excelRow, excelRow), styles.totalMoney);
            number(total, 7, orZero(item.previousAmount()), styles.money);
            formula(total, 8, "G%d-H%d".formatted(excelRow, excelRow), styles.totalMoney);
            totalRows.add(excelRow);
            r++;
        }

        Row grand = sheet.createRow(r++);
        text(grand, 5, "TOTAL", styles.totalLabel);
        String sumOf = sumOfRows(totalRows, "G");
        formula(grand, 6, sumOf, styles.totalMoney);
        formula(grand, 7, sumOfRows(totalRows, "H"), styles.totalMoney);
        formula(grand, 8, sumOfRows(totalRows, "I"), styles.totalMoney);
        int grandRow = grand.getRowNum() + 1;

        r++;
        Row signatures = sheet.createRow(r);
        text(signatures, 1, "Measurements by: " + orDash(header.measuredBy()), styles.caption);
        text(signatures, 4, "Prepared by: " + orDash(header.preparedBy()), styles.caption);
        text(signatures, 6, "Checked by: " + orDash(header.checkedBy()), styles.caption);

        sheet.setColumnWidth(0, 12 * 256);
        sheet.setColumnWidth(1, 52 * 256);
        for (int c = 2; c <= 8; c++) {
            sheet.setColumnWidth(c, 16 * 256);
        }
        sheet.createFreezePane(0, head.getRowNum() + 1);

        return new AbstractRefs("'" + ABSTRACT + "'!G" + grandRow,
                "'" + ABSTRACT + "'!H" + grandRow, "'" + ABSTRACT + "'!I" + grandRow, totalRows);
    }

    /** {@code G12+G20+G31} — an explicit list, because the rows are not contiguous. */
    private static String sumOfRows(List<Integer> rows, String column) {
        if (rows.isEmpty()) {
            return "0";
        }
        StringBuilder sum = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sum.append('+');
            }
            sum.append(column).append(rows.get(i));
        }
        return sum.toString();
    }

    // ------------------------------------------------------------------ CPWA-26

    private static void writeBillForm(Workbook workbook, Styles styles, Header header,
                                      AbstractRefs refs) {
        Sheet sheet = workbook.createSheet("Bill Form");
        int r = 0;
        r = caption(sheet, styles.caption, r, "C.P.W.A.-26 (Revised)", 5);
        r = caption(sheet, styles.title, r, "RUNNING ACCOUNT BILL", 5) + 1;
        r = caption(sheet, styles.caption, r, "Division: " + orDash(header.division()), 5);
        r = caption(sheet, styles.caption, r,
                "Name of work: " + orDash(header.workName()), 5);
        r = caption(sheet, styles.caption, r,
                "Name of contractor: " + orDash(header.contractorName()), 5);
        r = caption(sheet, styles.caption, r,
                "Reference to agreement: " + orDash(header.agreementNo()), 5);
        r = caption(sheet, styles.caption, r,
                "Serial No. of this bill: " + orDash(header.billTitle()), 5) + 1;
        r = caption(sheet, styles.subtitle, r, "ACCOUNT OF WORK EXECUTED", 5);
        r = caption(sheet, styles.caption, r, "As per abstract of cost enclosed.", 5) + 1;

        r = moneyLine(sheet, styles, r, "Total value of work done to date (A)", "=" + refs.grossToDate());
        r = moneyLine(sheet, styles, r, "Deduct value of work shown on previous bill",
                "=" + refs.previousTotal());
        int aRow = r - 1;
        r = moneyLine(sheet, styles, r, "Net value of work since previous bill (F)",
                "=E%d-E%d".formatted(aRow, aRow + 1));
        r += 2;

        r = caption(sheet, styles.subtitle, r, "CERTIFICATE & SIGNATURE", 5);
        r = caption(sheet, styles.caption, r,
                "1. The measurements on which the entries above are based were made by "
                        + orDash(header.measuredBy()) + ".", 5);
        r = caption(sheet, styles.caption, r,
                "2. Certified that the work has been carried out as per the terms and conditions "
                        + "of the agreement.", 5) + 2;

        Row signatures = sheet.createRow(r);
        text(signatures, 1, "Dated signature of contractor", styles.caption);
        text(signatures, 3, "Prepared by: " + orDash(header.preparedBy()), styles.caption);
        text(signatures, 4, "Executive Engineer: " + orDash(header.executiveEngineer()),
                styles.caption);

        sheet.setColumnWidth(0, 4 * 256);
        sheet.setColumnWidth(1, 46 * 256);
        for (int c = 2; c <= 5; c++) {
            sheet.setColumnWidth(c, 20 * 256);
        }
    }

    private static int moneyLine(Sheet sheet, Styles styles, int r, String label, String formula) {
        Row row = sheet.createRow(r);
        text(row, 1, label, styles.cell);
        Cell cell = row.createCell(4);
        cell.setCellFormula(formula.startsWith("=") ? formula.substring(1) : formula);
        cell.setCellStyle(styles.totalMoney);
        return r + 1;
    }

    // ------------------------------------------------------------------ recovery statement

    /**
     * The statutory deductions, each as the formula that produced it.
     *
     * <p>The rates are the ones the source bill applied. They are written into the sheet rather
     * than hidden in code precisely so the office can see and change them — a recovery rate is
     * revised by circular, and a figure with no visible derivation is one nobody can correct.</p>
     */
    private static void writeRecovery(Workbook workbook, Styles styles, Header header,
                                      AbstractRefs refs) {
        Sheet sheet = workbook.createSheet("Recovery Statement");
        int r = writeHeaderBlock(sheet, styles, header, "RECOVERY STATEMENT", 5);

        Row gross = sheet.createRow(r++);
        text(gross, 1, "Gross work done", styles.cell);
        formula(gross, 4, refs.grossToDate(), styles.totalMoney);
        int grossRow = gross.getRowNum() + 1;

        Row head = sheet.createRow(r++);
        String[] columns = {"Sl", "Recovery", "Rate", "Total up to date", "Already recovered",
                "Now to be recovered"};
        for (int c = 0; c < columns.length; c++) {
            Cell cell = head.createCell(c);
            cell.setCellValue(columns[c]);
            cell.setCellStyle(styles.tableHead);
        }

        String[][] recoveries = {
                {"1", "Security deposit @ 2.5% on gross work done", "2.5%",
                        "ROUND(E%d*2.5%%,0)"},
                {"2", "Income tax @ 2% on gross work done / 1.18", "2%",
                        "ROUND(E%d/1.18*2%%,0)"},
                {"3", "GST @ 2% on gross work done / 1.18", "2%",
                        "ROUND(E%d/1.18*2%%,0)"},
                {"4", "Labour welfare cess @ 1% on gross work done / 1.1918", "1%",
                        "ROUND(E%d/1.1918*1%%,0)"},
        };

        int firstRow = r + 1;
        for (String[] recovery : recoveries) {
            Row row = sheet.createRow(r++);
            text(row, 0, recovery[0], styles.cell);
            text(row, 1, recovery[1], styles.cell);
            text(row, 2, recovery[2], styles.cell);
            formula(row, 3, recovery[3].formatted(grossRow), styles.money);
            number(row, 4, BigDecimal.ZERO, styles.money);
            int excelRow = row.getRowNum() + 1;
            formula(row, 5, "D%d-E%d".formatted(excelRow, excelRow), styles.totalMoney);
        }
        int lastRow = r;

        Row total = sheet.createRow(r++);
        text(total, 2, "Total recoveries", styles.totalLabel);
        formula(total, 3, "SUM(D%d:D%d)".formatted(firstRow, lastRow), styles.totalMoney);
        formula(total, 4, "SUM(E%d:E%d)".formatted(firstRow, lastRow), styles.totalMoney);
        formula(total, 5, "SUM(F%d:F%d)".formatted(firstRow, lastRow), styles.totalMoney);
        int totalRow = total.getRowNum() + 1;

        r++;
        Row since = sheet.createRow(r++);
        text(since, 1, "Net value of work since previous bill", styles.cell);
        formula(since, 4, refs.sinceTotal(), styles.totalMoney);
        int sinceRow = since.getRowNum() + 1;

        Row payable = sheet.createRow(r);
        text(payable, 1, "Net amount payable", styles.totalLabel);
        formula(payable, 4, "E%d-F%d".formatted(sinceRow, totalRow), styles.totalMoney);

        sheet.setColumnWidth(0, 6 * 256);
        sheet.setColumnWidth(1, 52 * 256);
        for (int c = 2; c <= 5; c++) {
            sheet.setColumnWidth(c, 18 * 256);
        }
    }

    // ------------------------------------------------------------------ deviation statement

    /**
     * Executed against tendered, item by item, with the saving and excess each side of the
     * permitted band.
     *
     * <p>The band comes off the agreement rather than being assumed, and the two columns are
     * kept apart rather than netted: a saving on one item does not pay for an excess on
     * another, and a statement that showed only the difference would say it did.</p>
     */
    private static void writeDeviation(Workbook workbook, Styles styles, Header header,
                                       List<Item> items, AbstractRefs refs) {
        Sheet sheet = workbook.createSheet("Deviation Statement");
        int r = writeHeaderBlock(sheet, styles, header, "DEVIATION STATEMENT", 8);
        r = caption(sheet, styles.caption, r,
                "Permissible deviation: " + (header.deviationLimitPct() == null ? "—"
                        : header.deviationLimitPct() + "%") + " of the tendered quantity.", 8) + 1;

        String[] columns = {"Item No.", "Description", "Unit", "Agt. Qty", "Executed Qty", "Rate",
                "Agt. Amount", "Executed Amount", "Saving / (Excess)"};
        Row head = sheet.createRow(r++);
        head.setHeightInPoints(30);
        for (int c = 0; c < columns.length; c++) {
            Cell cell = head.createCell(c);
            cell.setCellValue(columns[c]);
            cell.setCellStyle(styles.tableHead);
        }

        int firstRow = r + 1;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            Row row = sheet.createRow(r++);
            int excelRow = row.getRowNum() + 1;
            text(row, 0, item.itemNumber(), styles.cell);
            text(row, 1, item.description(), styles.wrapped);
            text(row, 2, item.unit(), styles.cell);
            number(row, 3, item.agreementQuantity(), styles.quantity);
            // Executed quantity is read off the abstract rather than copied, so the two sheets
            // cannot come to disagree about the same number.
            if (i < refs.itemTotalRows().size()) {
                formula(row, 4, "'%s'!D%d".formatted(ABSTRACT, refs.itemTotalRows().get(i)),
                        styles.quantity);
            } else {
                number(row, 4, BigDecimal.ZERO, styles.quantity);
            }
            number(row, 5, item.rate(), styles.money);
            formula(row, 6, "ROUND(D%d*F%d,2)".formatted(excelRow, excelRow), styles.money);
            formula(row, 7, "ROUND(E%d*F%d,2)".formatted(excelRow, excelRow), styles.money);
            formula(row, 8, "G%d-H%d".formatted(excelRow, excelRow), styles.totalMoney);
        }
        int lastRow = r;

        Row total = sheet.createRow(r);
        text(total, 5, "TOTAL", styles.totalLabel);
        formula(total, 6, "SUM(G%d:G%d)".formatted(firstRow, lastRow), styles.totalMoney);
        formula(total, 7, "SUM(H%d:H%d)".formatted(firstRow, lastRow), styles.totalMoney);
        formula(total, 8, "SUM(I%d:I%d)".formatted(firstRow, lastRow), styles.totalMoney);

        sheet.setColumnWidth(0, 12 * 256);
        sheet.setColumnWidth(1, 48 * 256);
        for (int c = 2; c <= 8; c++) {
            sheet.setColumnWidth(c, 16 * 256);
        }
    }

    // ------------------------------------------------------------------ shared furniture

    private static int writeHeaderBlock(Sheet sheet, Styles styles, Header header, String title,
                                        int lastColumn) {
        int r = 0;
        r = caption(sheet, styles.title, r, title, lastColumn);
        r = caption(sheet, styles.caption, r,
                "Name of work: " + orDash(header.workName()), lastColumn);
        r = caption(sheet, styles.caption, r,
                "Name of contractor: " + orDash(header.contractorName()), lastColumn);
        r = caption(sheet, styles.caption, r,
                "Agreement No: " + orDash(header.agreementNo())
                        + "        Division: " + orDash(header.division()), lastColumn);
        r = caption(sheet, styles.caption, r,
                "Serial No. of bill: " + orDash(header.billTitle())
                        + "        Measured up to: "
                        + (header.cutoffDate() == null ? "—" : header.cutoffDate().toString()),
                lastColumn);
        return r + 1;
    }

    private static int caption(Sheet sheet, CellStyle style, int r, String text, int lastColumn) {
        Row row = sheet.createRow(r);
        Cell cell = row.createCell(0);
        cell.setCellValue(text);
        cell.setCellStyle(style);
        if (lastColumn > 0) {
            sheet.addMergedRegion(new CellRangeAddress(r, r, 0, lastColumn));
        }
        return r + 1;
    }

    private static void text(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        if (value == null) {
            cell.setBlank();
        } else {
            cell.setCellValue(value);
        }
        cell.setCellStyle(style);
    }

    /** A null figure stays blank. A linear item has no breadth, and 0.00 would claim it had one. */
    private static void number(Row row, int column, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(column);
        if (value == null) {
            cell.setBlank();
        } else {
            cell.setCellValue(value.doubleValue());
        }
        cell.setCellStyle(style);
    }

    private static void formula(Row row, int column, String formula, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellFormula(formula);
        cell.setCellStyle(style);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String orDash(String value) {
        return isBlank(value) ? "—" : value;
    }

    private static String orBlank(String value) {
        return value == null ? "" : value;
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Built once per workbook: POI caps how many distinct styles a file may hold. */
    private static final class Styles {
        private final CellStyle title;
        private final CellStyle subtitle;
        private final CellStyle caption;
        private final CellStyle tableHead;
        private final CellStyle cell;
        private final CellStyle bold;
        private final CellStyle wrapped;
        private final CellStyle serial;
        private final CellStyle dimension;
        private final CellStyle quantity;
        private final CellStyle money;
        private final CellStyle totalLabel;
        private final CellStyle totalQuantity;
        private final CellStyle totalMoney;

        private Styles(Workbook workbook) {
            DataFormat formats = workbook.createDataFormat();

            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 13);

            Font boldFont = workbook.createFont();
            boldFont.setBold(true);

            title = workbook.createCellStyle();
            title.setFont(titleFont);
            title.setAlignment(HorizontalAlignment.CENTER);

            subtitle = workbook.createCellStyle();
            subtitle.setFont(boldFont);

            caption = workbook.createCellStyle();
            caption.setAlignment(HorizontalAlignment.LEFT);

            tableHead = workbook.createCellStyle();
            tableHead.setFont(boldFont);
            tableHead.setAlignment(HorizontalAlignment.CENTER);
            tableHead.setVerticalAlignment(VerticalAlignment.CENTER);
            tableHead.setWrapText(true);
            bordered(tableHead);

            cell = workbook.createCellStyle();
            bordered(cell);
            cell.setVerticalAlignment(VerticalAlignment.TOP);

            bold = workbook.createCellStyle();
            bordered(bold);
            bold.setFont(boldFont);

            wrapped = workbook.createCellStyle();
            bordered(wrapped);
            wrapped.setWrapText(true);
            wrapped.setVerticalAlignment(VerticalAlignment.TOP);

            // The Sl column counts rows; it is not a measurement and must not print 1.00.
            serial = workbook.createCellStyle();
            bordered(serial);
            serial.setAlignment(HorizontalAlignment.CENTER);
            serial.setDataFormat(formats.getFormat("0"));

            dimension = workbook.createCellStyle();
            bordered(dimension);
            dimension.setAlignment(HorizontalAlignment.RIGHT);
            dimension.setDataFormat(formats.getFormat("0.00#"));

            quantity = workbook.createCellStyle();
            bordered(quantity);
            quantity.setAlignment(HorizontalAlignment.RIGHT);
            quantity.setDataFormat(formats.getFormat("#,##0.00"));

            money = workbook.createCellStyle();
            bordered(money);
            money.setAlignment(HorizontalAlignment.RIGHT);
            money.setDataFormat(formats.getFormat("#,##0.00"));

            totalLabel = workbook.createCellStyle();
            bordered(totalLabel);
            totalLabel.setFont(boldFont);
            totalLabel.setAlignment(HorizontalAlignment.RIGHT);

            totalQuantity = workbook.createCellStyle();
            bordered(totalQuantity);
            totalQuantity.setFont(boldFont);
            totalQuantity.setAlignment(HorizontalAlignment.RIGHT);
            totalQuantity.setDataFormat(formats.getFormat("#,##0.00"));

            totalMoney = workbook.createCellStyle();
            bordered(totalMoney);
            totalMoney.setFont(boldFont);
            totalMoney.setAlignment(HorizontalAlignment.RIGHT);
            totalMoney.setDataFormat(formats.getFormat("#,##0.00"));
        }

        private static void bordered(CellStyle style) {
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
        }
    }
}

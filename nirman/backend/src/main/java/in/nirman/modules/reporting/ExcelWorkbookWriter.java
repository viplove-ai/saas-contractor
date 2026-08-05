package in.nirman.modules.reporting;

import in.nirman.common.BusinessException;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Turns a header row and a list of value rows into an .xlsx file. The only place in the
 * codebase that knows Apache POI exists, so a business module never imports a spreadsheet
 * library to answer a question about wages.
 *
 * <p>Uses the streaming workbook: a year of attendance for a large site is tens of
 * thousands of rows, and the in-memory model would hold all of it at once.</p>
 */
@Component
public class ExcelWorkbookWriter {

    /** Rows above the table itself — "Kausani Main Block", "1 Jul 2025 to 31 Jul 2025". */
    public record Caption(String label, String value) {
    }

    public byte[] write(String sheetName, List<Caption> captions, List<String> headers,
                        List<List<Object>> rows) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet(sheetName);
            CellStyle captionStyle = boldStyle(workbook);
            CellStyle headerStyle = headerStyle(workbook);

            int rowIndex = 0;
            for (Caption caption : captions) {
                Row row = sheet.createRow(rowIndex++);
                Cell label = row.createCell(0);
                label.setCellValue(caption.label());
                label.setCellStyle(captionStyle);
                row.createCell(1).setCellValue(caption.value());
            }
            if (!captions.isEmpty()) {
                rowIndex++;   // a blank line between the captions and the table
            }

            Row headerRow = sheet.createRow(rowIndex++);
            for (int column = 0; column < headers.size(); column++) {
                Cell cell = headerRow.createCell(column);
                cell.setCellValue(headers.get(column));
                cell.setCellStyle(headerStyle);
            }

            for (List<Object> values : rows) {
                Row row = sheet.createRow(rowIndex++);
                for (int column = 0; column < values.size(); column++) {
                    setValue(row.createCell(column), values.get(column));
                }
            }

            // Streaming sheets cannot auto-size (rows are flushed to disk), so widths are
            // set from the header text, which is what the columns are named by anyway.
            for (int column = 0; column < headers.size(); column++) {
                sheet.setColumnWidth(column,
                        Math.min(60, Math.max(10, headers.get(column).length() + 4)) * 256);
            }

            workbook.write(out);
            workbook.dispose();
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException("report.export-failed",
                    "The spreadsheet could not be generated.");
        }
    }

    private static void setValue(Cell cell, Object value) {
        switch (value) {
            case null -> cell.setBlank();
            case BigDecimal number -> cell.setCellValue(number.doubleValue());
            case Number number -> cell.setCellValue(number.doubleValue());
            case LocalDate date -> cell.setCellValue(date.toString());
            case Boolean flag -> cell.setCellValue(flag);
            default -> cell.setCellValue(String.valueOf(value));
        }
    }

    private static CellStyle boldStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private static CellStyle headerStyle(Workbook workbook) {
        CellStyle style = boldStyle(workbook);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }
}

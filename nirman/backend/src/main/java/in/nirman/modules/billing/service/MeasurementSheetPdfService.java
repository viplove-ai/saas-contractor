package in.nirman.modules.billing.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import in.nirman.common.BusinessException;
import in.nirman.modules.billing.repository.MeasurementSheetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import in.nirman.security.CurrentUserProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The blank master measurement sheet, as a printable PDF.
 *
 * <p>One generic design for every tender, printed in bulk. The engineer writes the item number
 * himself and names the project and item in the app when he enters the page — so the paper
 * never goes stale when a deviation item is approved, and one book travels to whichever site
 * he is posted to.</p>
 *
 * <p><b>The serial is the reason this is generated rather than photocopied.</b> Each sheet
 * carries a pre-printed number, and the measurement register refuses a serial it has already
 * seen. A page entered twice — by two people each assuming the other had not — is the error
 * that pays for a quantity twice, and this closes it for the cost of a printed number.</p>
 *
 * <p>The corner marks and the dropout-coloured boxes cost nothing today and are what would let
 * a photograph of a filled sheet be read automatically later, without reprinting the books.
 * Nothing reads them yet.</p>
 */
@Service
public class MeasurementSheetPdfService {

    private static final Logger log = LoggerFactory.getLogger(MeasurementSheetPdfService.class);

    /** Twelve rows: comfortable to write in, and still legible in a phone photograph. */
    private static final int ROWS_PER_SHEET = 12;
    private static final int MAX_SHEETS = 200;

    private final SpringTemplateEngine templates;
    private final MeasurementSheetRepository sheets;
    private final CurrentUserProvider currentUser;

    public MeasurementSheetPdfService(SpringTemplateEngine templates,
                                      MeasurementSheetRepository sheets,
                                      CurrentUserProvider currentUser) {
        this.templates = templates;
        this.sheets = sheets;
        this.currentUser = currentUser;
    }

    /**
     * @param nextSerial where the next print run should start
     * @param lastUsed   the highest serial already entered, or null if none has been. Returned
     *                   so the screen can say <i>why</i> it is suggesting that number rather
     *                   than presenting it as a fact — the office may hold a part-used book the
     *                   system has never seen, and only the person holding it knows.
     */
    public record SerialSuggestion(int nextSerial, Integer lastUsed) {
    }

    /**
     * Where to start the next run of blank sheets.
     *
     * <p>One serial, one sheet, for ever: the register refuses a number it has already seen, so
     * a run that reprinted numbers would produce paper that cannot be entered. Suggesting the
     * next one after the highest already used is the honest default, and it stays editable
     * because the system only knows about sheets that were entered — not about the forty blanks
     * still in the book.</p>
     */
    @PreAuthorize("hasAuthority('billing:measure')")
    public SerialSuggestion nextSerial() {
        Integer highest = sheets.highestSerialNumber(currentUser.currentOrgId());
        return new SerialSuggestion(highest == null ? 1 : highest + 1, highest);
    }

    /**
     * @param from  the first serial in the run. Sequential and permanent: the register's
     *              duplicate guard is only meaningful if a number is never printed twice.
     * @param count how many sheets
     */
    @PreAuthorize("hasAuthority('billing:measure')")
    public byte[] render(int from, int count) {
        if (count < 1 || count > MAX_SHEETS) {
            throw new BusinessException("billing.print-run-out-of-range",
                    "A print run is between 1 and " + MAX_SHEETS + " sheets. Print the trial "
                            + "twenty first and use them on a live site before committing to a "
                            + "bulk order.");
        }
        if (from < 1) {
            throw new BusinessException("billing.serial-must-be-positive",
                    "Sheet numbers start at 1 and never repeat.");
        }

        List<String> serials = IntStream.range(0, count)
                .mapToObj(offset -> "M-%06d".formatted(from + offset))
                .toList();

        Context context = new Context();
        context.setVariable("sheets", serials);
        context.setVariable("marks", cornerMarks());
        context.setVariable("boxes", SheetGeometry.boxes());
        context.setVariable("columnHeads", columnHeads());
        context.setVariable("locationRules", locationRules());
        context.setVariable("decimalPoints", decimalPoints());
        context.setVariable("totalTopMm", SheetGeometry.TOTAL_TOP_MM);

        return toPdf(templates.process("measurement-sheet", context));
    }


    // ------------------------------------------------------------------ layout, from geometry

    /** A positioned rectangle in page millimetres — what the template lays everything out with. */
    public record Placed(double leftMm, double topMm, double widthMm, double heightMm) {
    }

    public record Labelled(String label, double leftMm, double widthMm) {
    }

    /**
     * The four registration marks the reader uses as its origin.
     *
     * <p>The bottom-left one is wider than the other three, and that asymmetry is the only thing
     * telling a page photographed upside down from one the right way up. A symmetric set would
     * read a rotated sheet as perfectly valid and transpose every row on it.</p>
     */
    private static List<Placed> cornerMarks() {
        double inset = SheetGeometry.MARK_INSET_MM;
        double size = SheetGeometry.MARK_SIZE_MM;
        double wide = SheetGeometry.MARK_WIDE_SIZE_MM;
        double right = SheetGeometry.PAGE_WIDTH_MM - inset - size;
        double bottom = SheetGeometry.PAGE_HEIGHT_MM - inset - size;
        return List.of(
                new Placed(inset, inset, size, size),
                new Placed(right, inset, size, size),
                new Placed(inset, bottom, wide, size),
                new Placed(right, bottom, size, size));
    }

    private static List<Labelled> columnHeads() {
        List<Labelled> heads = new ArrayList<>();
        for (SheetGeometry.Field field : SheetGeometry.FIELDS) {
            heads.add(new Labelled(headingFor(field.name()), field.leftMm(), field.widthMm()));
        }
        return heads;
    }

    private static String headingFor(String field) {
        return switch (field) {
            case "nos" -> "Nos";
            case "mult" -> "\u00D7";
            case "length" -> "L";
            case "breadth" -> "B";
            case "height" -> "H";
            case "qty" -> "Qty";
            default -> field;
        };
    }

    /** The line each location is written on, one per row. */
    private static List<Placed> locationRules() {
        List<Placed> rules = new ArrayList<>();
        for (int row = 0; row < SheetGeometry.ROWS; row++) {
            double top = SheetGeometry.GRID_TOP_MM + row * SheetGeometry.ROW_HEIGHT_MM
                    + SheetGeometry.ROW_HEIGHT_MM - 2.5;
            rules.add(new Placed(SheetGeometry.LOCATION_LEFT_MM, top,
                    SheetGeometry.LOCATION_WIDTH_MM, 0));
        }
        return rules;
    }

    /**
     * Decimal points are printed, not written. One less stroke to recognise, and one less place
     * for a smudge to move a figure by a factor of ten.
     */
    private static List<Placed> decimalPoints() {
        List<Placed> dots = new ArrayList<>();
        for (int row = 0; row < SheetGeometry.ROWS; row++) {
            double top = SheetGeometry.GRID_TOP_MM + row * SheetGeometry.ROW_HEIGHT_MM
                    + SheetGeometry.ROW_HEIGHT_MM / 2;
            for (SheetGeometry.Field field : SheetGeometry.FIELDS) {
                addDot(dots, field, top);
            }
        }
        addDot(dots, SheetGeometry.TOTAL_FIELD,
                SheetGeometry.TOTAL_TOP_MM + SheetGeometry.BOX_H_MM / 2);
        return dots;
    }

    private static void addDot(List<Placed> dots, SheetGeometry.Field field, double topMm) {
        if (field.decimals() == 0) {
            return;
        }
        List<Double> lefts = field.boxLefts();
        int lastInteger = field.digits() - field.decimals() - 1;
        double left = lefts.get(lastInteger) + SheetGeometry.BOX_W_MM + 0.5;
        dots.add(new Placed(left, topMm, 2, 2));
    }

    private static byte[] toPdf(String html) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            // The caller gets nothing useful from a renderer stack trace, but whoever has to
            // fix the template does — and the error contract keeps it out of the response.
            log.error("Blank measurement sheet template failed to render", e);
            throw new BusinessException("billing.sheet-pdf-failed",
                    "The blank measurement sheets could not be rendered.");
        }
    }
}

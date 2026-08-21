package in.nirman.modules.billing.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import in.nirman.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import in.nirman.security.CurrentUserProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.ByteArrayOutputStream;
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
    private final CurrentUserProvider currentUser;

    public MeasurementSheetPdfService(SpringTemplateEngine templates,
                                      CurrentUserProvider currentUser) {
        this.templates = templates;
        this.currentUser = currentUser;
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
        List<Integer> rows = IntStream.rangeClosed(1, ROWS_PER_SHEET).boxed().toList();

        Context context = new Context();
        context.setVariable("serials", serials);
        context.setVariable("rows", rows);
        context.setVariable("orgId", currentUser.currentOrgId());

        return toPdf(templates.process("measurement-sheet", context));
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

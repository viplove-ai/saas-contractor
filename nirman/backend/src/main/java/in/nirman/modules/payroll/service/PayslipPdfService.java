package in.nirman.modules.payroll.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import in.nirman.common.BusinessException;
import in.nirman.modules.identity.service.StaffPayrollLookup;
import in.nirman.modules.payroll.api.dto.PayrollDtos.PayslipResponse;
import in.nirman.modules.payroll.domain.PayrollRun;
import in.nirman.modules.payroll.domain.Payslip;
import in.nirman.modules.payroll.repository.PayslipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * The printed payroll: the month's register, and the slip a person is handed.
 *
 * <p><b>Two documents, because they are read by two people.</b> The register is one landscape
 * page with a row per member — what the office reconciles against the bank transfer and files
 * with its returns. The payslip is one member's own page, and it is the document the Code on
 * Wages actually requires an employer to issue: the components, the days, every deduction
 * named, and the net.</p>
 *
 * <p>Everything printed comes out of the frozen {@code payslips} row and nothing is computed
 * here — unlike the daily report, which deliberately reads one section live. There is no
 * equivalent exception on a payslip: a slip printed a second time in November must be the
 * document that was handed over in July, down to the paise, or it is not a copy of
 * anything.</p>
 *
 * <p>A draft run prints too, and says so across the page. An office checks a month on paper
 * before it finalises it — that is what the paper is for — and refusing to print until the
 * figures were final would mean they were checked on a screen or not at all. What it may not
 * do is print a draft that looks final.</p>
 */
@Service
@Transactional(readOnly = true)
public class PayslipPdfService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM-yyyy");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d-MMM-yy");
    private static final DateTimeFormatter FILE_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final PayrollService payroll;
    private final PayslipRepository payslips;
    private final StaffPayrollLookup staff;
    private final SpringTemplateEngine templates;

    public PayslipPdfService(PayrollService payroll, PayslipRepository payslips,
                             StaffPayrollLookup staff, SpringTemplateEngine templates) {
        this.payroll = payroll;
        this.payslips = payslips;
        this.staff = staff;
        this.templates = templates;
    }

    /** The month on one landscape page: a row a member, and the totals underneath. */
    public Rendered register(UUID runId) {
        PayrollRun run = payroll.requireForRender(runId);
        List<Payslip> slips = payslips.findByRunIdOrderByEmployeeNameAsc(runId);

        Context context = baseContext(run);
        context.setVariable("slips", slips.stream()
                .map(slip -> PayrollService.toResponse(slip, !run.isDraft()))
                .toList());
        // The firm does not deduct professional tax, so the column is not printed — except on
        // a month drawn while it did, where the deductions have to come to the total beside
        // them. Decided here rather than in the page because summing a column of records from
        // a template is the kind of expression that works until somebody renames a field.
        context.setVariable("anyProfessionalTax", slips.stream()
                .anyMatch(slip -> slip.getProfessionalTax().signum() != 0));
        return new Rendered(toPdf(templates.process("payslip-register", context), "register"),
                "payroll-register-" + FILE_MONTH.format(run.getPeriodMonth()) + ".pdf");
    }

    /** One member's own slip. */
    public Rendered payslip(UUID payslipId) {
        Payslip slip = payroll.requirePayslipForRender(payslipId);
        PayrollRun run = payroll.requireForRender(slip.getRunId());

        Context context = baseContext(run);
        context.setVariable("slips",
                List.of(PayrollService.toResponse(slip, !run.isDraft())));
        return new Rendered(toPdf(templates.process("payslip", context), "payslip"),
                "payslip-" + FILE_MONTH.format(run.getPeriodMonth()) + "-"
                        + safe(slip.getEmployeeName()) + ".pdf");
    }

    /**
     * Every slip in the run, one to a page.
     *
     * <p>One request rather than twenty, because the alternative is an office downloading
     * twenty files and discovering on the nineteenth that it missed one.</p>
     */
    public Rendered allPayslips(UUID runId) {
        PayrollRun run = payroll.requireForRender(runId);
        List<Payslip> slips = payslips.findByRunIdOrderByEmployeeNameAsc(runId);
        if (slips.isEmpty()) {
            throw new BusinessException("payroll.nothing-to-print",
                    "There are no payslips in this run to print.");
        }
        Context context = baseContext(run);
        context.setVariable("slips", slips.stream()
                .map(slip -> PayrollService.toResponse(slip, !run.isDraft()))
                .toList());
        return new Rendered(toPdf(templates.process("payslip", context), "payslips"),
                "payslips-" + FILE_MONTH.format(run.getPeriodMonth()) + ".pdf");
    }

    private Context baseContext(PayrollRun run) {
        Context context = new Context();
        StaffPayrollLookup.EmployerInfo employer = staff.employer();
        context.setVariable("employer", employer);
        context.setVariable("monthLabel", MONTH.format(run.getPeriodMonth()));
        context.setVariable("periodLabel", DAY.format(run.getPeriodMonth())
                + " to " + DAY.format(run.periodEnd()));
        context.setVariable("payableDays", run.getPayableDays());
        // A draft's figures are still moving, and a page that does not say so is a page
        // somebody files. The same warning the daily report carries for the same reason.
        context.setVariable("draft", run.isDraft());
        context.setVariable("printedOn", LocalDate.now().format(DAY));
        return context;
    }

    public record Rendered(byte[] body, String fileName) {
    }

    private static byte[] toPdf(String html, String what) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new BusinessException("payroll.pdf-failed",
                    "The " + what + " could not be rendered as a PDF.");
        }
    }

    private static String safe(String name) {
        return name == null ? "member" : name.replaceAll("[^A-Za-z0-9]+", "-").toLowerCase();
    }
}

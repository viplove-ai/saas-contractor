package in.nirman.modules.dpr.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import in.nirman.common.BusinessException;
import in.nirman.modules.dpr.api.dto.DprDtos.DprResponse;
import in.nirman.modules.dpr.domain.DailyProgressReport;
import in.nirman.modules.project.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The printed daily progress report.
 *
 * <p>The PDF is why the DPR stores a frozen snapshot at all. It is the artefact that leaves the
 * system — printed, signed, sent to the department — and a document whose figures move after it
 * was issued is not a document. So this renders {@link DprResponse} exactly as the API returns
 * it, from the frozen columns, and computes nothing of its own.</p>
 *
 * <p>HTML through Thymeleaf, then openhtmltopdf, rather than a drawing API: a DPR is a form with
 * tables and a signature block, its layout will be argued over by people who are not
 * programmers, and a template is something they can be shown. The template is the one place the
 * paper layout lives.</p>
 *
 * <p>Photographs are listed by caption rather than embedded. Fetching a dozen site photographs
 * out of object storage to inline them would make a PDF request depend on MinIO being reachable
 * and turn a one-page form into a slow multi-megabyte download over a site connection — and the
 * captions are what a reader of the printed form actually needs. The images stay one signed URL
 * away in the app.</p>
 *
 * <p><b>Not every reader gets every section.</b> The same report goes to the department, to a
 * client's representative and into the firm's own file, and those are three different documents
 * cut from one: what the day cost is the firm's business and not the department's, the muster
 * roll carries names and wages, and a photograph list is noise on a covering sheet. So the
 * caller says which sections to print. What it may <em>not</em> do is drop one silently — a
 * page with no plant table reads as a site with no plant, and a document that lies by omission
 * is worse than a long one. Every omission is named in a line at the foot, which is the whole
 * of what makes the option safe to offer.</p>
 */
@Service
@Transactional(readOnly = true)
public class DprPdfService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    private final DprService dprs;
    private final DprResponses responses;
    private final ProjectRepository projects;
    private final SpringTemplateEngine templates;

    public DprPdfService(DprService dprs, DprResponses responses, ProjectRepository projects,
                         SpringTemplateEngine templates) {
        this.dprs = dprs;
        this.responses = responses;
        this.projects = projects;
        this.templates = templates;
    }

    /**
     * One printable part of the form.
     *
     * <p>The identity block, the conditions, the signatures and a lost day's cause are not on
     * the list and never come off: they are what makes the sheet a report of a particular day
     * at a particular site rather than an extract, and a page that can lose its own date is not
     * a document. What is here is what a reader might legitimately not be entitled to or not
     * want, each with the label the omission line prints.</p>
     */
    public enum Section {
        WORK("Work done"),
        LABOUR("Labour on site"),
        PLANT("Plant and machinery"),
        COST("What the day cost"),
        OBSERVATIONS("Observations"),
        PHOTOS("Photographs");

        private final String label;

        Section(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Everything, which is what a caller that says nothing gets. */
    public static final Set<Section> ALL_SECTIONS =
            java.util.Collections.unmodifiableSet(EnumSet.allOf(Section.class));

    /** @return the rendered PDF, and the file name it should be offered under */
    public Rendered render(UUID id) {
        return render(id, ALL_SECTIONS);
    }

    /**
     * @param sections what to print. Null or empty means everything — an empty set is a caller
     *                 that ticked nothing, and a sheet carrying only a letterhead and two
     *                 signature lines is not a thing anybody meant to ask for.
     */
    public Rendered render(UUID id, Set<Section> sections) {
        Set<Section> printing = sections == null || sections.isEmpty()
                ? ALL_SECTIONS : EnumSet.copyOf(sections);
        DailyProgressReport report = dprs.requireForRender(id);
        DprResponse dto = responses.toResponse(report);

        Context context = new Context();
        context.setVariable("dpr", dto);
        for (Section section : Section.values()) {
            context.setVariable("show" + section.name(), printing.contains(section));
        }
        // What was left off, by name and at the foot of the page. A reader holding a sheet
        // with no cost table cannot otherwise tell a cheap day from a redacted one.
        List<String> omitted = EnumSet.allOf(Section.class).stream()
                .filter(section -> !printing.contains(section))
                .map(Section::label)
                .toList();
        context.setVariable("omitted", omitted);
        context.setVariable("projectName", projects.findById(report.getProjectId())
                .map(project -> project.getName()).orElse("—"));
        context.setVariable("reportDate", dto.reportDate().format(DATE));
        context.setVariable("submittedAt", dto.submittedAt() == null ? null
                : STAMP.format(dto.submittedAt().atZone(ZoneId.systemDefault())));
        context.setVariable("verifiedAt", dto.verifiedAt() == null ? null
                : STAMP.format(dto.verifiedAt().atZone(ZoneId.systemDefault())));
        // A draft's figures are still moving, so the page says so across itself. A DPR that
        // looks final and is not is worse than no PDF at all.
        context.setVariable("draft", !dto.snapshotFrozen());

        String html = templates.process("dpr-report", context);
        return new Rendered(toPdf(html, dto.dprNumber()), fileNameFor(dto));
    }

    public record Rendered(byte[] body, String fileName) {
    }

    private static byte[] toPdf(String html, String dprNumber) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new BusinessException("dpr.pdf-failed",
                    "The report " + dprNumber + " could not be rendered as a PDF.");
        }
    }

    private static String fileNameFor(DprResponse dto) {
        return "%s-%s-%s.pdf".formatted(dto.dprNumber(),
                        dto.siteName() == null ? "site" : dto.siteName(), dto.reportDate())
                .replaceAll("[^A-Za-z0-9._-]", "-");
    }
}

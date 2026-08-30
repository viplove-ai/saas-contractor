package in.nirman.modules.identity.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import in.nirman.common.BusinessException;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.identity.api.dto.StaffDtos.OfferLetterRequest;
import in.nirman.modules.identity.api.dto.StaffDtos.StaffDocumentResponse;
import in.nirman.modules.identity.domain.Organisation;
import in.nirman.modules.identity.domain.StaffDocument;
import in.nirman.modules.identity.domain.StaffProfile;
import in.nirman.modules.identity.domain.StaffSalaryRevision;
import in.nirman.modules.identity.domain.User;
import in.nirman.modules.identity.repository.OrganisationRepository;
import in.nirman.modules.identity.repository.StaffProfileRepository;
import in.nirman.modules.identity.repository.StaffSalaryRevisionRepository;
import in.nirman.modules.identity.repository.UserRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * The letter that offers somebody the job, written off the record rather than off a diary.
 *
 * <p><b>It states what the record already says.</b> The terms on an offer letter — the
 * designation, the joining date, the probation and its length, the structure the salary is
 * made of — are every one of them already columns on {@link StaffProfile} and
 * {@link StaffSalaryRevision}, typed by somebody who decided them. A screen that asked for
 * them again at letter time would be a second place to state the same terms, and the letter
 * and the payroll would then disagree about what was agreed with the man they both describe.
 * So this reads them and refuses to be told them; what the request carries is only what
 * belongs to the letter and to nothing else — where he is posted, whom he reports to, and by
 * when he must answer.</p>
 *
 * <p><b>It is filed on his record, not merely downloaded.</b> V51 already holds the papers a
 * staff record was typed off, and a letter the firm itself wrote is exactly such a paper —
 * the one, in fact, that every other figure on the record is supposed to agree with. So the
 * rendered PDF goes into storage and a {@code staff_documents} row of type
 * {@code OFFER_LETTER} names it, through the same register and the same permission as a
 * scanned passbook. An offer letter that lived only in the browser's download folder would be
 * the one term of employment nobody could produce when it was disputed.</p>
 *
 * <p><b>No new permission.</b> {@code staff:write} is already this act: stating terms that
 * are on the record and filing the result beside the papers those terms were read off is one
 * job of custody, which is the argument V51 made about holding a bank account number and
 * holding the picture of the passbook it was copied from.</p>
 *
 * <p><b>It computes no deductions.</b> The letter says what he is paid and states that the
 * provident fund, the state insurance and gratuity apply as the statutes require; it does not
 * print a net figure. A net depends on a tax regime he has not yet elected and declarations he
 * has not yet made, and a candidate who was shown a take-home on the day he was offered the
 * job and a smaller one on his first payslip has been told two things by the same employer.</p>
 */
@Service
@Transactional
public class OfferLetterService {

    private static final String ENTITY = "STAFF_DOCUMENT";
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final UserRepository users;
    private final StaffProfileRepository profiles;
    private final StaffSalaryRevisionRepository salaries;
    private final OrganisationRepository organisations;
    private final StaffDocumentService staffDocuments;
    private final SpringTemplateEngine templates;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public OfferLetterService(UserRepository users, StaffProfileRepository profiles,
                              StaffSalaryRevisionRepository salaries,
                              OrganisationRepository organisations,
                              StaffDocumentService staffDocuments,
                              SpringTemplateEngine templates, CurrentUserProvider currentUser,
                              AuditService audit) {
        this.users = users;
        this.profiles = profiles;
        this.salaries = salaries;
        this.organisations = organisations;
        this.staffDocuments = staffDocuments;
        this.templates = templates;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    /**
     * The letter, rendered and handed back without being kept.
     *
     * <p>Because a letter is read before it is sent. An office that could only produce one by
     * filing it would file three drafts on the way to the one it meant.</p>
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('staff:write')")
    public Rendered preview(UUID userId, OfferLetterRequest request) {
        return render(userId, request);
    }

    /**
     * The letter, rendered and filed on his record as a paper.
     *
     * <p>Generated afresh rather than taking the previewed bytes back from the browser: a
     * document the client could hand us is a document the client could have edited, and the
     * one thing the firm's own letter must be is the firm's own letter.</p>
     */
    @PreAuthorize("hasAuthority('staff:write')")
    public StaffDocumentResponse issue(UUID userId, OfferLetterRequest request) {
        Rendered rendered = render(userId, request);
        // Filed through the ordinary register, so a letter the firm wrote sits beside the
        // Aadhaar card the candidate brought and is found by the same query.
        StaffDocumentResponse document = staffDocuments.attachGenerated(userId,
                StaffDocument.Type.OFFER_LETTER,
                "Offer letter " + rendered.reference() + ", issued "
                        + rendered.letterDate().format(LONG_DATE),
                rendered.body(), rendered.fileName());
        audit.record(ENTITY, document.id(), "OFFER_LETTER_ISSUED", null,
                Map.of("userId", userId.toString(), "reference", rendered.reference()), null);
        return document;
    }

    // ------------------------------------------------------------------ internals

    private Rendered render(UUID userId, OfferLetterRequest request) {
        UUID orgId = currentUser.currentOrgId();
        User user = users.findById(userId)
                .filter(candidate -> candidate.getOrgId().equals(orgId))
                .orElseThrow(() -> BusinessException.notFound("User", userId));
        StaffProfile profile = profiles.findByUserId(userId)
                .orElseThrow(() -> new BusinessException("staff.no-record-for-offer",
                        "There is no staff record for " + user.getFullName() + " yet. An offer "
                                + "letter states the terms on the record, so the record has to "
                                + "exist first — the designation, the joining date and the "
                                + "salary structure are what the letter is made of."));

        LocalDate joining = request.joiningOn() != null ? request.joiningOn()
                : profile.getJoinedOn();
        if (joining == null) {
            throw new BusinessException("staff.offer-needs-a-joining-date",
                    "An offer letter has to say when he starts. Record a joining date on the "
                            + "staff record, or give one with the letter.");
        }

        StaffSalaryRevision structure = structureFor(userId, joining);
        if (structure == null) {
            throw new BusinessException("staff.offer-needs-a-salary",
                    "No salary has been recorded for " + user.getFullName() + ". An offer "
                            + "letter with no figure in it is not an offer.");
        }
        if (!structure.isStructured()) {
            throw new BusinessException("staff.offer-needs-a-structure",
                    "The salary on record for " + user.getFullName() + " is a total with no "
                            + "breakdown. An offer letter has to say what the pay is made of — "
                            + "the provident fund and gratuity he is being promised are "
                            + "computed on the basic, not on the total.");
        }

        Organisation employer = organisations.findById(orgId)
                .orElseThrow(() -> BusinessException.notFound("Organisation", orgId));
        LocalDate letterDate = request.letterDate() != null ? request.letterDate()
                : LocalDate.now();
        String reference = blankToNull(request.reference()) != null
                ? request.reference().trim()
                : defaultReference(employer, profile, letterDate);

        Context context = new Context();
        context.setVariable("employer", employer);
        context.setVariable("candidateName", user.getFullName());
        context.setVariable("address", profile.getCurrentAddress() != null
                ? profile.getCurrentAddress() : profile.getPermanentAddress());
        context.setVariable("reference", reference);
        context.setVariable("letterDate", letterDate.format(LONG_DATE));
        context.setVariable("joiningDate", joining.format(LONG_DATE));
        context.setVariable("designation", profile.getDesignation());
        context.setVariable("employmentType", profile.getEmploymentType());
        context.setVariable("probationDays", profile.getProbationDays());
        context.setVariable("probationEnds", profile.getProbationDays() == null ? null
                : joining.plusDays(profile.getProbationDays()).format(LONG_DATE));
        context.setVariable("contractEndsOn", profile.getContractEndsOn() == null ? null
                : profile.getContractEndsOn().format(LONG_DATE));
        context.setVariable("noticePeriodDays", profile.getNoticePeriodDays());
        context.setVariable("placeOfPosting", blankToNull(request.placeOfPosting()));
        context.setVariable("reportingTo", blankToNull(request.reportingTo()));
        context.setVariable("respondBy", request.respondBy() == null ? null
                : request.respondBy().format(LONG_DATE));
        context.setVariable("signatoryName", blankToNull(request.signatoryName()));
        context.setVariable("signatoryDesignation", blankToNull(request.signatoryDesignation()));

        context.setVariable("structure", structure);
        context.setVariable("annual", structure.getMonthlyAmount()
                .multiply(BigDecimal.valueOf(12)).setScale(2, RoundingMode.HALF_UP));
        // The wage the statutes will actually work on, printed on the annexure so that the
        // candidate is told the basis of the fund he is being promised rather than left to
        // infer it from a table. It is also the number that tells the office writing the
        // structure whether its split is doing what it thinks.
        context.setVariable("statutoryWages", structure.statutoryWages());
        context.setVariable("pfApplicable", profile.isPfApplicable());
        context.setVariable("esiApplicable", profile.isEsiApplicable());

        String html = templates.process("offer-letter", context);
        String fileName = "offer-letter-" + FILE_DATE.format(letterDate) + "-"
                + safe(user.getFullName()) + ".pdf";
        return new Rendered(toPdf(html), fileName, reference, letterDate);
    }

    /**
     * The structure to quote: the one in force on the day he starts, and failing that the
     * earliest ever recorded for him.
     *
     * <p>The fallback is what makes the ordinary case work. An office writing an offer records
     * the structure effective from the joining date, so the first branch finds it. But a
     * structure recorded a week <em>after</em> the intended start — because the date moved, or
     * because somebody typed the terms before fixing the day — would otherwise leave the
     * letter with nothing to say, and refusing to write it over an ordering nobody thought
     * about would send the office back to a diary.</p>
     */
    private StaffSalaryRevision structureFor(UUID userId, LocalDate joining) {
        var history = salaries.findByUserIdOrderByEffectiveFromDesc(userId);
        return history.stream()
                .filter(revision -> !revision.getEffectiveFrom().isAfter(joining))
                .findFirst()
                .orElseGet(() -> history.isEmpty() ? null : history.get(history.size() - 1));
    }

    /**
     * A reference the letter can be filed under when the office has no scheme of its own.
     *
     * <p>Built from the organisation's code, the year and the employee number, because those
     * are the three things an office searches a filing cabinet by. It is offered rather than
     * imposed — the request may carry any reference at all — since a firm that already numbers
     * its correspondence has a scheme this one would only fight with.</p>
     */
    private static String defaultReference(Organisation employer, StaffProfile profile,
                                           LocalDate letterDate) {
        String who = profile.getEmployeeNumber() != null ? profile.getEmployeeNumber()
                : profile.getId().toString().substring(0, 6).toUpperCase();
        return employer.getCode() + "/HR/" + letterDate.getYear() + "/" + who;
    }

    public record Rendered(byte[] body, String fileName, String reference,
                           LocalDate letterDate) {
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
            throw new BusinessException("staff.offer-letter-failed",
                    "The offer letter could not be rendered as a PDF.");
        }
    }

    private static String safe(String name) {
        return name == null ? "candidate" : name.replaceAll("[^A-Za-z0-9]+", "-").toLowerCase();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

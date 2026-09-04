package in.nirman.modules.identity.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import in.nirman.common.BusinessException;
import in.nirman.common.StatutoryContributions;
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
import in.nirman.modules.attachment.service.AttachmentLookup;
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
 * belongs to the letter and to nothing else — where he is posted and by when he must
 * answer.</p>
 *
 * <p><b>The administrator signs it, and the letter knows who he is.</b> The signatory used to
 * be two boxes on the form — a name and a post — which is a letter that can go out over any
 * name at all, typed by whoever held {@code staff:write} that afternoon. It is now the person
 * issuing it: only an administrator may, his name is read off the session, and his signature
 * — the picture he uploaded on his own account, V60 — is drawn over the line. A letter is
 * refused issue while he has none, because an offer that goes out unsigned is what the picture
 * exists to prevent; a preview is allowed to show the line blank, since a letter is read before
 * it is signed.</p>
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
 * <p><b>It prints the deductions it can compute, and no net.</b> The provident fund and the
 * state insurance are arithmetic on the structure — they depend on nothing the candidate has
 * yet decided — so the annexure states them, because "subject to statutory deductions" is the
 * sentence every candidate reads as meaning nothing and then queries on his first payslip. The
 * tax is a different case and stays out: it depends on a regime he has not elected and
 * declarations he has not made. That is also why there is no net figure. A take-home shown on
 * the day of the offer and a smaller one on the first payslip is two statements from one
 * employer, and the difference would be exactly the tax this letter cannot know.</p>
 */
@Service
@Transactional
public class OfferLetterService {

    private static final String ENTITY = "STAFF_DOCUMENT";
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** Indian grouping, the way every other rupee figure in this system is written. */
    private static final java.text.NumberFormat MONEY =
            java.text.NumberFormat.getInstance(new java.util.Locale("en", "IN"));

    static {
        MONEY.setMinimumFractionDigits(2);
        MONEY.setMaximumFractionDigits(2);
    }

    private final UserRepository users;
    private final StaffProfileRepository profiles;
    private final StaffSalaryRevisionRepository salaries;
    private final OrganisationRepository organisations;
    private final StaffDocumentService staffDocuments;
    private final AttachmentLookup attachments;
    private final SpringTemplateEngine templates;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public OfferLetterService(UserRepository users, StaffProfileRepository profiles,
                              StaffSalaryRevisionRepository salaries,
                              OrganisationRepository organisations,
                              StaffDocumentService staffDocuments,
                              AttachmentLookup attachments,
                              SpringTemplateEngine templates, CurrentUserProvider currentUser,
                              AuditService audit) {
        this.users = users;
        this.profiles = profiles;
        this.salaries = salaries;
        this.organisations = organisations;
        this.staffDocuments = staffDocuments;
        this.attachments = attachments;
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
        return render(userId, request, false);
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
        Rendered rendered = render(userId, request, true);
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

    /**
     * @param issuing whether the letter is going out. An issued letter must carry the
     *                signatory's signature; a preview may show the line blank.
     */
    private Rendered render(UUID userId, OfferLetterRequest request, boolean issuing) {
        UUID orgId = currentUser.currentOrgId();
        // The signatory: the administrator issuing it, and only an administrator. staff:write
        // is the accountant's too since V54, and the accountant keeps the record without
        // thereby being the person whose name goes at the foot of the firm's offer.
        if (!currentUser.isAdmin()) {
            throw BusinessException.forbidden(
                    "Only an administrator signs an offer letter.");
        }
        User signatory = users.findByIdAndOrgId(currentUser.currentUserIdOrNull(), orgId)
                .orElseThrow(() -> BusinessException.forbidden("Sign in to issue a letter."));
        String signature = signatory.getSignatureAttachmentId() == null ? null
                : attachments.dataUri(signatory.getSignatureAttachmentId()).orElse(null);
        if (issuing && signature == null) {
            throw new BusinessException("staff.offer-needs-your-signature",
                    "The letter goes out over your signature, and there is none on your "
                            + "account yet. Upload it on your account screen and issue the "
                            + "letter again.");
        }
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
        context.setVariable("respondBy", request.respondBy() == null ? null
                : request.respondBy().format(LONG_DATE));
        context.setVariable("signatoryName", signatory.getFullName());
        context.setVariable("signatureDataUri", signature);

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

        /*
          The deductions, on a full month with nothing lost. Computed through the same class
          the payroll uses, so the figure on the letter is the figure on his first payslip
          rather than a second implementation that agrees with it by luck.
        */
        StatutoryContributions.Result statutory = StatutoryContributions.of(
                structure.getBasic().add(orZero(structure.getDearnessAllowance())),
                orZero(structure.getHra()).add(orZero(structure.getConveyance()))
                        .add(orZero(structure.getOtherAllowance())),
                BigDecimal.ZERO, structure.getMonthlyAmount(), BigDecimal.ONE,
                profile.isPfApplicable(), profile.isEsiApplicable(), profile.isPfOnFullWages());
        context.setVariable("statutory", statutory);
        context.setVariable("totalDeductions",
                statutory.pfEmployee().add(statutory.esiEmployee()));

        // What the firm provides on top of the packet. Facilities rather than money, and
        // deliberately not components of it — see StaffProfile on why the statute keeps them
        // out of wages.
        /*
          Written here as whole sentences rather than assembled from spans in the template, and
          not for tidiness. openhtmltopdf writes each inline run into the PDF separately and an
          extractor reads them back in layout order, so a sentence built from three conditional
          spans comes out of a copy-paste with its clauses interleaved — which is precisely how
          somebody reads this letter when they forward it in an email. One string is one run.
        */
        context.setVariable("accommodationSentence", profile.isAccommodationProvided()
                ? profile.getAccommodationNote() == null
                        ? "Accommodation will be provided to you."
                        : "Accommodation will be provided to you — "
                                + profile.getAccommodationNote() + "."
                : null);
        context.setVariable("fuelSentence", fuelSentence(profile));
        context.setVariable("providesAnything",
                profile.isAccommodationProvided() || profile.isFuelProvided());

        /*
          The clause numbers, worked out here rather than counted in the template.

          Several clauses are conditional — a probation clause only for somebody on probation,
          a term clause only for a fixed engagement — so a counter running over the whole list
          would number a permanent member's letter 1, 2, 4, 5. Deciding it in Java means the
          conditions live in one place and the template only prints what it is given, which is
          also the only arrangement Thymeleaf will allow: SpEL refuses to call a mutating
          method on a context variable, so a counter incremented from the page cannot work.
        */
        java.util.Map<String, Integer> termNo = new java.util.HashMap<>();
        int n = 1;
        termNo.put("joining", n++);
        if (blankToNull(request.placeOfPosting()) != null) {
            termNo.put("posting", n++);
        }
        if (profile.getEmploymentType() == StaffProfile.EmploymentType.PROBATION) {
            termNo.put("probation", n++);
        }
        if (profile.getEmploymentType() == StaffProfile.EmploymentType.CONTRACTUAL) {
            termNo.put("term", n++);
        }
        termNo.put("remuneration", n++);
        termNo.put("statutory", n++);
        if (profile.isAccommodationProvided() || profile.isFuelProvided()) {
            termNo.put("provides", n++);
        }
        if (profile.getNoticePeriodDays() != null) {
            termNo.put("notice", n++);
        }
        termNo.put("documents", n++);
        termNo.put("general", n);
        context.setVariable("termNo", termNo);

        String html = templates.process("offer-letter", context);
        String fileName = "offer-letter-" + FILE_DATE.format(letterDate) + "-"
                + safe(user.getFullName()) + ".pdf";
        return new Rendered(toPdf(html), fileName, reference, letterDate);
    }

    /**
     * What the letter says about fuel, as one sentence.
     *
     * <p>A figure means a fixed monthly allowance; no figure means reimbursement at actuals,
     * which is the commoner arrangement and is not the same statement as zero.</p>
     */
    private static String fuelSentence(StaffProfile profile) {
        if (!profile.isFuelProvided()) {
            return null;
        }
        String sentence = profile.getFuelMonthlyAmount() == null
                ? "Fuel for the running of your motorcycle on the firm's work will be "
                        + "reimbursed at actuals against bills."
                : "A fuel allowance of Rs. "
                        + MONEY.format(profile.getFuelMonthlyAmount())
                        + " a month will be paid for the running of your motorcycle on the "
                        + "firm's work.";
        return profile.getFuelNote() == null ? sentence : sentence + " " + profile.getFuelNote();
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

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

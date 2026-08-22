package in.nirman.modules.billing.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.billing.api.dto.BillingDtos.AgreementRequest;
import in.nirman.modules.billing.api.dto.BillingDtos.AgreementResponse;
import in.nirman.modules.billing.domain.Agreement;
import in.nirman.modules.billing.api.dto.VaultDtos.AgreementSuggestion;
import in.nirman.modules.billing.api.dto.VaultDtos.TenderDocumentRequest;
import in.nirman.modules.billing.api.dto.VaultDtos.TenderDocumentResponse;
import in.nirman.modules.billing.domain.AgreementDocument;
import in.nirman.modules.billing.domain.ReferenceDocument;
import in.nirman.modules.billing.repository.AgreementDocumentRepository;
import in.nirman.modules.billing.repository.AgreementRepository;
import in.nirman.modules.billing.repository.ReferenceDocumentRepository;
import in.nirman.modules.tender.service.NitLookup;
import in.nirman.security.CurrentUserProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The tender's own details, asked once and standing for every bill of that tender.
 *
 * <p>Two kinds of thing live here and they are here for the same reason. The <b>names</b> —
 * contractor, the officer whose measurements the bill is based on, who prepared it, who
 * checked it, the division — print on every page of every bill and change only between
 * tenders. The <b>rate chain</b> — coefficient, cost index, tender percentage — is the
 * arithmetic that turns a published schedule rate into what this contract pays.</p>
 *
 * <p>Both were previously retyped into forty-three worksheets by hand, which is how the
 * workbook this was designed against came to say "3rd RA Bill" on its measurement pages,
 * "4th RA Bill" on its abstract, and to carry a sheet named MB1st RA. Asked once, at the
 * first bill of the tender, and never again.</p>
 */
@Service
@Transactional
public class AgreementService {

    private static final String ENTITY_TYPE = "AGREEMENT";

    /** What the chain does to a round thousand, so a screen can show the arithmetic. */
    private static final BigDecimal SAMPLE_RATE = new BigDecimal("1000");

    private final AgreementRepository agreements;
    private final AgreementDocumentRepository agreementDocuments;
    private final ReferenceDocumentRepository documents;
    private final NitLookup notices;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public AgreementService(AgreementRepository agreements,
                            AgreementDocumentRepository agreementDocuments,
                            ReferenceDocumentRepository documents, NitLookup notices,
                            CurrentUserProvider currentUser, AuditService audit) {
        this.agreements = agreements;
        this.agreementDocuments = agreementDocuments;
        this.documents = documents;
        this.notices = notices;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ what governs it

    /**
     * The editions this tender is priced under.
     *
     * <p>Read from what was stored against the tender, never from whichever edition is current.
     * An agreement let in 2025 stays a DSR 2023 agreement after DSR 2026 is published, and a
     * bill that repriced itself when the shelf changed would invent money.</p>
     */
    @PreAuthorize("hasAuthority('billing:read')")
    @Transactional(readOnly = true)
    public List<TenderDocumentResponse> documentsFor(UUID projectId) {
        return agreements.findByOrgIdAndProjectId(currentUser.currentOrgId(), projectId)
                .map(agreement -> agreementDocuments
                        .findByAgreementIdOrderByRoleAsc(agreement.getId()).stream()
                        .map(this::toTenderDocument)
                        .toList())
                .orElseGet(List::of);
    }

    /**
     * Records which editions govern this tender. Replaces the set wholesale: the office is
     * answering "what was this priced under" as a whole, and a partial update would leave a
     * document on the record that the notice does not cite.
     */
    @PreAuthorize("hasAuthority('billing:prepare')")
    public List<TenderDocumentResponse> linkDocuments(UUID projectId,
                                                      List<TenderDocumentRequest> requests) {
        Agreement agreement = agreements.findByOrgIdAndProjectId(currentUser.currentOrgId(), projectId)
                .orElseThrow(() -> new BusinessException("billing.agreement-required",
                        "The tender's details come first — a document governs an agreement, and "
                                + "this project has none recorded yet."));

        agreementDocuments.deleteByAgreementId(agreement.getId());
        agreementDocuments.flush();

        List<AgreementDocument> saved = new ArrayList<>();
        for (TenderDocumentRequest request : requests == null ? List.<TenderDocumentRequest>of() : requests) {
            ReferenceDocument document = documents
                    .findByIdAndOrgIdAndDeletedAtIsNull(request.documentId(),
                            currentUser.currentOrgId())
                    .orElseThrow(() -> BusinessException.notFound("Reference document",
                            request.documentId()));
            // A superseded edition may still be cited — that is the point of storing the link.
            // A withdrawn one may not: nothing is left to open.
            if (document.getStatus() == ReferenceDocument.Status.WITHDRAWN) {
                throw new BusinessException("vault.document-withdrawn",
                        document.getCode() + " has been withdrawn, so a bill priced under it "
                                + "could not be produced when it was questioned.");
            }
            saved.add(agreementDocuments.save(new AgreementDocument(agreement.getId(),
                    request.documentId(), request.role(), request.workPart())));
        }

        audit.record(ENTITY_TYPE, agreement.getId(), "LINK_DOCUMENTS", null,
                Map.of("projectId", projectId.toString(), "documents", saved.size()), null);
        return saved.stream().map(this::toTenderDocument).toList();
    }

    /**
     * What the notice said, so the agreement form offers a figure to confirm rather than a box
     * to transcribe into.
     *
     * <p>The reader has been extracting the DSR year and the cost index off every notice since
     * the tender module was built; until the vault existed there was nothing to do with them.
     * Suggestions only — nothing here decides anything.</p>
     */
    @PreAuthorize("hasAuthority('billing:read')")
    @Transactional(readOnly = true)
    public AgreementSuggestion suggestion(UUID projectId) {
        NitLookup.RateBasis basis = notices.rateBasis(projectId)
                .orElse(new NitLookup.RateBasis(null, null, null, null));

        List<ReferenceDocument> matches = new ArrayList<>();
        for (Integer year : new Integer[] {basis.civilDsrYear(), basis.electricalDsrYear()}) {
            if (year == null) {
                continue;
            }
            for (ReferenceDocument.Kind kind
                    : new ReferenceDocument.Kind[] {ReferenceDocument.Kind.DSR,
                            ReferenceDocument.Kind.DAR}) {
                documents.findCurrent(currentUser.currentOrgId(), kind, false, year).stream()
                        .filter(candidate -> matches.stream()
                                .noneMatch(seen -> seen.getId().equals(candidate.getId())))
                        .forEach(matches::add);
            }
        }

        return new AgreementSuggestion(basis.civilDsrYear(), basis.civilCostIndexPercent(),
                basis.electricalDsrYear(), basis.electricalCostIndexPercent(),
                matches.stream().map(ReferenceDocumentService::toResponse).toList());
    }

    private TenderDocumentResponse toTenderDocument(AgreementDocument link) {
        ReferenceDocument document = documents.findById(link.getDocumentId()).orElse(null);
        return new TenderDocumentResponse(link.getId(), link.getDocumentId(), link.getRole(),
                link.getWorkPart(),
                document == null ? null : document.getCode(),
                document == null ? null : document.getTitle(),
                document == null ? null : document.getEditionYear(),
                document == null ? null : document.getStatus(),
                document == null ? null : document.getAttachmentId());
    }

    /**
     * Creates the agreement or amends it. One row per project, so this is an upsert rather
     * than a create — the screen that asks for these details is the same screen whether it is
     * the first bill or a correction three bills later.
     */
    @PreAuthorize("hasAuthority('billing:prepare')")
    public AgreementResponse save(UUID projectId, AgreementRequest request) {
        UUID orgId = currentUser.currentOrgId();
        Agreement agreement = agreements.findByOrgIdAndProjectId(orgId, projectId)
                .orElseGet(() -> new Agreement(orgId, projectId));
        boolean isNew = agreement.getVersion() == null;

        if (request.dsrCoefficient().signum() <= 0) {
            throw new BusinessException("billing.coefficient-must-be-positive",
                    "The coefficient factor multiplies every derived rate, so zero or less "
                            + "would price the work at nothing. It is 1 when the schedule rate "
                            + "is taken as printed.");
        }

        agreement.setAgreementNo(request.agreementNo());
        agreement.setDivision(request.division());
        agreement.setSubDivision(request.subDivision());
        agreement.setDsrScheduleId(request.dsrScheduleId());
        agreement.setDsrCoefficient(request.dsrCoefficient());
        agreement.setCostIndexPct(request.costIndexPct());
        agreement.setTenderPct(request.tenderPct());
        agreement.setDeviationLimitPct(request.deviationLimitPct());
        agreement.setContractorName(request.contractorName());
        agreement.setMeasuredByName(request.measuredByName());
        agreement.setMeasuredByDesignation(request.measuredByDesignation());
        agreement.setPreparedByName(request.preparedByName());
        agreement.setPreparedByDesignation(request.preparedByDesignation());
        agreement.setCheckedByName(request.checkedByName());
        agreement.setCheckedByDesignation(request.checkedByDesignation());
        agreement.setExecutiveEngineer(request.executiveEngineer());
        agreement.setCmbNo(request.cmbNo());
        agreement.setEstimatedCost(request.estimatedCost());
        agreement.setTenderedCost(request.tenderedCost());
        agreement.setDateOfStart(request.dateOfStart());
        agreement.setStipulatedCompletion(request.stipulatedCompletion());
        agreements.save(agreement);

        audit.record(ENTITY_TYPE, agreement.getId(), isNew ? "CREATE" : "UPDATE", null,
                Map.of("projectId", projectId.toString(),
                        "agreementNo", String.valueOf(request.agreementNo()),
                        "dsrCoefficient", request.dsrCoefficient(),
                        "costIndexPct", request.costIndexPct(),
                        "tenderPct", request.tenderPct()), null);
        return toResponse(agreement);
    }

    @PreAuthorize("hasAuthority('billing:read')")
    @Transactional(readOnly = true)
    public Optional<AgreementResponse> find(UUID projectId) {
        return agreements.findByOrgIdAndProjectId(currentUser.currentOrgId(), projectId)
                .map(AgreementService::toResponse);
    }

    private static AgreementResponse toResponse(Agreement a) {
        return new AgreementResponse(a.getId(), a.getProjectId(), a.getAgreementNo(),
                a.getDivision(), a.getSubDivision(), a.getDsrScheduleId(), a.getDsrCoefficient(),
                a.getCostIndexPct(), a.getTenderPct(), a.getDeviationLimitPct(),
                a.getContractorName(), a.getMeasuredByName(), a.getMeasuredByDesignation(),
                a.getPreparedByName(), a.getPreparedByDesignation(), a.getCheckedByName(),
                a.getCheckedByDesignation(), a.getExecutiveEngineer(), a.getCmbNo(),
                a.getEstimatedCost(), a.getTenderedCost(), a.getDateOfStart(),
                a.getStipulatedCompletion(), a.deriveRate(SAMPLE_RATE), a.getVersion());
    }
}

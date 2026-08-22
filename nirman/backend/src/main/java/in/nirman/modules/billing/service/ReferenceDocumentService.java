package in.nirman.modules.billing.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.billing.api.dto.VaultDtos.DocumentRequest;
import in.nirman.modules.billing.api.dto.VaultDtos.DocumentResponse;
import in.nirman.modules.billing.domain.ReferenceDocument;
import in.nirman.modules.billing.repository.AgreementDocumentRepository;
import in.nirman.modules.billing.repository.ReferenceDocumentRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The shelf: the editions a bill is prepared against, and the file for each.
 *
 * <p>Three things this class is for.</p>
 *
 * <p><b>Registering an edition before the copy is found.</b> The office knows a tender cites
 * DSR 2023 the moment it reads the notice, and may not have a PDF for weeks. A row with no
 * attachment is a real and useful state, so nothing here demands a file.</p>
 *
 * <p><b>Superseding, which says what to use next and not what should have been used before.</b>
 * Publishing DSR 2026 must not touch a single tender priced under DSR 2023. So superseding
 * moves the status and nothing else, and the link from a tender to its edition is stored
 * against that tender rather than resolved at read time.</p>
 *
 * <p><b>Refusing to withdraw an edition somebody is still billing under.</b> A document that
 * vanished from under a live agreement would leave a bill unable to say what priced it.</p>
 */
@Service
@Transactional
public class ReferenceDocumentService {

    private static final String ENTITY_TYPE = "REFERENCE_DOCUMENT";

    private final ReferenceDocumentRepository documents;
    private final AgreementDocumentRepository agreementDocuments;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public ReferenceDocumentService(ReferenceDocumentRepository documents,
                                    AgreementDocumentRepository agreementDocuments,
                                    CurrentUserProvider currentUser, AuditService audit) {
        this.documents = documents;
        this.agreementDocuments = agreementDocuments;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @PreAuthorize("hasAuthority('billing:read')")
    @Transactional(readOnly = true)
    public List<DocumentResponse> list(ReferenceDocument.Kind kind) {
        return documents.findForOrg(currentUser.currentOrgId(), kind == null, kind).stream()
                .map(ReferenceDocumentService::toResponse)
                .toList();
    }

    @PreAuthorize("hasAuthority('billing:read')")
    @Transactional(readOnly = true)
    public DocumentResponse get(UUID id) {
        return toResponse(require(id));
    }

    /**
     * @throws BusinessException 409 when the code is taken. An edition code is how a person
     *         says which document they mean, so two rows answering to DSR-2023 would make every
     *         later question ambiguous.
     */
    @PreAuthorize("hasAuthority('dsr:manage')")
    public DocumentResponse create(DocumentRequest request) {
        UUID orgId = currentUser.currentOrgId();
        String code = request.code().trim();
        documents.findByOrgIdAndCodeAndDeletedAtIsNull(orgId, code).ifPresent(existing -> {
            throw BusinessException.conflict("vault.code-taken",
                    "There is already an edition called " + code + " — "
                            + existing.getTitle() + ". Open that one rather than adding a second "
                            + "row for the same document.");
        });

        ReferenceDocument document =
                new ReferenceDocument(orgId, request.kind(), code, request.title().trim());
        apply(document, request);
        documents.save(document);

        audit.record(ENTITY_TYPE, document.getId(), "CREATE", null,
                Map.of("code", code, "kind", request.kind().name(),
                        "editionYear", String.valueOf(request.editionYear())), null);
        return toResponse(document);
    }

    @PreAuthorize("hasAuthority('dsr:manage')")
    public DocumentResponse update(UUID id, DocumentRequest request) {
        ReferenceDocument document = require(id);
        apply(document, request);
        audit.record(ENTITY_TYPE, id, "UPDATE", null,
                Map.of("code", document.getCode()), null);
        return toResponse(document);
    }

    /**
     * Marks an edition replaced by a newer one.
     *
     * <p>Deliberately touches no agreement. A tender priced under the old edition stays priced
     * under it — that is the whole reason the link is stored rather than looked up.</p>
     */
    @PreAuthorize("hasAuthority('dsr:manage')")
    public DocumentResponse supersede(UUID id, UUID replacementId) {
        ReferenceDocument document = require(id);
        if (id.equals(replacementId)) {
            throw new BusinessException("vault.self-supersede",
                    "An edition cannot replace itself.");
        }
        ReferenceDocument replacement = require(replacementId);
        if (replacement.getKind() != document.getKind()) {
            throw new BusinessException("vault.kind-mismatch",
                    "A " + replacement.getKind() + " does not replace a " + document.getKind()
                            + ". They are different kinds of authority.");
        }
        replacement.setSupersedesId(id);
        document.supersede();

        audit.record(ENTITY_TYPE, id, "SUPERSEDE", null,
                Map.of("code", document.getCode(), "replacedBy", replacement.getCode()), null);
        return toResponse(document);
    }

    /**
     * @throws BusinessException 422 when a tender is still priced under it. A document that
     *         disappeared from under a live agreement would leave its bills unable to say what
     *         priced them.
     */
    @PreAuthorize("hasAuthority('dsr:manage')")
    public void withdraw(UUID id) {
        ReferenceDocument document = require(id);
        long citing = agreementDocuments.countByDocumentId(id);
        if (citing > 0) {
            throw new BusinessException("vault.document-in-use",
                    document.getCode() + " is what " + citing + " tender(s) were priced under, "
                            + "so it cannot be withdrawn. Supersede it instead — that says what "
                            + "to use next without disturbing what was already billed.");
        }
        document.withdraw();
        document.markDeleted(Instant.now());
        audit.record(ENTITY_TYPE, id, "WITHDRAW", null, Map.of("code", document.getCode()), null);
    }

    /** Attaching the file to an edition already registered — the usual order of events. */
    @PreAuthorize("hasAuthority('dsr:manage')")
    public DocumentResponse attach(UUID id, UUID attachmentId) {
        ReferenceDocument document = require(id);
        document.setAttachmentId(attachmentId);
        audit.record(ENTITY_TYPE, id, "ATTACH", null,
                Map.of("code", document.getCode(),
                        "attachmentId", String.valueOf(attachmentId)), null);
        return toResponse(document);
    }

    private void apply(ReferenceDocument document, DocumentRequest request) {
        if (request.kind() == ReferenceDocument.Kind.COST_INDEX && request.indexPercent() == null) {
            throw new BusinessException("vault.cost-index-needs-percent",
                    "A cost index circular exists to state a percentage. Without one it cannot "
                            + "do the only thing it is for.");
        }
        if (request.kind() != ReferenceDocument.Kind.COST_INDEX && request.indexPercent() != null) {
            throw new BusinessException("vault.percent-not-applicable",
                    "Only a cost index carries a single percentage. A schedule of rates has "
                            + "thousands of rates and no one number.");
        }
        document.setKind(request.kind());
        document.setTitle(request.title().trim());
        document.setEditionYear(request.editionYear());
        document.setStation(request.station());
        document.setIndexPercent(request.indexPercent());
        document.setEffectiveFrom(request.effectiveFrom());
        document.setEffectiveTo(request.effectiveTo());
        document.setAttachmentId(request.attachmentId());
        document.setNotes(request.notes());
    }

    private ReferenceDocument require(UUID id) {
        return documents.findByIdAndOrgIdAndDeletedAtIsNull(id, currentUser.currentOrgId())
                .orElseThrow(() -> BusinessException.notFound("Reference document", id));
    }

    static DocumentResponse toResponse(ReferenceDocument d) {
        return new DocumentResponse(d.getId(), d.getKind(), d.getCode(), d.getTitle(),
                d.getEditionYear(), d.getStation(), d.getIndexPercent(), d.getEffectiveFrom(),
                d.getEffectiveTo(), d.getAttachmentId(), d.getSupersedesId(), d.getStatus(),
                d.getNotes(), d.getVersion());
    }
}

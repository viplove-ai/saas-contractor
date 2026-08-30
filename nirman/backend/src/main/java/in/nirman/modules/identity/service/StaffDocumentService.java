package in.nirman.modules.identity.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.attachment.service.AttachmentLookup;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.identity.api.dto.StaffDtos.AddStaffDocumentRequest;
import in.nirman.modules.identity.api.dto.StaffDtos.StaffDocumentResponse;
import in.nirman.modules.identity.domain.StaffDocument;
import in.nirman.modules.identity.domain.User;
import in.nirman.modules.identity.repository.StaffDocumentRepository;
import in.nirman.modules.identity.repository.UserRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The papers behind a staff record: the Aadhaar card the last four digits were read off, the
 * cheque the account number was copied from, the letter somebody signed.
 *
 * <p>Its own service rather than four more methods on {@link StaffRecordService}, which is
 * about figures somebody typed. This is about files, and the two have different failure
 * modes: a figure is corrected in place and a file is replaced, and the file's whole life —
 * uploaded, claimed, thrown away — happens outside the record's version.</p>
 *
 * <p><b>It writes nothing about the member.</b> A scan of an Aadhaar card does not fill in
 * the last four digits and a passbook does not fill in the account number: reading a figure
 * off a photograph is the office's act, and a screen that did it silently would put a number
 * on the payroll that nobody had looked at.</p>
 *
 * <p><b>No new permission.</b> {@code staff:read} and {@code staff:write} are already these
 * two questions — holding somebody's bank account number and holding the picture of the
 * passbook it was read off are one act of custody, and an organisation granting one without
 * the other would be deciding something it has no way to think about.</p>
 */
@Service
@Transactional
public class StaffDocumentService {

    /** What the uploads are filed under in object storage, and what claims them afterwards. */
    private static final String ENTITY = "STAFF_DOCUMENT";

    private final StaffDocumentRepository documents;
    private final UserRepository users;
    private final AttachmentLookup attachments;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public StaffDocumentService(StaffDocumentRepository documents, UserRepository users,
                                AttachmentLookup attachments, CurrentUserProvider currentUser,
                                AuditService audit) {
        this.documents = documents;
        this.users = users;
        this.attachments = attachments;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('staff:read')")
    public List<StaffDocumentResponse> list(UUID userId) {
        requireMember(userId);
        return documents.findByOrgIdAndUserIdOrderByCreatedAtDesc(orgId(), userId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Puts an uploaded file on the record.
     *
     * <p>The file is claimed on the way through, which is what stops the man who uploaded it
     * discarding it afterwards as a stray draft — an unclaimed upload is exactly that. The
     * claim is also the duplicate guard the unique constraint spells out: one file, one row.
     * The same document uploaded twice is two files and two rows, which is a person deciding
     * to do it twice rather than the register disagreeing with itself.</p>
     */
    @PreAuthorize("hasAuthority('staff:write')")
    public StaffDocumentResponse add(UUID userId, AddStaffDocumentRequest request) {
        User member = requireMember(userId);
        if (documents.existsByAttachmentId(request.attachmentId())) {
            throw BusinessException.conflict("staff.document-exists",
                    "That file is already on this record.");
        }
        AttachmentLookup.FileInfo file = attachments.require(request.attachmentId());

        StaffDocument document = new StaffDocument(orgId(), userId, request.attachmentId(),
                request.docType(), blankToNull(request.note()));
        documents.save(document);
        attachments.claimFor(request.attachmentId(), document.getId());

        audit.record(ENTITY, document.getId(), "CREATE", null,
                Map.of("userId", userId.toString(), "member", member.getFullName(),
                        "docType", request.docType().name(), "fileName", file.fileName()), null);
        return toResponse(document, file);
    }

    /**
     * Takes a paper off the record, and the file with it.
     *
     * <p>Really deleted, not voided with a reason — the rule that keeps an approved bill on
     * the books does not reach here. A document is not a figure anything was computed from:
     * what was read off it was typed into the record and stays there. The ordinary reason to
     * remove one is that it is the wrong man's Aadhaar card, a thumb over the lens, or a scan
     * of a card that has been reissued, and keeping a photograph of somebody's identity
     * document because the register cannot bear to lose a row is the worse of the two
     * failures. So the file is discarded with the row, which stops any further signed link
     * being minted for it.</p>
     */
    @PreAuthorize("hasAuthority('staff:write')")
    public void remove(UUID userId, UUID documentId) {
        StaffDocument document = documents.findByIdAndOrgId(documentId, orgId())
                .orElseThrow(() -> BusinessException.notFound("Staff document", documentId));
        if (!document.getUserId().equals(userId)) {
            throw BusinessException.notFound("Staff document", documentId);
        }
        documents.delete(document);
        attachments.discardFor(document.getAttachmentId(), document.getId());
        audit.record(ENTITY, documentId, "DELETE", null,
                Map.of("userId", userId.toString(), "docType", document.getDocType().name()),
                "removed from the staff record");
    }

    private StaffDocumentResponse toResponse(StaffDocument document) {
        /*
          A row whose file has gone is answered rather than thrown. It should not happen —
          claiming is what stops an upload being discarded, and removal takes the two away
          together — but a register that will not open because one object is missing is worse
          than one that says which paper it cannot find.
        */
        try {
            return toResponse(document, attachments.require(document.getAttachmentId()));
        } catch (BusinessException missing) {
            return toResponse(document, null);
        }
    }

    private StaffDocumentResponse toResponse(StaffDocument document,
                                             AttachmentLookup.FileInfo file) {
        return new StaffDocumentResponse(document.getId(), document.getUserId(),
                document.getAttachmentId(), document.getDocType(), document.getNote(),
                file == null ? null : file.fileName(),
                file == null ? null : file.contentType(),
                file != null && file.image(),
                document.getCreatedAt(), document.getCreatedBy());
    }

    /** A member of this organisation, whether or not anybody has filled in his record. */
    private User requireMember(UUID userId) {
        return users.findById(userId)
                .filter(user -> user.getOrgId().equals(orgId()))
                .orElseThrow(() -> BusinessException.notFound("User", userId));
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

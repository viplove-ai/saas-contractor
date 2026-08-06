package in.nirman.modules.attachment.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.attachment.StorageProperties;
import in.nirman.modules.attachment.api.dto.AttachmentDtos.AttachmentResponse;
import in.nirman.modules.attachment.api.dto.AttachmentDtos.SignedUrlResponse;
import in.nirman.modules.attachment.domain.Attachment;
import in.nirman.modules.attachment.repository.AttachmentRepository;
import in.nirman.modules.audit.AuditService;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Upload, signed download and draft deletion. The two security properties this class owns:
 * a signed URL is issued only after a fresh site-access check (a forwarded URL dies within
 * minutes and cannot be renewed by someone unassigned), and a stored file can only be
 * removed by its uploader while no business record has claimed it.
 */
@Service
public class AttachmentService {

    private static final DateTimeFormatter KEY_PREFIX = DateTimeFormatter.ofPattern("yyyy/MM");

    private final AttachmentRepository attachments;
    private final StorageClient storage;
    private final StorageProperties properties;
    private final SiteAccessGuard siteAccessGuard;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public AttachmentService(AttachmentRepository attachments, StorageClient storage,
                             StorageProperties properties, SiteAccessGuard siteAccessGuard,
                             CurrentUserProvider currentUser, AuditService audit) {
        this.attachments = attachments;
        this.storage = storage;
        this.properties = properties;
        this.siteAccessGuard = siteAccessGuard;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @PreAuthorize("hasAuthority('attachment:upload')")
    public AttachmentResponse upload(MultipartFile file, String ownerEntityType, UUID siteId,
                                     Attachment.Kind kind) {
        if (file.isEmpty()) {
            throw new BusinessException("attachment.empty", "The uploaded file is empty.");
        }
        if (file.getSize() > properties.maxFileSizeBytes()) {
            throw new BusinessException("attachment.too-large",
                    "The file exceeds the limit of "
                            + (properties.maxFileSizeBytes() / (1024 * 1024)) + " MB.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !properties.allowedContentTypes().contains(contentType)) {
            throw new BusinessException("attachment.type",
                    "Only these file types are accepted: "
                            + String.join(", ", properties.allowedContentTypes()));
        }
        if (siteId != null) {
            siteAccessGuard.assertCanAccess(siteId);
        }

        String safeName = sanitize(file.getOriginalFilename());
        UUID orgId = currentUser.currentOrgId();
        String objectKey = "org/%s/%s/%s/%s-%s".formatted(orgId, ownerEntityType.toLowerCase(),
                KEY_PREFIX.format(LocalDate.now()), UUID.randomUUID(), safeName);

        String checksum;
        try (InputStream in = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            DigestInputStream digesting = new DigestInputStream(in, digest);
            storage.put(objectKey, digesting, file.getSize(), contentType);
            checksum = HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new BusinessException("attachment.read", "The upload could not be read.",
                    HttpStatus.BAD_REQUEST);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }

        Attachment attachment = new Attachment(orgId, siteId, ownerEntityType, safeName,
                contentType, file.getSize(), checksum, properties.bucket(), objectKey, kind);
        attachments.save(attachment);
        audit.record("ATTACHMENT", attachment.getId(), "CREATE", null,
                Map.of("fileName", safeName, "sizeBytes", file.getSize(),
                        "ownerEntityType", ownerEntityType), null);
        return toResponse(attachment);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('attachment:download')")
    public SignedUrlResponse signedUrl(UUID id) {
        Attachment attachment = require(id);
        if (attachment.getSiteId() != null) {
            // The re-check that makes a forwarded link worthless to the unassigned.
            siteAccessGuard.assertCanAccess(attachment.getSiteId());
        }
        String url = storage.presignedGetUrl(attachment.getObjectKey(),
                properties.signedUrlMinutes());
        return new SignedUrlResponse(attachment.getId(), url,
                properties.signedUrlMinutes() * 60L, attachment.getFileName(),
                attachment.getContentType());
    }

    @Transactional
    @PreAuthorize("hasAuthority('attachment:upload')")
    public void delete(UUID id) {
        Attachment attachment = require(id);
        if (attachment.getOwnerEntityId() != null) {
            throw new BusinessException("attachment.claimed",
                    "This file is attached to a saved record and cannot be removed here.");
        }
        if (!currentUser.isAdmin()
                && !currentUser.currentUserIdOrNull().equals(attachment.getUploadedBy())) {
            throw BusinessException.forbidden("Only the uploader can remove a draft file.");
        }
        attachment.softDelete(Instant.now());
        audit.record("ATTACHMENT", id, "DELETE", null, null, "draft upload removed");
        // The object stays in the store; a cleanup job for orphans is a later phase's task.
    }

    /**
     * Binds a draft upload to the record it belongs to.
     *
     * <p>A file uploaded before its owner exists — a tender PDF read to build a project, a
     * bill photographed before the expense is entered — sits unclaimed until the save that
     * gives it a home. Claiming is what stops {@link #delete} discarding it afterwards.</p>
     *
     * @throws BusinessException if the file already belongs to something else, which would
     *                           mean two records disagreeing about who owns one file
     */
    @PreAuthorize("hasAuthority('attachment:upload')")
    public AttachmentResponse claim(UUID id, UUID ownerEntityId) {
        Attachment attachment = require(id);
        if (attachment.getOwnerEntityId() != null
                && !attachment.getOwnerEntityId().equals(ownerEntityId)) {
            throw new BusinessException("attachment.claimed",
                    "This file is already attached to another record.", HttpStatus.CONFLICT);
        }
        attachment.attachTo(ownerEntityId);
        attachments.save(attachment);
        return toResponse(attachment);
    }

    private Attachment require(UUID id) {
        return attachments.findByIdAndOrgIdAndDeletedAtIsNull(id, currentUser.currentOrgId())
                .orElseThrow(() -> BusinessException.notFound("Attachment", id));
    }

    private AttachmentResponse toResponse(Attachment a) {
        return new AttachmentResponse(a.getId(), a.getSiteId(), a.getOwnerEntityType(),
                a.getOwnerEntityId(), a.getFileName(), a.getContentType(), a.getSizeBytes(),
                a.getChecksumSha256(), a.getKind(), a.getUploadedAt(), a.getUploadedBy());
    }

    private static String sanitize(String original) {
        String name = original == null ? "file" : original;
        name = name.substring(name.lastIndexOf('/') + 1).substring(name.lastIndexOf('\\') + 1);
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return name.length() > 180 ? name.substring(name.length() - 180) : name;
    }
}

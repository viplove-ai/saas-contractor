package in.nirman.modules.attachment.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.attachment.domain.Attachment;
import in.nirman.modules.attachment.StorageProperties;
import in.nirman.modules.attachment.repository.AttachmentRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * The {@link AttachmentLookup} implementation.
 *
 * <p>No {@code @PreAuthorize} and no site guard, like every other {@code *Lookup}: the caller
 * has already passed the check that got it here — it is holding a record it may write to, on a
 * site it may reach — and the file it is about to attach was uploaded by that same caller
 * moments ago under {@code attachment:upload}. The org filter stays, because that is not a
 * permission question but the boundary of what exists.</p>
 */
@Service
@Transactional
public class AttachmentLookupService implements AttachmentLookup {

    private static final java.time.format.DateTimeFormatter KEY_PREFIX =
            java.time.format.DateTimeFormatter.ofPattern("yyyy/MM");

    private final AttachmentRepository attachments;
    private final StorageClient storage;
    private final StorageProperties properties;
    private final CurrentUserProvider currentUser;

    public AttachmentLookupService(AttachmentRepository attachments, StorageClient storage,
                                   StorageProperties properties,
                                   CurrentUserProvider currentUser) {
        this.attachments = attachments;
        this.storage = storage;
        this.properties = properties;
        this.currentUser = currentUser;
    }

    @Override
    @Transactional(readOnly = true)
    public FileInfo require(UUID attachmentId) {
        Attachment attachment = load(attachmentId);
        return new FileInfo(attachment.getId(), attachment.getFileName(),
                attachment.getContentType(), attachment.getSiteId(),
                attachment.getContentType().startsWith("image/"));
    }

    @Override
    public void claimFor(UUID attachmentId, UUID ownerEntityId) {
        Attachment attachment = load(attachmentId);
        if (attachment.getOwnerEntityId() != null
                && !attachment.getOwnerEntityId().equals(ownerEntityId)) {
            throw BusinessException.conflict("attachment.claimed",
                    "That file is already attached to another record.");
        }
        attachment.attachTo(ownerEntityId);
        attachments.save(attachment);
    }

    @Override
    public FileInfo store(byte[] content, String fileName, String contentType,
                          String ownerEntityType, UUID ownerEntityId) {
        UUID orgId = currentUser.currentOrgId();
        String safeName = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        String objectKey = "org/%s/%s/%s/%s-%s".formatted(orgId, ownerEntityType.toLowerCase(),
                KEY_PREFIX.format(java.time.LocalDate.now()), UUID.randomUUID(), safeName);

        String checksum;
        try {
            checksum = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        storage.put(objectKey, new java.io.ByteArrayInputStream(content), content.length,
                contentType);

        // No site. A generated document belongs to a record and not to a place, and giving it
        // one would put it behind a site guard that the record it belongs to does not have.
        Attachment attachment = new Attachment(orgId, null, ownerEntityType, safeName,
                contentType, content.length, checksum, properties.bucket(), objectKey,
                Attachment.Kind.DOCUMENT);
        attachment.attachTo(ownerEntityId);
        attachments.save(attachment);
        return new FileInfo(attachment.getId(), safeName, contentType, null, false);
    }

    @Override
    public void discardFor(UUID attachmentId, UUID ownerEntityId) {
        Attachment attachment = load(attachmentId);
        if (attachment.getOwnerEntityId() != null
                && !attachment.getOwnerEntityId().equals(ownerEntityId)) {
            throw BusinessException.conflict("attachment.claimed",
                    "That file belongs to another record.");
        }
        attachment.softDelete(Instant.now());
        attachments.save(attachment);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<String> dataUri(UUID attachmentId) {
        return attachments.findByIdAndOrgIdAndDeletedAtIsNull(attachmentId,
                        currentUser.currentOrgId())
                .map(attachment -> {
                    byte[] bytes = storage.get(attachment.getObjectKey());
                    return bytes == null ? null : "data:" + attachment.getContentType()
                            + ";base64," + java.util.Base64.getEncoder().encodeToString(bytes);
                });
    }

    private Attachment load(UUID attachmentId) {
        return attachments.findByIdAndOrgIdAndDeletedAtIsNull(attachmentId,
                        currentUser.currentOrgId())
                .orElseThrow(() -> BusinessException.notFound("Attachment", attachmentId));
    }
}

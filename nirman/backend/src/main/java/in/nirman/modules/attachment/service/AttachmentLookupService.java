package in.nirman.modules.attachment.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.attachment.domain.Attachment;
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

    private final AttachmentRepository attachments;
    private final CurrentUserProvider currentUser;

    public AttachmentLookupService(AttachmentRepository attachments,
                                   CurrentUserProvider currentUser) {
        this.attachments = attachments;
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

    private Attachment load(UUID attachmentId) {
        return attachments.findByIdAndOrgIdAndDeletedAtIsNull(attachmentId,
                        currentUser.currentOrgId())
                .orElseThrow(() -> BusinessException.notFound("Attachment", attachmentId));
    }
}

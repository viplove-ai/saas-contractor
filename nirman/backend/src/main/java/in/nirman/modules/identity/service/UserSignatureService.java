package in.nirman.modules.identity.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.attachment.service.AttachmentLookup;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.identity.domain.User;
import in.nirman.modules.identity.repository.UserRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * The member's own signature: uploaded by him, replaced by him, and by nobody else.
 *
 * <p><b>No permission.</b> Every other write in this module is behind a code somebody grants.
 * This one is behind being signed in, because the thing being written is the caller's own
 * hand — an administrator who could upload a signature for a supervisor could put that
 * supervisor's name on a report the supervisor never saw, which is precisely what a signature
 * on a document is supposed to rule out. So the service never takes a user id: it writes the
 * caller's row and only that.</p>
 *
 * <p><b>A picture, and only a picture.</b> The documents draw it into an {@code <img>}, so a
 * PDF is refused here rather than at the moment a letter fails to render. The shape is settled
 * on the device before upload — the screen crops every signature to one fixed ratio — so the
 * server stores no size and checks none: a size stored beside a picture is a second statement
 * of what the picture already is.</p>
 *
 * <p><b>Replacing discards.</b> The old file is thrown away with the old link, exactly as a
 * removed staff paper is (V51): a signature that has been superseded is not evidence of
 * anything, and keeping a picture of somebody's hand that nothing points at is the worse of
 * the two failures. Documents already rendered keep the picture they were rendered with — the
 * offer letter is filed as a PDF, and the daily report is drawn afresh with whatever the
 * signer holds on the day it is printed.</p>
 */
@Service
@Transactional
public class UserSignatureService {

    /** What the upload is filed under in object storage, and what claims it afterwards. */
    public static final String ENTITY = "USER_SIGNATURE";

    private final UserRepository users;
    private final AttachmentLookup attachments;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public UserSignatureService(UserRepository users, AttachmentLookup attachments,
                                CurrentUserProvider currentUser, AuditService audit) {
        this.users = users;
        this.attachments = attachments;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    /** Puts an uploaded picture on the caller's account as his signature. */
    public User set(UUID attachmentId) {
        User user = self();
        AttachmentLookup.FileInfo file = attachments.require(attachmentId);
        if (!file.image()) {
            throw new BusinessException("signature.not-a-picture",
                    "A signature has to be a picture. Photograph it on white paper, or upload "
                            + "a PNG or JPEG of it.");
        }
        UUID previous = user.getSignatureAttachmentId();
        if (attachmentId.equals(previous)) {
            return user;
        }
        attachments.claimFor(attachmentId, user.getId());
        user.setSignatureAttachmentId(attachmentId);
        users.save(user);
        if (previous != null) {
            attachments.discardFor(previous, user.getId());
        }
        audit.record("USER", user.getId(), previous == null ? "SIGNATURE_SET" : "SIGNATURE_REPLACED",
                previous == null ? null : Map.of("attachmentId", previous.toString()),
                Map.of("attachmentId", attachmentId.toString(), "fileName", file.fileName()),
                null);
        return user;
    }

    /** Takes the caller's signature off his account, and the file with it. */
    public User clear() {
        User user = self();
        UUID previous = user.getSignatureAttachmentId();
        if (previous == null) {
            return user;
        }
        user.setSignatureAttachmentId(null);
        users.save(user);
        attachments.discardFor(previous, user.getId());
        audit.record("USER", user.getId(), "SIGNATURE_REMOVED",
                Map.of("attachmentId", previous.toString()), null, null);
        return user;
    }

    private User self() {
        UUID userId = currentUser.currentUserIdOrNull();
        if (userId == null) {
            throw BusinessException.forbidden("Sign in to change your signature.");
        }
        return users.findByIdAndOrgId(userId, currentUser.currentOrgId())
                .orElseThrow(() -> BusinessException.notFound("User", userId));
    }
}

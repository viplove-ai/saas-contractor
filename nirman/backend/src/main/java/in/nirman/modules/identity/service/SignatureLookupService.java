package in.nirman.modules.identity.service;

import in.nirman.modules.attachment.service.AttachmentLookup;
import in.nirman.modules.identity.repository.UserRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class SignatureLookupService implements SignatureLookup {

    private final UserRepository users;
    private final AttachmentLookup attachments;
    private final CurrentUserProvider currentUser;

    public SignatureLookupService(UserRepository users, AttachmentLookup attachments,
                                  CurrentUserProvider currentUser) {
        this.users = users;
        this.attachments = attachments;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<String> signatureDataUri(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return users.findByIdAndOrgId(userId, currentUser.currentOrgId())
                .map(user -> user.getSignatureAttachmentId())
                .flatMap(attachments::dataUri);
    }
}

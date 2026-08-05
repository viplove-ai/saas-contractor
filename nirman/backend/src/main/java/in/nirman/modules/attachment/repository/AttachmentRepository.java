package in.nirman.modules.attachment.repository;

import in.nirman.modules.attachment.domain.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    Optional<Attachment> findByIdAndOrgIdAndDeletedAtIsNull(UUID id, UUID orgId);
}

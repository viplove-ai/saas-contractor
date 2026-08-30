package in.nirman.modules.identity.repository;

import in.nirman.modules.identity.domain.StaffDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffDocumentRepository extends JpaRepository<StaffDocument, UUID> {

    /** One member's papers, newest first — the whole of the read. */
    List<StaffDocument> findByOrgIdAndUserIdOrderByCreatedAtDesc(UUID orgId, UUID userId);

    Optional<StaffDocument> findByIdAndOrgId(UUID id, UUID orgId);

    boolean existsByAttachmentId(UUID attachmentId);
}

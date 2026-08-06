package in.nirman.modules.tender.repository;

import in.nirman.modules.tender.domain.NitDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NitDocumentRepository extends JpaRepository<NitDocument, UUID> {

    Optional<NitDocument> findByProjectIdAndOrgIdAndDeletedAtIsNull(UUID projectId, UUID orgId);

    boolean existsByProjectIdAndDeletedAtIsNull(UUID projectId);
}

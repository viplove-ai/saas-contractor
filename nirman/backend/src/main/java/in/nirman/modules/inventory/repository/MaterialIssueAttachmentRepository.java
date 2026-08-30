package in.nirman.modules.inventory.repository;

import in.nirman.modules.inventory.domain.MaterialIssueAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MaterialIssueAttachmentRepository
        extends JpaRepository<MaterialIssueAttachment, UUID> {

    List<MaterialIssueAttachment> findByMaterialIssueId(UUID materialIssueId);
}

package in.nirman.modules.dpr.repository;

import in.nirman.modules.dpr.domain.DprPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DprPhotoRepository extends JpaRepository<DprPhoto, UUID> {

    List<DprPhoto> findByDprIdOrderBySortOrder(UUID dprId);

    boolean existsByDprIdAndAttachmentId(UUID dprId, UUID attachmentId);
}

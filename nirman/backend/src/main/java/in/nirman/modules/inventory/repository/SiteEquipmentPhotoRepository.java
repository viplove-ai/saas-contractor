package in.nirman.modules.inventory.repository;

import in.nirman.modules.inventory.domain.SiteEquipmentPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SiteEquipmentPhotoRepository extends JpaRepository<SiteEquipmentPhoto, UUID> {

    /** Oldest first: the first picture taken is of the machine, the later ones of what went wrong. */
    List<SiteEquipmentPhoto> findByEquipmentIdOrderByCreatedAtAsc(UUID equipmentId);

    /**
     * Every picture for a page of machines, in one query.
     *
     * <p>The register draws forty rows and each may carry several; asking per row is forty
     * queries to fill one screen.</p>
     */
    List<SiteEquipmentPhoto> findByEquipmentIdInOrderByCreatedAtAsc(Collection<UUID> equipmentIds);

    Optional<SiteEquipmentPhoto> findByIdAndOrgId(UUID id, UUID orgId);

    boolean existsByAttachmentId(UUID attachmentId);

    long countByEquipmentId(UUID equipmentId);
}

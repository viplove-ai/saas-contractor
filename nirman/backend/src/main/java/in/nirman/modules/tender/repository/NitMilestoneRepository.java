package in.nirman.modules.tender.repository;

import in.nirman.modules.tender.domain.NitMilestone;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NitMilestoneRepository extends JpaRepository<NitMilestone, UUID> {

    /** In the order the notice printed them, which is the order they fall due. */
    List<NitMilestone> findByNitDocumentIdOrderBySequenceNoAsc(UUID nitDocumentId);
}

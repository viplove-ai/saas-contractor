package in.nirman.modules.tender.repository;

import in.nirman.modules.tender.domain.NitInterimMinimum;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NitInterimMinimumRepository extends JpaRepository<NitInterimMinimum, UUID> {

    List<NitInterimMinimum> findByNitDocumentId(UUID nitDocumentId);
}

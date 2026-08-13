package in.nirman.modules.planning.repository;

import in.nirman.modules.planning.domain.MaterialLeadTime;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MaterialLeadTimeRepository extends JpaRepository<MaterialLeadTime, UUID> {

    List<MaterialLeadTime> findByOrgId(UUID orgId);

    Optional<MaterialLeadTime> findByIdAndOrgId(UUID id, UUID orgId);
}

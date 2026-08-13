package in.nirman.modules.planning.repository;

import in.nirman.modules.planning.domain.WorkTypeProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkTypeProfileRepository extends JpaRepository<WorkTypeProfile, UUID> {

    List<WorkTypeProfile> findByOrgIdAndActiveTrueOrderByCodeAsc(UUID orgId);

    Optional<WorkTypeProfile> findByOrgIdAndCode(UUID orgId, String code);
}

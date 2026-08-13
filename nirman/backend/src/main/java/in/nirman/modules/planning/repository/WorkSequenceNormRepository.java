package in.nirman.modules.planning.repository;

import in.nirman.modules.planning.domain.WorkSequenceNorm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkSequenceNormRepository extends JpaRepository<WorkSequenceNorm, UUID> {

    List<WorkSequenceNorm> findByOrgIdOrderBySequenceRankAsc(UUID orgId);

    Optional<WorkSequenceNorm> findByIdAndOrgId(UUID id, UUID orgId);
}

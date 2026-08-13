package in.nirman.modules.planning.repository;

import in.nirman.modules.planning.domain.ExecutionPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExecutionPlanRepository extends JpaRepository<ExecutionPlan, UUID> {

    Optional<ExecutionPlan> findByIdAndOrgIdAndDeletedAtIsNull(UUID id, UUID orgId);

    /** The one live baseline a project runs under. */
    Optional<ExecutionPlan> findByProjectIdAndOrgIdAndBaselinedAtIsNotNullAndSupersededAtIsNullAndDeletedAtIsNull(
            UUID projectId, UUID orgId);

    List<ExecutionPlan> findByProjectIdAndOrgIdAndDeletedAtIsNullOrderByRevisionDesc(
            UUID projectId, UUID orgId);
}

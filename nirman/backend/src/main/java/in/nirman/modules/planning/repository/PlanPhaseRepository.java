package in.nirman.modules.planning.repository;

import in.nirman.modules.planning.domain.PlanPhase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlanPhaseRepository extends JpaRepository<PlanPhase, UUID> {

    List<PlanPhase> findByPlanId(UUID planId);

    void deleteByPlanId(UUID planId);
}

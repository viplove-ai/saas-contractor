package in.nirman.modules.planning.repository;

import in.nirman.modules.planning.domain.PlanMaterialDemand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlanMaterialDemandRepository extends JpaRepository<PlanMaterialDemand, UUID> {

    List<PlanMaterialDemand> findByPlanId(UUID planId);

    void deleteByPlanId(UUID planId);
}

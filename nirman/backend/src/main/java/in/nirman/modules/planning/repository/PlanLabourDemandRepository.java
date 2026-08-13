package in.nirman.modules.planning.repository;

import in.nirman.modules.planning.domain.PlanLabourDemand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlanLabourDemandRepository extends JpaRepository<PlanLabourDemand, UUID> {

    List<PlanLabourDemand> findByPlanId(UUID planId);

    void deleteByPlanId(UUID planId);
}

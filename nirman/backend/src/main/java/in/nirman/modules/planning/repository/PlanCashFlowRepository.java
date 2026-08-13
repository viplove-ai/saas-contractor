package in.nirman.modules.planning.repository;

import in.nirman.modules.planning.domain.PlanCashFlow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlanCashFlowRepository extends JpaRepository<PlanCashFlow, UUID> {

    List<PlanCashFlow> findByPlanId(UUID planId);

    void deleteByPlanId(UUID planId);
}

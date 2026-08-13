package in.nirman.modules.planning.repository;

import in.nirman.modules.planning.domain.PlanWorkPackage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlanWorkPackageRepository extends JpaRepository<PlanWorkPackage, UUID> {

    List<PlanWorkPackage> findByPlanId(UUID planId);

    void deleteByPlanId(UUID planId);
}

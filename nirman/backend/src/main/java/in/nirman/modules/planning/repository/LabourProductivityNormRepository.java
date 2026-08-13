package in.nirman.modules.planning.repository;

import in.nirman.modules.planning.domain.LabourProductivityNorm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LabourProductivityNormRepository
        extends JpaRepository<LabourProductivityNorm, UUID> {

    List<LabourProductivityNorm> findByOrgIdOrderByWorkCategoryAscWorkSubTypeAsc(UUID orgId);

    /** Every trade this work needs, which together are one gang. */
    List<LabourProductivityNorm> findByOrgIdAndWorkCategoryAndActiveTrue(UUID orgId,
                                                                        String workCategory);

    Optional<LabourProductivityNorm> findByIdAndOrgId(UUID id, UUID orgId);
}

package in.nirman.modules.inventory.repository;

import in.nirman.modules.inventory.domain.MaterialConsumptionNorm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MaterialConsumptionNormRepository extends JpaRepository<MaterialConsumptionNorm, UUID> {

    List<MaterialConsumptionNorm> findByOrgIdAndActiveTrueOrderByWorkCategoryAscWorkSubTypeAsc(UUID orgId);

    /**
     * The coefficient for a piece of work. A sub-type is more specific than the bare
     * category — M25 concrete takes more cement than concrete in general — so a caller that
     * knows the grade looks for it first and falls back to the category norm.
     */
    @Query("""
            SELECT n FROM MaterialConsumptionNorm n
            WHERE n.orgId = :orgId
              AND n.workCategory = :workCategory
              AND ((:workSubType IS NULL AND n.workSubType IS NULL) OR n.workSubType = :workSubType)
              AND n.materialId = :materialId
              AND n.active = true
            """)
    Optional<MaterialConsumptionNorm> findNorm(@Param("orgId") UUID orgId,
                                               @Param("workCategory") String workCategory,
                                               @Param("workSubType") String workSubType,
                                               @Param("materialId") UUID materialId);

    List<MaterialConsumptionNorm> findByOrgIdAndWorkCategoryAndActiveTrue(UUID orgId, String workCategory);
}

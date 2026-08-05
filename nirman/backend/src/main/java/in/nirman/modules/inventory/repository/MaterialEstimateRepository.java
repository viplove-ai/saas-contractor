package in.nirman.modules.inventory.repository;

import in.nirman.modules.inventory.domain.MaterialEstimate;
import in.nirman.modules.inventory.domain.MaterialEstimate.Level;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MaterialEstimateRepository extends JpaRepository<MaterialEstimate, UUID> {

    Optional<MaterialEstimate> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * The live revision for one scope and level, if there is one. Matches
     * {@code uq_material_estimate_live}, including its treatment of a null BOQ item as a
     * scope in its own right — the project-wide estimate.
     */
    @Query("""
            SELECT e FROM MaterialEstimate e
            WHERE e.orgId = :orgId
              AND e.projectId = :projectId
              AND ((:boqItemId IS NULL AND e.boqItemId IS NULL) OR e.boqItemId = :boqItemId)
              AND e.materialId = :materialId
              AND e.estimateLevel = :level
              AND e.supersededAt IS NULL
            """)
    Optional<MaterialEstimate> findLive(@Param("orgId") UUID orgId,
                                        @Param("projectId") UUID projectId,
                                        @Param("boqItemId") UUID boqItemId,
                                        @Param("materialId") UUID materialId,
                                        @Param("level") Level level);

    /** Every live estimate for a project, optionally narrowed to one material or level. */
    @Query("""
            SELECT e FROM MaterialEstimate e
            WHERE e.orgId = :orgId
              AND e.projectId = :projectId
              AND (:materialId IS NULL OR e.materialId = :materialId)
              AND (:level IS NULL OR e.estimateLevel = :level)
              AND e.supersededAt IS NULL
            ORDER BY e.materialId, e.estimateLevel
            """)
    List<MaterialEstimate> findLiveForProject(@Param("orgId") UUID orgId,
                                              @Param("projectId") UUID projectId,
                                              @Param("materialId") UUID materialId,
                                              @Param("level") Level level);

    /** Including superseded revisions, so a figure quoted last month stays reproducible. */
    @Query("""
            SELECT e FROM MaterialEstimate e
            WHERE e.orgId = :orgId AND e.projectId = :projectId AND e.materialId = :materialId
            ORDER BY e.revision DESC
            """)
    List<MaterialEstimate> findHistory(@Param("orgId") UUID orgId,
                                       @Param("projectId") UUID projectId,
                                       @Param("materialId") UUID materialId);
}

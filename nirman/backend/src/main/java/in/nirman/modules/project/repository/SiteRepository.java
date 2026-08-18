package in.nirman.modules.project.repository;

import in.nirman.modules.project.domain.Site;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SiteRepository extends JpaRepository<Site, UUID> {

    Optional<Site> findByIdAndOrgIdAndDeletedAtIsNull(UUID id, UUID orgId);

    /** Sees a deleted site, for restoring it. See {@code ProjectRepository#findByIdAndOrgId}. */
    Optional<Site> findByIdAndOrgId(UUID id, UUID orgId);

    /** Blind to {@code deleted_at} on purpose — a deleted site keeps its code reserved. */
    boolean existsByOrgIdAndCode(UUID orgId, String code);

    Optional<Site> findByOrgIdAndCode(UUID orgId, String code);

    List<Site> findByOrgIdAndDeletedAtIsNullOrderByCode(UUID orgId);

    List<Site> findByOrgIdAndProjectIdAndDeletedAtIsNullOrderByCode(UUID orgId, UUID projectId);

    List<Site> findByIdInAndDeletedAtIsNullOrderByCode(Collection<UUID> ids);

    // ------------------------------------------------------------- the deleted side

    List<Site> findByOrgIdAndDeletedAtIsNotNullOrderByCode(UUID orgId);

    List<Site> findByOrgIdAndProjectIdAndDeletedAtIsNotNullOrderByCode(UUID orgId, UUID projectId);

    /**
     * Every site of a project whatever its state, which is what deleting and restoring a
     * project both need: the delete has to cascade to live sites, and the restore has to
     * bring back the ones that went down with it.
     */
    List<Site> findByOrgIdAndProjectIdOrderByCode(UUID orgId, UUID projectId);

    @Query("SELECT DISTINCT s.projectId FROM Site s WHERE s.id IN :siteIds AND s.deletedAt IS NULL")
    List<UUID> findProjectIds(@Param("siteIds") Collection<UUID> siteIds);

    /**
     * The ids, of those given, that let their labour to suppliers. A projection rather than
     * the sites themselves because the only caller wants a set to test membership against,
     * once, for a register that may span every site in the organisation.
     */
    @Query("SELECT s.id FROM Site s WHERE s.orgId = :orgId AND s.id IN :siteIds "
            + "AND s.usesOutsourcedLabour = true AND s.deletedAt IS NULL")
    List<UUID> findOutsourcedLabourSiteIds(@Param("orgId") UUID orgId,
                                           @Param("siteIds") Collection<UUID> siteIds);

    long countByProjectIdAndDeletedAtIsNull(UUID projectId);
}

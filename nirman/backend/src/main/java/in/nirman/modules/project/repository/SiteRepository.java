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

    boolean existsByOrgIdAndCode(UUID orgId, String code);

    List<Site> findByOrgIdAndDeletedAtIsNullOrderByCode(UUID orgId);

    List<Site> findByOrgIdAndProjectIdAndDeletedAtIsNullOrderByCode(UUID orgId, UUID projectId);

    List<Site> findByIdInAndDeletedAtIsNullOrderByCode(Collection<UUID> ids);

    @Query("SELECT DISTINCT s.projectId FROM Site s WHERE s.id IN :siteIds AND s.deletedAt IS NULL")
    List<UUID> findProjectIds(@Param("siteIds") Collection<UUID> siteIds);

    long countByProjectIdAndDeletedAtIsNull(UUID projectId);
}

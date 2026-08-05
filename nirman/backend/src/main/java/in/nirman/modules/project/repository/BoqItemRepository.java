package in.nirman.modules.project.repository;

import in.nirman.modules.project.domain.BoqItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BoqItemRepository extends JpaRepository<BoqItem, UUID> {

    Optional<BoqItem> findByIdAndOrgIdAndDeletedAtIsNull(UUID id, UUID orgId);

    boolean existsByProjectIdAndItemNumber(UUID projectId, String itemNumber);

    List<BoqItem> findByIdInAndOrgIdAndDeletedAtIsNull(Collection<UUID> ids, UUID orgId);

    @Query("""
            SELECT b FROM BoqItem b
            WHERE b.orgId = :orgId AND b.deletedAt IS NULL
              AND (:projectId IS NULL OR b.projectId = :projectId)
              AND (:siteId IS NULL OR b.siteId = :siteId OR b.siteId IS NULL)
              AND (:category IS NULL OR b.category = :category)
            ORDER BY b.sortOrder ASC, b.itemNumber ASC
            """)
    List<BoqItem> search(@Param("orgId") UUID orgId,
                         @Param("projectId") UUID projectId,
                         @Param("siteId") UUID siteId,
                         @Param("category") String category);
}

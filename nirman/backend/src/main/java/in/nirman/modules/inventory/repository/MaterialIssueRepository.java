package in.nirman.modules.inventory.repository;

import in.nirman.modules.inventory.domain.DocumentWorkflow;
import in.nirman.modules.inventory.domain.MaterialIssue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface MaterialIssueRepository extends JpaRepository<MaterialIssue, UUID> {

    Optional<MaterialIssue> findByIdAndOrgId(UUID id, UUID orgId);

    @Query("""
            SELECT i FROM MaterialIssue i
            WHERE i.orgId = :orgId
              AND (:siteId IS NULL OR i.siteId = :siteId)
              AND (:storeId IS NULL OR i.storeId = :storeId)
              AND (:boqItemId IS NULL OR i.boqItemId = :boqItemId)
              AND (:status IS NULL OR i.workflowStatus = :status)
              AND (:from IS NULL OR i.issueDate >= :from)
              AND (:to IS NULL OR i.issueDate <= :to)
              AND (:restricted = false OR i.siteId IN :siteIds)
            """)
    Page<MaterialIssue> search(@Param("orgId") UUID orgId,
                               @Param("siteId") UUID siteId,
                               @Param("storeId") UUID storeId,
                               @Param("boqItemId") UUID boqItemId,
                               @Param("status") DocumentWorkflow status,
                               @Param("from") LocalDate from,
                               @Param("to") LocalDate to,
                               @Param("restricted") boolean restricted,
                               @Param("siteIds") Collection<UUID> siteIds,
                               Pageable pageable);
}

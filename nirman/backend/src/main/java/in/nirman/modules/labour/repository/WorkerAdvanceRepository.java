package in.nirman.modules.labour.repository;

import in.nirman.modules.labour.domain.WorkerAdvance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface WorkerAdvanceRepository extends JpaRepository<WorkerAdvance, UUID> {

    Optional<WorkerAdvance> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * {@code siteIds} is the caller's scope, separate from the {@code siteId} filter: one is
     * what they asked for, the other is what they may have, and an unfiltered request must
     * still mean "my sites".
     */
    @Query("""
            SELECT a FROM WorkerAdvance a
            WHERE a.orgId = :orgId
              AND (:siteId IS NULL OR a.siteId = :siteId)
              AND (:workerId IS NULL OR a.workerId = :workerId)
              AND (:from IS NULL OR a.advanceDate >= :from)
              AND (:to IS NULL OR a.advanceDate <= :to)
              AND (:siteIdsRestricted = false OR a.siteId IN :siteIds)
            """)
    Page<WorkerAdvance> search(@Param("orgId") UUID orgId,
                               @Param("siteId") UUID siteId,
                               @Param("workerId") UUID workerId,
                               @Param("from") LocalDate from,
                               @Param("to") LocalDate to,
                               @Param("siteIdsRestricted") boolean siteIdsRestricted,
                               @Param("siteIds") Collection<UUID> siteIds,
                               Pageable pageable);
}

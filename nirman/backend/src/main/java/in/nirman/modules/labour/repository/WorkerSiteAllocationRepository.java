package in.nirman.modules.labour.repository;

import in.nirman.modules.labour.domain.WorkerSiteAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkerSiteAllocationRepository extends JpaRepository<WorkerSiteAllocation, UUID> {

    List<WorkerSiteAllocation> findByWorkerIdOrderByEffectiveFromDesc(UUID workerId);

    /** The schema permits at most one of these per worker ({@code uq_alloc_open}). */
    Optional<WorkerSiteAllocation> findByWorkerIdAndEffectiveToIsNull(UUID workerId);

    @Query("""
            SELECT a FROM WorkerSiteAllocation a
            WHERE a.workerId = :workerId
              AND a.effectiveFrom <= :onDate
              AND (a.effectiveTo IS NULL OR a.effectiveTo >= :onDate)
            """)
    Optional<WorkerSiteAllocation> findEffectiveOn(@Param("workerId") UUID workerId,
                                                   @Param("onDate") LocalDate onDate);

    /** Bulk form of {@link #findEffectiveOn}: at most one row per worker, by {@code uq_alloc_open}. */
    @Query("""
            SELECT a FROM WorkerSiteAllocation a
            WHERE a.workerId IN :workerIds
              AND a.effectiveFrom <= :onDate
              AND (a.effectiveTo IS NULL OR a.effectiveTo >= :onDate)
            """)
    List<WorkerSiteAllocation> findEffectiveOnFor(@Param("workerIds") Collection<UUID> workerIds,
                                                  @Param("onDate") LocalDate onDate);

    /**
     * Postings to a site that begin after a date — the men who were not yet on its roll that
     * morning but are on it now or later. The muster reaches back through these.
     */
    List<WorkerSiteAllocation> findBySiteIdAndEffectiveFromAfter(UUID siteId, LocalDate date);
}

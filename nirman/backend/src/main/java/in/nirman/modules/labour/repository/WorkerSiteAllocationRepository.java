package in.nirman.modules.labour.repository;

import in.nirman.modules.labour.domain.WorkerSiteAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
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
}

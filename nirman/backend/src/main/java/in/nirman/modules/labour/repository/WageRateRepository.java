package in.nirman.modules.labour.repository;

import in.nirman.modules.labour.domain.WageRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WageRateRepository extends JpaRepository<WageRate, UUID> {

    List<WageRate> findByWorkerIdOrderByEffectiveFromDesc(UUID workerId);

    Optional<WageRate> findByWorkerIdAndEffectiveToIsNull(UUID workerId);

    /** The rate in force on a date — what attendance freezes onto the record at verification. */
    @Query("""
            SELECT r FROM WageRate r
            WHERE r.workerId = :workerId
              AND r.effectiveFrom <= :onDate
              AND (r.effectiveTo IS NULL OR r.effectiveTo >= :onDate)
            """)
    Optional<WageRate> findEffectiveOn(@Param("workerId") UUID workerId,
                                       @Param("onDate") LocalDate onDate);

    /** Bulk form, so verifying a sixty-worker roster costs one query rather than sixty. */
    @Query("""
            SELECT r FROM WageRate r
            WHERE r.workerId IN :workerIds
              AND r.effectiveFrom <= :onDate
              AND (r.effectiveTo IS NULL OR r.effectiveTo >= :onDate)
            """)
    List<WageRate> findEffectiveOnFor(@Param("workerIds") Collection<UUID> workerIds,
                                      @Param("onDate") LocalDate onDate);
}

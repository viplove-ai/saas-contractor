package in.nirman.modules.billing.repository;

import in.nirman.modules.billing.domain.RaBill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RaBillRepository extends JpaRepository<RaBill, UUID> {

    Optional<RaBill> findByIdAndOrgIdAndDeletedAtIsNull(UUID id, UUID orgId);

    List<RaBill> findByOrgIdAndProjectIdAndDeletedAtIsNullOrderBySerialNoDesc(UUID orgId, UUID projectId);

    /**
     * What the last passed bill said the work had come to.
     *
     * <p>Cumulative by construction — every bill's gross is the work up to its own cutoff — so
     * the latest passed one is the total billed to date and there is nothing to add up.</p>
     */
    @Query("""
            SELECT b.grossWorkDone FROM RaBill b
            WHERE b.projectId = :projectId AND b.deletedAt IS NULL
              AND b.status = in.nirman.modules.billing.domain.RaBill$Status.PASSED
            ORDER BY b.serialNo DESC
            LIMIT 1
            """)
    Optional<java.math.BigDecimal> grossBilledToDate(@Param("projectId") UUID projectId);

    /** The serial the next bill takes. A project's first bill is the 1st RA. */
    @Query("""
            SELECT COALESCE(MAX(b.serialNo), 0) FROM RaBill b
            WHERE b.projectId = :projectId AND b.deletedAt IS NULL
            """)
    int highestSerial(@Param("projectId") UUID projectId);

    /**
     * The bill a new one carries forward from. Deliberately the latest by serial rather than
     * the latest passed: a project cannot have two open bills, so if one is open it is the
     * one being worked on and a second must not be started behind it.
     */
    @Query("""
            SELECT b FROM RaBill b
            WHERE b.projectId = :projectId AND b.deletedAt IS NULL
            ORDER BY b.serialNo DESC
            LIMIT 1
            """)
    Optional<RaBill> latest(@Param("projectId") UUID projectId);
}

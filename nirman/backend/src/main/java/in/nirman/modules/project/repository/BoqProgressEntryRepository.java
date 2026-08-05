package in.nirman.modules.project.repository;

import in.nirman.modules.project.domain.BoqProgressEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface BoqProgressEntryRepository extends JpaRepository<BoqProgressEntry, UUID> {

    /**
     * The idempotency check a DPR verification consults before posting its claims.
     * {@code uq_boq_entry_dpr_item} (V9) is the backstop under a race; this is what turns a
     * second verification into a quiet no-op instead of a constraint violation.
     */
    boolean existsByDprIdAndBoqItemId(UUID dprId, UUID boqItemId);

    List<BoqProgressEntry> findByDprIdOrderByCreatedAt(UUID dprId);

    /** The measurement book for one line, oldest first — a running bill reads downwards. */
    @Query("""
            SELECT e FROM BoqProgressEntry e
            WHERE e.orgId = :orgId AND e.boqItemId = :boqItemId
              AND (:from IS NULL OR e.entryDate >= :from)
              AND (:to IS NULL OR e.entryDate <= :to)
            ORDER BY e.entryDate ASC, e.createdAt ASC
            """)
    List<BoqProgressEntry> findForItem(@Param("orgId") UUID orgId,
                                       @Param("boqItemId") UUID boqItemId,
                                       @Param("from") LocalDate from,
                                       @Param("to") LocalDate to);

    /**
     * What {@code boq_items.completed_quantity} is a cache of. Re-summing this is how the
     * cache is proved right, and the test that does so is the reason it is exposed.
     */
    @Query("""
            SELECT COALESCE(SUM(e.quantity), 0) FROM BoqProgressEntry e
            WHERE e.boqItemId = :boqItemId
            """)
    BigDecimal totalClaimed(@Param("boqItemId") UUID boqItemId);

    @Query("""
            SELECT e FROM BoqProgressEntry e
            WHERE e.orgId = :orgId
              AND (:siteId IS NULL OR e.siteId = :siteId)
              AND e.entryDate BETWEEN :from AND :to
            ORDER BY e.entryDate ASC
            """)
    List<BoqProgressEntry> findForPeriod(@Param("orgId") UUID orgId,
                                         @Param("siteId") UUID siteId,
                                         @Param("from") LocalDate from,
                                         @Param("to") LocalDate to);
}

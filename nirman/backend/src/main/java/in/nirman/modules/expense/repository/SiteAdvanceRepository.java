package in.nirman.modules.expense.repository;

import in.nirman.modules.expense.domain.SiteAdvance;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SiteAdvanceRepository extends JpaRepository<SiteAdvance, UUID> {

    Optional<SiteAdvance> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * SELECT … FOR UPDATE. Two settlements approved at once against the same float must not
     * both read the same starting balance — that is how ₹20,000 of petty cash clears
     * ₹30,000 of bills.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM SiteAdvance a WHERE a.id = :id")
    Optional<SiteAdvance> findForUpdate(@Param("id") UUID id);

    @Query("""
            SELECT a FROM SiteAdvance a
            WHERE a.orgId = :orgId
              AND (:siteId IS NULL OR a.siteId = :siteId)
              AND (:userId IS NULL OR a.issuedToUserId = :userId)
              AND (:status IS NULL OR a.settlementStatus = :status)
              AND (:restricted = false OR a.siteId IN :siteIds)
            """)
    Page<SiteAdvance> search(@Param("orgId") UUID orgId,
                             @Param("siteId") UUID siteId,
                             @Param("userId") UUID userId,
                             @Param("status") SiteAdvance.SettlementStatus status,
                             @Param("restricted") boolean restricted,
                             @Param("siteIds") Collection<UUID> siteIds,
                             Pageable pageable);

    /** Floats still in somebody's pocket, for the advance-balances report. */
    @Query("""
            SELECT a FROM SiteAdvance a
            WHERE a.orgId = :orgId
              AND a.settlementStatus IN (
                    in.nirman.modules.expense.domain.SiteAdvance$SettlementStatus.OPEN,
                    in.nirman.modules.expense.domain.SiteAdvance$SettlementStatus.PARTIALLY_SETTLED)
              AND (:siteId IS NULL OR a.siteId = :siteId)
              AND (:userId IS NULL OR a.issuedToUserId = :userId)
            ORDER BY a.advanceDate ASC
            """)
    List<SiteAdvance> findOpen(@Param("orgId") UUID orgId,
                               @Param("siteId") UUID siteId,
                               @Param("userId") UUID userId);
}

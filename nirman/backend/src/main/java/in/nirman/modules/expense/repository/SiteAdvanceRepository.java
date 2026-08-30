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

    /**
     * Floats with something still to argue about, for the balances report and the settle
     * screen.
     *
     * <p>OVERSPENT is in the list for the reason V49 gave it a name at all: a float somebody
     * has spent past is not finished with, it is the one the company owes money on. Leaving
     * it out would drop exactly the position the office most needs in front of it.</p>
     */
    @Query("""
            SELECT a FROM SiteAdvance a
            WHERE a.orgId = :orgId
              AND a.settlementStatus IN (
                    in.nirman.modules.expense.domain.SiteAdvance$SettlementStatus.OPEN,
                    in.nirman.modules.expense.domain.SiteAdvance$SettlementStatus.PARTIALLY_SETTLED,
                    in.nirman.modules.expense.domain.SiteAdvance$SettlementStatus.OVERSPENT)
              AND (:siteId IS NULL OR a.siteId = :siteId)
              AND (:userId IS NULL OR a.issuedToUserId = :userId)
            ORDER BY a.advanceDate ASC
            """)
    List<SiteAdvance> findOpen(@Param("orgId") UUID orgId,
                               @Param("siteId") UUID siteId,
                               @Param("userId") UUID userId);

    /**
     * Every float ever issued to one person at one site, spent or not.
     *
     * <p>The balances report sums these rather than reading a stored per-person figure,
     * because a balance somebody can store is a second version of a truth these rows already
     * tell — the same argument the vendor account makes about not keeping a balance on the
     * vendor.</p>
     */
    @Query("""
            SELECT a FROM SiteAdvance a
            WHERE a.orgId = :orgId
              AND a.settlementStatus <> in.nirman.modules.expense.domain.SiteAdvance$SettlementStatus.CANCELLED
              AND (:siteId IS NULL OR a.siteId = :siteId)
              AND (:userId IS NULL OR a.issuedToUserId = :userId)
              AND (:restricted = false OR a.siteId IN :siteIds)
            ORDER BY a.advanceDate ASC
            """)
    List<SiteAdvance> findLive(@Param("orgId") UUID orgId,
                               @Param("siteId") UUID siteId,
                               @Param("userId") UUID userId,
                               @Param("restricted") boolean restricted,
                               @Param("siteIds") Collection<UUID> siteIds);
}

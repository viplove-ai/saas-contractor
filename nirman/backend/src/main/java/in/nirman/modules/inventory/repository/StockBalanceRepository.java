package in.nirman.modules.inventory.repository;

import in.nirman.modules.inventory.domain.StockBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockBalanceRepository extends JpaRepository<StockBalance, UUID> {

    /**
     * SELECT … FOR UPDATE. Two movements of the same material in the same store must not
     * read the same starting quantity and then both write their own result — that is how a
     * store issues forty bags twice out of a heap of forty.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM StockBalance b WHERE b.storeId = :storeId AND b.materialId = :materialId")
    Optional<StockBalance> findForUpdate(@Param("storeId") UUID storeId,
                                         @Param("materialId") UUID materialId);

    Optional<StockBalance> findByStoreIdAndMaterialId(UUID storeId, UUID materialId);

    List<StockBalance> findByStoreIdIn(Collection<UUID> storeIds);

    /**
     * The stock position. {@code restricted} is the caller's scope rather than a filter they
     * asked for: a supervisor who names no store still sees only the stores at his own
     * sites, never everybody's.
     */
    @Query("""
            SELECT b FROM StockBalance b
            WHERE b.orgId = :orgId
              AND (:storeId IS NULL OR b.storeId = :storeId)
              AND (:materialId IS NULL OR b.materialId = :materialId)
              AND (:restricted = false OR b.storeId IN :storeIds)
            ORDER BY b.storeId, b.materialId
            """)
    List<StockBalance> search(@Param("orgId") UUID orgId,
                              @Param("storeId") UUID storeId,
                              @Param("materialId") UUID materialId,
                              @Param("restricted") boolean restricted,
                              @Param("storeIds") Collection<UUID> storeIds);
}

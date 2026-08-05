package in.nirman.modules.inventory.repository;

import in.nirman.modules.inventory.domain.StockTransfer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockTransferRepository extends JpaRepository<StockTransfer, UUID> {

    Optional<StockTransfer> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * A transfer has two ends and the caller may be at either. {@code storeIds} is the set
     * of stores at the caller's sites, so a supervisor sees what is coming to him as well as
     * what he sent — matching either end, not both.
     */
    @Query("""
            SELECT t FROM StockTransfer t
            WHERE t.orgId = :orgId
              AND (:fromStoreId IS NULL OR t.fromStoreId = :fromStoreId)
              AND (:toStoreId IS NULL OR t.toStoreId = :toStoreId)
              AND (:status IS NULL OR t.status = :status)
              AND (:from IS NULL OR t.transferDate >= :from)
              AND (:to IS NULL OR t.transferDate <= :to)
              AND (:restricted = false
                   OR t.fromStoreId IN :storeIds OR t.toStoreId IN :storeIds)
            """)
    Page<StockTransfer> search(@Param("orgId") UUID orgId,
                               @Param("fromStoreId") UUID fromStoreId,
                               @Param("toStoreId") UUID toStoreId,
                               @Param("status") StockTransfer.Status status,
                               @Param("from") LocalDate from,
                               @Param("to") LocalDate to,
                               @Param("restricted") boolean restricted,
                               @Param("storeIds") Collection<UUID> storeIds,
                               Pageable pageable);

    @Query("""
            SELECT t FROM StockTransfer t
            WHERE t.orgId = :orgId
              AND t.transferDate BETWEEN :from AND :to
            ORDER BY t.transferDate ASC, t.transferNumber ASC
            """)
    List<StockTransfer> findForPeriod(@Param("orgId") UUID orgId,
                                      @Param("from") LocalDate from,
                                      @Param("to") LocalDate to);
}

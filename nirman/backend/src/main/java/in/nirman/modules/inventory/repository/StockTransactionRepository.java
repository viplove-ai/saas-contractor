package in.nirman.modules.inventory.repository;

import in.nirman.modules.inventory.domain.StockTransaction;
import in.nirman.modules.inventory.domain.StockTransaction.SourceType;
import in.nirman.modules.inventory.domain.StockTransaction.TxnType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface StockTransactionRepository extends JpaRepository<StockTransaction, UUID> {

    /**
     * The idempotency check the ledger consults before posting a document line. The unique
     * index {@code uq_stx_source_line} is the backstop under a race; this is what turns a
     * re-sent document into a quiet no-op instead of a 409.
     */
    boolean existsBySourceTypeAndSourceLineIdAndTxnType(SourceType sourceType, UUID sourceLineId,
                                                        TxnType txnType);

    boolean existsByStoreIdAndMaterialIdAndTxnType(UUID storeId, UUID materialId, TxnType txnType);

    List<StockTransaction> findBySourceTypeAndSourceId(SourceType sourceType, UUID sourceId);

    /** The movement history behind a balance. Oldest first — a ledger reads downwards. */
    @Query("""
            SELECT t FROM StockTransaction t
            WHERE t.orgId = :orgId
              AND (:storeId IS NULL OR t.storeId = :storeId)
              AND (:materialId IS NULL OR t.materialId = :materialId)
              AND (:from IS NULL OR t.txnDate >= :from)
              AND (:to IS NULL OR t.txnDate <= :to)
              AND (:restricted = false OR t.siteId IN :siteIds)
            ORDER BY t.txnDate ASC, t.createdAt ASC
            """)
    Page<StockTransaction> ledger(@Param("orgId") UUID orgId,
                                  @Param("storeId") UUID storeId,
                                  @Param("materialId") UUID materialId,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to,
                                  @Param("restricted") boolean restricted,
                                  @Param("siteIds") Collection<UUID> siteIds,
                                  Pageable pageable);

    /**
     * Movements of one type over a period, for the consumption and wastage reports.
     * Unpaged on purpose: these feed spreadsheet exports, which want the whole set.
     */
    @Query("""
            SELECT t FROM StockTransaction t
            WHERE t.orgId = :orgId
              AND t.txnType IN :types
              AND (:siteId IS NULL OR t.siteId = :siteId)
              AND t.txnDate BETWEEN :from AND :to
            ORDER BY t.txnDate ASC, t.createdAt ASC
            """)
    List<StockTransaction> findByTypes(@Param("orgId") UUID orgId,
                                       @Param("types") Collection<TxnType> types,
                                       @Param("siteId") UUID siteId,
                                       @Param("from") LocalDate from,
                                       @Param("to") LocalDate to);

    /**
     * Every movement at a site over a period, whatever its type — the input to the DPR's
     * daily material table and to the dashboard's opening-plus-received-less-consumed
     * identity. {@code siteId} null means every site in the organisation.
     *
     * <p>Unfiltered by type on purpose: the caller has to see transfers in order to explain a
     * residual, and a query that hid them would make the reconciliation look broken.</p>
     */
    @Query("""
            SELECT t FROM StockTransaction t
            WHERE t.orgId = :orgId
              AND (:siteId IS NULL OR t.siteId = :siteId)
              AND t.txnDate BETWEEN :from AND :to
            ORDER BY t.txnDate ASC, t.createdAt ASC
            """)
    List<StockTransaction> findForSitePeriod(@Param("orgId") UUID orgId,
                                            @Param("siteId") UUID siteId,
                                            @Param("from") LocalDate from,
                                            @Param("to") LocalDate to);

    /**
     * The signed value of everything that moved before a date: the opening inventory value
     * the period's arithmetic starts from.
     *
     * <p>Summed from the ledger rather than read from a snapshot, because the ledger is the
     * only thing that can answer it for an arbitrary date — {@code stock_balances} knows
     * today, not the first of last month.</p>
     */
    @Query("""
            SELECT COALESCE(SUM(t.value * t.direction), 0) FROM StockTransaction t
            WHERE t.orgId = :orgId
              AND (:siteId IS NULL OR t.siteId = :siteId)
              AND t.txnDate < :before
            """)
    BigDecimal signedValueBefore(@Param("orgId") UUID orgId,
                                 @Param("siteId") UUID siteId,
                                 @Param("before") LocalDate before);

    /**
     * Consumption charged to no work item. A data-quality figure rather than an error: it is
     * legitimate for cartage nails to go out against a purpose alone, and it is a problem
     * when half the cement does.
     */
    @Query("""
            SELECT count(t) FROM StockTransaction t
            WHERE t.orgId = :orgId
              AND (:siteId IS NULL OR t.siteId = :siteId)
              AND t.txnType = in.nirman.modules.inventory.domain.StockTransaction$TxnType.ISSUE
              AND t.boqItemId IS NULL
              AND t.txnDate BETWEEN :from AND :to
            """)
    long countConsumptionWithoutBoqItem(@Param("orgId") UUID orgId,
                                        @Param("siteId") UUID siteId,
                                        @Param("from") LocalDate from,
                                        @Param("to") LocalDate to);

    /**
     * Everything ever issued of one material on one project, whatever the store. The
     * estimated-versus-actual comparison reads this and then splits it by BOQ item, because
     * a variance is only honest over the scope the estimate actually covers (docs/09).
     */
    @Query("""
            SELECT t FROM StockTransaction t
            WHERE t.orgId = :orgId
              AND t.materialId = :materialId
              AND t.projectId = :projectId
              AND t.txnType IN :types
            """)
    List<StockTransaction> findForProjectMaterial(@Param("orgId") UUID orgId,
                                                  @Param("projectId") UUID projectId,
                                                  @Param("materialId") UUID materialId,
                                                  @Param("types") Collection<TxnType> types);
}

package in.nirman.modules.inventory.repository;

import in.nirman.modules.inventory.domain.DocumentWorkflow;
import in.nirman.modules.inventory.domain.GoodsReceipt;
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

public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, UUID> {

    Optional<GoodsReceipt> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * The duplicate-delivery check. Mirrors {@code uq_grn_vendor_invoice}: comparison is
     * case- and whitespace-insensitive, cancelled receipts are ignored, and the placeholder
     * bill numbers the field actually writes — "-", "NIL", "Local" — are excluded, because
     * treating those as invoice numbers would reject the second delivery of the day.
     */
    @Query("""
            SELECT g FROM GoodsReceipt g
            WHERE g.orgId = :orgId
              AND g.vendorId = :vendorId
              AND upper(trim(g.invoiceNumber)) = upper(trim(:invoiceNumber))
              AND g.workflowStatus <> in.nirman.modules.inventory.domain.DocumentWorkflow.CANCELLED
            """)
    List<GoodsReceipt> findSameInvoice(@Param("orgId") UUID orgId,
                                       @Param("vendorId") UUID vendorId,
                                       @Param("invoiceNumber") String invoiceNumber);

    @Query("""
            SELECT g FROM GoodsReceipt g
            WHERE g.orgId = :orgId
              AND (:siteId IS NULL OR g.siteId = :siteId)
              AND (:storeId IS NULL OR g.storeId = :storeId)
              AND (:vendorId IS NULL OR g.vendorId = :vendorId)
              AND (:status IS NULL OR g.workflowStatus = :status)
              AND (:from IS NULL OR g.receiptDate >= :from)
              AND (:to IS NULL OR g.receiptDate <= :to)
              AND (:restricted = false OR g.siteId IN :siteIds)
            """)
    Page<GoodsReceipt> search(@Param("orgId") UUID orgId,
                              @Param("siteId") UUID siteId,
                              @Param("storeId") UUID storeId,
                              @Param("vendorId") UUID vendorId,
                              @Param("status") DocumentWorkflow status,
                              @Param("from") LocalDate from,
                              @Param("to") LocalDate to,
                              @Param("restricted") boolean restricted,
                              @Param("siteIds") Collection<UUID> siteIds,
                              Pageable pageable);
}

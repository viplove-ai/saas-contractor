package in.nirman.modules.expense.repository;

import in.nirman.modules.expense.domain.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdAndOrgId(UUID id, UUID orgId);

    List<Payment> findByExpenseIdOrderByPaymentDateAsc(UUID expenseId);

    List<Payment> findByExpenseIdIn(Collection<UUID> expenseIds);

    @Query("""
            SELECT p FROM Payment p
            WHERE p.orgId = :orgId
              AND (:vendorId IS NULL OR p.vendorId = :vendorId)
              AND (:expenseId IS NULL OR p.expenseId = :expenseId)
              AND (:from IS NULL OR p.paymentDate >= :from)
              AND (:to IS NULL OR p.paymentDate <= :to)
            """)
    Page<Payment> search(@Param("orgId") UUID orgId,
                         @Param("vendorId") UUID vendorId,
                         @Param("expenseId") UUID expenseId,
                         @Param("from") LocalDate from,
                         @Param("to") LocalDate to,
                         Pageable pageable);

    /**
     * Cash paid to a supplier against no bill of his. COALESCE because a supplier nobody has
     * ever paid in advance should read as zero rather than as an absent figure the caller
     * has to interpret — everything downstream subtracts it.
     */
    @Query("""
            SELECT COALESCE(SUM(p.amount), 0) FROM Payment p
            WHERE p.orgId = :orgId AND p.vendorId = :vendorId AND p.expenseId IS NULL
            """)
    BigDecimal sumVendorAdvances(@Param("orgId") UUID orgId, @Param("vendorId") UUID vendorId);

    /** A supplier's cash movements, both kinds, oldest first — his account as a statement. */
    @Query("""
            SELECT p FROM Payment p
            WHERE p.orgId = :orgId AND p.vendorId = :vendorId
            ORDER BY p.paymentDate ASC, p.paymentNumber ASC
            """)
    List<Payment> findForVendor(@Param("orgId") UUID orgId, @Param("vendorId") UUID vendorId);
}

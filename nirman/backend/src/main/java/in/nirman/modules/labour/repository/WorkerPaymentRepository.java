package in.nirman.modules.labour.repository;

import in.nirman.modules.labour.domain.WorkerPayment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface WorkerPaymentRepository extends JpaRepository<WorkerPayment, UUID> {

    Optional<WorkerPayment> findByIdAndOrgId(UUID id, UUID orgId);

    /** The same shape as the advance search: the filter is one thing, the scope another. */
    @Query("""
            SELECT p FROM WorkerPayment p
            WHERE p.orgId = :orgId
              AND (:siteId IS NULL OR p.siteId = :siteId)
              AND (:workerId IS NULL OR p.workerId = :workerId)
              AND (:from IS NULL OR p.paymentDate >= :from)
              AND (:to IS NULL OR p.paymentDate <= :to)
              AND (:siteIdsRestricted = false OR p.siteId IN :siteIds)
            """)
    Page<WorkerPayment> search(@Param("orgId") UUID orgId,
                               @Param("siteId") UUID siteId,
                               @Param("workerId") UUID workerId,
                               @Param("from") LocalDate from,
                               @Param("to") LocalDate to,
                               @Param("siteIdsRestricted") boolean siteIdsRestricted,
                               @Param("siteIds") Collection<UUID> siteIds,
                               Pageable pageable);
}

package in.nirman.modules.dpr.repository;

import in.nirman.modules.dpr.domain.DailyProgressReport;
import in.nirman.modules.dpr.domain.DailyProgressReport.Workflow;
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

public interface DailyProgressReportRepository extends JpaRepository<DailyProgressReport, UUID> {

    Optional<DailyProgressReport> findByIdAndOrgId(UUID id, UUID orgId);

    /** Mirrors {@code uq_dpr_site_date}: one report per site per day, checked before the index. */
    Optional<DailyProgressReport> findBySiteIdAndReportDate(UUID siteId, LocalDate reportDate);

    @Query("""
            SELECT d FROM DailyProgressReport d
            WHERE d.orgId = :orgId
              AND (:siteId IS NULL OR d.siteId = :siteId)
              AND (:status IS NULL OR d.workflowStatus = :status)
              AND (:from IS NULL OR d.reportDate >= :from)
              AND (:to IS NULL OR d.reportDate <= :to)
              AND (:restricted = false OR d.siteId IN :siteIds)
            """)
    Page<DailyProgressReport> search(@Param("orgId") UUID orgId,
                                     @Param("siteId") UUID siteId,
                                     @Param("status") Workflow status,
                                     @Param("from") LocalDate from,
                                     @Param("to") LocalDate to,
                                     @Param("restricted") boolean restricted,
                                     @Param("siteIds") Collection<UUID> siteIds,
                                     Pageable pageable);

    @Query("""
            SELECT d FROM DailyProgressReport d
            WHERE d.orgId = :orgId
              AND (:siteId IS NULL OR d.siteId = :siteId)
              AND d.reportDate BETWEEN :from AND :to
            ORDER BY d.reportDate ASC
            """)
    List<DailyProgressReport> findForPeriod(@Param("orgId") UUID orgId,
                                           @Param("siteId") UUID siteId,
                                           @Param("from") LocalDate from,
                                           @Param("to") LocalDate to);

    /** Reports waiting on an engineer's signature — the verification queue's count. */
    @Query("""
            SELECT count(d) FROM DailyProgressReport d
            WHERE d.orgId = :orgId
              AND (:siteId IS NULL OR d.siteId = :siteId)
              AND d.workflowStatus = in.nirman.modules.dpr.domain.DailyProgressReport$Workflow.SUBMITTED
            """)
    long countAwaitingVerification(@Param("orgId") UUID orgId, @Param("siteId") UUID siteId);
}

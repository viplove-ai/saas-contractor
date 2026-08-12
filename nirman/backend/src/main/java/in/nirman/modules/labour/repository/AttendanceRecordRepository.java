package in.nirman.modules.labour.repository;

import in.nirman.modules.labour.domain.AttendanceRecord;
import in.nirman.modules.labour.domain.WorkflowStatus;
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

public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, UUID> {

    /**
     * Whether this man has ever been on a muster roll — the first question asked before he can
     * be deleted. Cancelled rows count: somebody stood at a gate and marked him, which is not
     * what a row entered by mistake looks like.
     */
    long countByWorkerId(UUID workerId);

    /**
     * The live row for a worker on a day, if any. Cancelled rows are excluded to match
     * {@code uq_attendance_worker_site_date}, so a mistaken entry can be cancelled and
     * re-entered without deleting history.
     */
    @Query("""
            SELECT a FROM AttendanceRecord a
            WHERE a.workerId = :workerId AND a.siteId = :siteId
              AND a.attendanceDate = :date AND a.workflowStatus <> 'CANCELLED'
            """)
    Optional<AttendanceRecord> findLive(@Param("workerId") UUID workerId,
                                        @Param("siteId") UUID siteId,
                                        @Param("date") LocalDate date);

    @Query("""
            SELECT a FROM AttendanceRecord a
            WHERE a.siteId = :siteId AND a.attendanceDate = :date
              AND a.workflowStatus <> 'CANCELLED'
            """)
    List<AttendanceRecord> findLiveForDay(@Param("siteId") UUID siteId,
                                          @Param("date") LocalDate date);

    List<AttendanceRecord> findByIdInAndOrgId(Collection<UUID> ids, UUID orgId);

    @Query("""
            SELECT a FROM AttendanceRecord a
            WHERE a.orgId = :orgId
              AND (:siteId IS NULL OR a.siteId = :siteId)
              AND (:workerId IS NULL OR a.workerId = :workerId)
              AND (:status IS NULL OR a.workflowStatus = :status)
              AND a.attendanceDate BETWEEN :from AND :to
              AND (:siteIdsRestricted = false OR a.siteId IN :siteIds)
            """)
    Page<AttendanceRecord> search(@Param("orgId") UUID orgId,
                                  @Param("siteId") UUID siteId,
                                  @Param("workerId") UUID workerId,
                                  @Param("status") WorkflowStatus status,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to,
                                  @Param("siteIdsRestricted") boolean siteIdsRestricted,
                                  @Param("siteIds") Collection<UUID> siteIds,
                                  Pageable pageable);

    @Query("""
            SELECT a FROM AttendanceRecord a
            WHERE a.siteId = :siteId
              AND a.attendanceDate BETWEEN :from AND :to
              AND a.workflowStatus <> 'CANCELLED'
            ORDER BY a.attendanceDate, a.workerId
            """)
    List<AttendanceRecord> findForPeriod(@Param("siteId") UUID siteId,
                                         @Param("from") LocalDate from,
                                         @Param("to") LocalDate to);

    /**
     * Live rows over a period for the whole organisation or one site of it, for the DPR
     * roll-ups and the dashboards. {@code siteId} null means every site — which
     * {@link #findForPeriod} cannot express, because a company dashboard has no one site.
     */
    @Query("""
            SELECT a FROM AttendanceRecord a
            WHERE a.orgId = :orgId
              AND (:siteId IS NULL OR a.siteId = :siteId)
              AND a.attendanceDate BETWEEN :from AND :to
              AND a.workflowStatus <> 'CANCELLED'
            ORDER BY a.attendanceDate, a.workerId
            """)
    List<AttendanceRecord> findLiveForOrgPeriod(@Param("orgId") UUID orgId,
                                                @Param("siteId") UUID siteId,
                                                @Param("from") LocalDate from,
                                                @Param("to") LocalDate to);

    /** Rows a period lock is about to close. */
    @Query("""
            SELECT a FROM AttendanceRecord a
            WHERE a.siteId = :siteId
              AND a.attendanceDate BETWEEN :from AND :to
              AND a.workflowStatus = 'VERIFIED'
            """)
    List<AttendanceRecord> findVerifiedInPeriod(@Param("siteId") UUID siteId,
                                                @Param("from") LocalDate from,
                                                @Param("to") LocalDate to);
}

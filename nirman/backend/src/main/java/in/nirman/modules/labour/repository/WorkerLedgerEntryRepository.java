package in.nirman.modules.labour.repository;

import in.nirman.modules.labour.domain.WorkerLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface WorkerLedgerEntryRepository extends JpaRepository<WorkerLedgerEntry, UUID> {

    /**
     * The idempotency check behind "verifying twice must not pay twice". Backed by
     * {@code uq_wle_attendance_posting}, so a race that slips past this check still fails
     * at the constraint rather than paying the worker a second time.
     */
    boolean existsBySourceTypeAndSourceIdAndEntryType(WorkerLedgerEntry.SourceType sourceType,
                                                      UUID sourceId,
                                                      WorkerLedgerEntry.EntryType entryType);

    List<WorkerLedgerEntry> findBySourceTypeAndSourceId(WorkerLedgerEntry.SourceType sourceType,
                                                        UUID sourceId);

    @Query("""
            SELECT e FROM WorkerLedgerEntry e
            WHERE e.workerId = :workerId
              AND (:from IS NULL OR e.entryDate >= :from)
              AND (:to IS NULL OR e.entryDate <= :to)
            ORDER BY e.entryDate, e.createdAt
            """)
    List<WorkerLedgerEntry> findForWorker(@Param("workerId") UUID workerId,
                                          @Param("from") LocalDate from,
                                          @Param("to") LocalDate to);

    List<WorkerLedgerEntry> findByOrgIdAndPeriodYearMonthAndSiteId(UUID orgId, String periodYearMonth,
                                                                   UUID siteId);

    /**
     * Everything posted at a site over a date range. The wage summary needs the advances
     * <em>drawn in the period</em>, not the worker's lifetime balance — a man who owed
     * money in March should not have it subtracted again from his April sheet.
     */
    @Query("""
            SELECT e FROM WorkerLedgerEntry e
            WHERE e.siteId = :siteId AND e.entryDate BETWEEN :from AND :to
            ORDER BY e.workerId, e.entryDate
            """)
    List<WorkerLedgerEntry> findForSitePeriod(@Param("siteId") UUID siteId,
                                              @Param("from") LocalDate from,
                                              @Param("to") LocalDate to);
}

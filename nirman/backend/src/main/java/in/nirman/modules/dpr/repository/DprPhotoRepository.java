package in.nirman.modules.dpr.repository;

import in.nirman.modules.dpr.domain.DprPhoto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DprPhotoRepository extends JpaRepository<DprPhoto, UUID> {

    List<DprPhoto> findByDprIdOrderBySortOrder(UUID dprId);

    boolean existsByDprIdAndAttachmentId(UUID dprId, UUID attachmentId);

    /**
     * Every photograph on every live report of a project, newest day first and in the order
     * they sit on each report — the project's gallery. Narrowed to the caller's sites the way
     * the register is, and to a site or a span of days when asked.
     *
     * <p>A join on the id rather than a mapped association, because {@code DprPhoto} carries
     * the report's id and not the report, as every child row here does. The optional filters
     * take a typed flag beside an always-bound value rather than {@code (:x IS NULL OR ...)},
     * for the reason the billing repositories do: Postgres cannot type a parameter standing
     * alone in {@code ? IS NULL} and refuses to prepare the statement.</p>
     */
    @Query(value = """
            SELECT new in.nirman.modules.dpr.repository.PhotoOnReport(p, d)
            FROM DprPhoto p JOIN DailyProgressReport d ON d.id = p.dprId
            WHERE d.orgId = :orgId
              AND d.deletedAt IS NULL
              AND d.projectId = :projectId
              AND (:anySite = TRUE OR d.siteId = :siteId)
              AND (:ignoreFrom = TRUE OR d.reportDate >= :from)
              AND (:ignoreTo = TRUE OR d.reportDate <= :to)
              AND (:restricted = FALSE OR d.siteId IN :siteIds)
            ORDER BY d.reportDate DESC, d.siteId, p.sortOrder
            """,
            countQuery = """
            SELECT count(p)
            FROM DprPhoto p JOIN DailyProgressReport d ON d.id = p.dprId
            WHERE d.orgId = :orgId
              AND d.deletedAt IS NULL
              AND d.projectId = :projectId
              AND (:anySite = TRUE OR d.siteId = :siteId)
              AND (:ignoreFrom = TRUE OR d.reportDate >= :from)
              AND (:ignoreTo = TRUE OR d.reportDate <= :to)
              AND (:restricted = FALSE OR d.siteId IN :siteIds)
            """)
    Page<PhotoOnReport> gallery(@Param("orgId") UUID orgId,
                                @Param("projectId") UUID projectId,
                                @Param("anySite") boolean anySite,
                                @Param("siteId") UUID siteId,
                                @Param("ignoreFrom") boolean ignoreFrom,
                                @Param("from") LocalDate from,
                                @Param("ignoreTo") boolean ignoreTo,
                                @Param("to") LocalDate to,
                                @Param("restricted") boolean restricted,
                                @Param("siteIds") Collection<UUID> siteIds,
                                Pageable pageable);
}

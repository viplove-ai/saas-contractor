package in.nirman.modules.labour.repository;

import in.nirman.modules.labour.domain.Worker;
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

public interface WorkerRepository extends JpaRepository<Worker, UUID> {

    Optional<Worker> findByIdAndOrgIdAndDeletedAtIsNull(UUID id, UUID orgId);

    boolean existsByOrgIdAndWorkerCode(UUID orgId, String workerCode);

    /**
     * Worker search. {@code siteId} filters through the allocation open on the given date
     * rather than a column on the worker, because a worker moves between sites over time:
     * the answer must reflect where he was posted that day, not where he is posted today.
     *
     * <p>{@code q} is an empty string when nothing is being searched for, never null: a
     * null whose only context is {@code lower()} gives PostgreSQL no type to infer, and the
     * statement fails to parse as {@code lower(bytea)}. The other optional filters compare
     * against a mapped attribute, which is type enough.</p>
     *
     * <p>{@code siteIds} is the caller's own scope and is separate from the {@code siteId}
     * filter on purpose: one is what the user asked to see, the other is what they are
     * allowed to see, and a request with no filter must still mean "my sites" rather than
     * "every site".</p>
     */
    @Query("""
            SELECT w FROM Worker w
            WHERE w.orgId = :orgId AND w.deletedAt IS NULL
              AND (:active IS NULL OR w.active = :active)
              AND (:contractorId IS NULL OR w.labourContractorId = :contractorId)
              AND (:skillId IS NULL OR w.skillCategoryId = :skillId)
              AND (:q = '' OR lower(w.fullName) LIKE lower(concat('%', :q, '%'))
                           OR lower(w.workerCode) LIKE lower(concat('%', :q, '%')))
              AND (:siteId IS NULL OR EXISTS (
                    SELECT 1 FROM WorkerSiteAllocation a
                    WHERE a.workerId = w.id AND a.siteId = :siteId
                      AND a.effectiveFrom <= :onDate
                      AND (a.effectiveTo IS NULL OR a.effectiveTo >= :onDate)))
              AND (:siteIdsRestricted = false OR EXISTS (
                    SELECT 1 FROM WorkerSiteAllocation v
                    WHERE v.workerId = w.id AND v.siteId IN :siteIds
                      AND v.effectiveFrom <= :onDate
                      AND (v.effectiveTo IS NULL OR v.effectiveTo >= :onDate)))
            """)
    Page<Worker> search(@Param("orgId") UUID orgId,
                        @Param("siteId") UUID siteId,
                        @Param("onDate") LocalDate onDate,
                        @Param("contractorId") UUID contractorId,
                        @Param("skillId") UUID skillId,
                        @Param("active") Boolean active,
                        @Param("q") String q,
                        @Param("siteIdsRestricted") boolean siteIdsRestricted,
                        @Param("siteIds") Collection<UUID> siteIds,
                        Pageable pageable);

    /** The roster: every active worker allocated to the site on that date, in muster-roll order. */
    @Query("""
            SELECT w FROM Worker w
            WHERE w.orgId = :orgId AND w.deletedAt IS NULL AND w.active = true
              AND EXISTS (
                    SELECT 1 FROM WorkerSiteAllocation a
                    WHERE a.workerId = w.id AND a.siteId = :siteId
                      AND a.effectiveFrom <= :onDate
                      AND (a.effectiveTo IS NULL OR a.effectiveTo >= :onDate))
            ORDER BY w.workerCode
            """)
    List<Worker> findRoster(@Param("orgId") UUID orgId,
                            @Param("siteId") UUID siteId,
                            @Param("onDate") LocalDate onDate);

    List<Worker> findByIdInAndOrgIdAndDeletedAtIsNull(Collection<UUID> ids, UUID orgId);
}

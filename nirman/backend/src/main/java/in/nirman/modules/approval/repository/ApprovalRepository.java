package in.nirman.modules.approval.repository;

import in.nirman.modules.approval.domain.Approval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalRepository extends JpaRepository<Approval, UUID> {

    Optional<Approval> findByIdAndOrgId(UUID id, UUID orgId);

    /** The chain behind one record, oldest level first. */
    List<Approval> findByEntityTypeAndEntityIdOrderByLevelAscCreatedAtAsc(String entityType,
                                                                          UUID entityId);

    @Query("""
            SELECT a FROM Approval a
            WHERE a.entityType = :entityType AND a.entityId = :entityId
              AND a.status = in.nirman.modules.approval.domain.Approval$Status.PENDING
            """)
    Optional<Approval> findPending(@Param("entityType") String entityType,
                                   @Param("entityId") UUID entityId);

    /**
     * Somebody's queue. Matched on role rather than on user, because the queue belongs to
     * the job: an engineer on leave should not take his site's approvals with him.
     *
     * <p>{@code restricted} is the caller's site scope, kept separate from the
     * {@code siteId} they asked for — an unfiltered queue still means "mine". Records that
     * belong to no site are visible to anyone holding the role, since there is no site to
     * be assigned to.</p>
     */
    @Query("""
            SELECT a FROM Approval a
            WHERE a.orgId = :orgId
              AND a.status = in.nirman.modules.approval.domain.Approval$Status.PENDING
              AND a.assignedRole IN :roles
              AND (:entityType IS NULL OR a.entityType = :entityType)
              AND (:siteId IS NULL OR a.siteId = :siteId)
              AND (:restricted = false OR a.siteId IS NULL OR a.siteId IN :siteIds)
            ORDER BY a.createdAt ASC
            """)
    List<Approval> findPendingFor(@Param("orgId") UUID orgId,
                                  @Param("roles") Collection<String> roles,
                                  @Param("entityType") String entityType,
                                  @Param("siteId") UUID siteId,
                                  @Param("restricted") boolean restricted,
                                  @Param("siteIds") Collection<UUID> siteIds);
}

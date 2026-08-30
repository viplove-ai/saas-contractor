package in.nirman.modules.identity.repository;

import in.nirman.modules.identity.domain.UserSiteAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface UserSiteAssignmentRepository extends JpaRepository<UserSiteAssignment, UUID> {

    List<UserSiteAssignment> findByUserId(UUID userId);

    /** At most one row by the schema's unique constraint; a list keeps the caller honest. */
    List<UserSiteAssignment> findByUserIdAndSiteId(UUID userId, UUID siteId);

    /** Whose posting to this site is live today. Closed assignments are history, not staff. */
    @Query("""
            SELECT a.userId FROM UserSiteAssignment a
            WHERE a.orgId = :orgId AND a.siteId = :siteId
              AND a.assignedFrom <= :on
              AND (a.assignedTo IS NULL OR a.assignedTo >= :on)
            """)
    List<UUID> findActiveUserIds(@Param("orgId") UUID orgId, @Param("siteId") UUID siteId,
                                 @Param("on") LocalDate on);
}

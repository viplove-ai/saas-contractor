package in.nirman.modules.identity.repository;

import in.nirman.modules.identity.domain.UserSiteAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserSiteAssignmentRepository extends JpaRepository<UserSiteAssignment, UUID> {

    List<UserSiteAssignment> findByUserId(UUID userId);

    /** At most one row by the schema's unique constraint; a list keeps the caller honest. */
    List<UserSiteAssignment> findByUserIdAndSiteId(UUID userId, UUID siteId);
}

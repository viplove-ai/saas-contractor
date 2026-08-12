package in.nirman.modules.identity.repository;

import in.nirman.modules.identity.domain.StaffProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffProfileRepository extends JpaRepository<StaffProfile, UUID> {

    Optional<StaffProfile> findByUserId(UUID userId);

    /** Every profile in the organisation — the dashboard's whole input, on a staff of a dozen. */
    List<StaffProfile> findByOrgId(UUID orgId);
}

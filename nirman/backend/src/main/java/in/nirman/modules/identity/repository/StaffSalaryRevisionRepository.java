package in.nirman.modules.identity.repository;

import in.nirman.modules.identity.domain.StaffSalaryRevision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StaffSalaryRevisionRepository extends JpaRepository<StaffSalaryRevision, UUID> {

    /** Newest first: the first row is what applies today, and the rest is how it got there. */
    List<StaffSalaryRevision> findByUserIdOrderByEffectiveFromDesc(UUID userId);

    /** Every revision in the organisation, for the payroll tile. */
    List<StaffSalaryRevision> findByOrgIdOrderByEffectiveFromDesc(UUID orgId);
}

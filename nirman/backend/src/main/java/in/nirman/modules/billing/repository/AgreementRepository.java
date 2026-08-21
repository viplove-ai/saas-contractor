package in.nirman.modules.billing.repository;

import in.nirman.modules.billing.domain.Agreement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AgreementRepository extends JpaRepository<Agreement, UUID> {

    Optional<Agreement> findByOrgIdAndProjectId(UUID orgId, UUID projectId);
}

package in.nirman.modules.identity.repository;

import in.nirman.modules.identity.domain.Organisation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrganisationRepository extends JpaRepository<Organisation, UUID> {
}

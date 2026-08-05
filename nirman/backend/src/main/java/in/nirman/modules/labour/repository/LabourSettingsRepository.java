package in.nirman.modules.labour.repository;

import in.nirman.modules.labour.domain.LabourSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LabourSettingsRepository extends JpaRepository<LabourSettings, UUID> {

    Optional<LabourSettings> findByOrgId(UUID orgId);
}

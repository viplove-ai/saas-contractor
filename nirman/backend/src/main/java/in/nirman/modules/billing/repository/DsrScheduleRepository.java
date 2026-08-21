package in.nirman.modules.billing.repository;

import in.nirman.modules.billing.domain.DsrSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DsrScheduleRepository extends JpaRepository<DsrSchedule, UUID> {

    Optional<DsrSchedule> findByIdAndOrgIdAndDeletedAtIsNull(UUID id, UUID orgId);

    Optional<DsrSchedule> findByOrgIdAndCodeAndDeletedAtIsNull(UUID orgId, String code);

    List<DsrSchedule> findByOrgIdAndDeletedAtIsNullOrderByCodeAsc(UUID orgId);
}

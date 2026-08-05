package in.nirman.modules.masterdata.repository;

import in.nirman.modules.masterdata.domain.Unit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UnitRepository extends JpaRepository<Unit, UUID> {

    List<Unit> findByOrgIdOrderByCode(UUID orgId);

    Optional<Unit> findByOrgIdAndCode(UUID orgId, String code);

    boolean existsByOrgIdAndCode(UUID orgId, String code);
}

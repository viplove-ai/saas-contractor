package in.nirman.modules.dpr.repository;

import in.nirman.modules.dpr.domain.DprLabour;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DprLabourRepository extends JpaRepository<DprLabour, UUID> {

    List<DprLabour> findByDprId(UUID dprId);

    void deleteByDprId(UUID dprId);
}

package in.nirman.modules.dpr.repository;

import in.nirman.modules.dpr.domain.DprMachinery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DprMachineryRepository extends JpaRepository<DprMachinery, UUID> {

    List<DprMachinery> findByDprId(UUID dprId);

    void deleteByDprId(UUID dprId);
}

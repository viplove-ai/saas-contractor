package in.nirman.modules.dpr.repository;

import in.nirman.modules.dpr.domain.DprWorkItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DprWorkItemRepository extends JpaRepository<DprWorkItem, UUID> {

    List<DprWorkItem> findByDprIdOrderBySortOrder(UUID dprId);

    void deleteByDprId(UUID dprId);
}

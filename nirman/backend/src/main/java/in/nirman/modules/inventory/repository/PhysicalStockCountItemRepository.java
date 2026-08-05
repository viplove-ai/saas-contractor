package in.nirman.modules.inventory.repository;

import in.nirman.modules.inventory.domain.PhysicalStockCountItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PhysicalStockCountItemRepository extends JpaRepository<PhysicalStockCountItem, UUID> {

    List<PhysicalStockCountItem> findByCountId(UUID countId);
}

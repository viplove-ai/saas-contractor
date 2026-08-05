package in.nirman.modules.inventory.repository;

import in.nirman.modules.inventory.domain.StockTransferItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface StockTransferItemRepository extends JpaRepository<StockTransferItem, UUID> {

    List<StockTransferItem> findByTransferId(UUID transferId);

    List<StockTransferItem> findByTransferIdIn(Collection<UUID> transferIds);
}

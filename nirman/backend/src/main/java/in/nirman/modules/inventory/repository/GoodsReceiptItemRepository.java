package in.nirman.modules.inventory.repository;

import in.nirman.modules.inventory.domain.GoodsReceiptItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface GoodsReceiptItemRepository extends JpaRepository<GoodsReceiptItem, UUID> {

    List<GoodsReceiptItem> findByGrnId(UUID grnId);

    List<GoodsReceiptItem> findByGrnIdIn(Collection<UUID> grnIds);

    void deleteByGrnId(UUID grnId);
}

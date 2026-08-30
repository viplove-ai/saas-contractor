package in.nirman.modules.inventory.repository;

import in.nirman.modules.inventory.domain.GoodsReceiptAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GoodsReceiptAttachmentRepository
        extends JpaRepository<GoodsReceiptAttachment, UUID> {

    List<GoodsReceiptAttachment> findByGoodsReceiptId(UUID goodsReceiptId);
}

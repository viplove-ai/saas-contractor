package in.nirman.modules.treasury.repository;

import in.nirman.modules.treasury.domain.BankDepositPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface BankDepositPhotoRepository extends JpaRepository<BankDepositPhoto, UUID> {

    List<BankDepositPhoto> findByDepositIdOrderBySortOrderAscIdAsc(UUID depositId);

    /** Every register row's pictures in one query, rather than one query per row. */
    List<BankDepositPhoto> findByDepositIdInOrderBySortOrderAscIdAsc(Collection<UUID> depositIds);

    boolean existsByDepositIdAndAttachmentId(UUID depositId, UUID attachmentId);

    void deleteByDepositIdAndAttachmentId(UUID depositId, UUID attachmentId);
}

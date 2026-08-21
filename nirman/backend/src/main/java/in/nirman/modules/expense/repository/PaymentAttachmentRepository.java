package in.nirman.modules.expense.repository;

import in.nirman.modules.expense.domain.PaymentAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PaymentAttachmentRepository extends JpaRepository<PaymentAttachment, UUID> {

    List<PaymentAttachment> findByPaymentId(UUID paymentId);

    /** For a page of the register: one query for forty payments, not forty queries. */
    List<PaymentAttachment> findByPaymentIdIn(Collection<UUID> paymentIds);

    boolean existsByPaymentIdAndAttachmentId(UUID paymentId, UUID attachmentId);
}

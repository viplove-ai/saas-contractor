package in.nirman.modules.expense.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Links a stored file to the payment it proves — the UPI screenshot, the signed receipt, the
 * bank slip.
 *
 * <p>The twin of {@link ExpenseAttachment}, one step further along the same story. The bill
 * has had a photograph on it since the beginning; the payment settling that bill had a
 * reference number and nothing else, so the half of the transaction a supplier argues about
 * nine months later was the half with no evidence behind it.</p>
 *
 * <p>{@code docType} names what the picture is of rather than what the file is. A screenshot
 * of a UPI app and a receipt with a supplier's signature on it are not the same kind of proof
 * — the second binds him and the first only says the money moved — and a reader deciding how
 * far to trust one is entitled to be told which he is looking at.</p>
 */
@Entity
@Table(name = "payment_attachments")
public class PaymentAttachment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "attachment_id", nullable = false, updatable = false)
    private UUID attachmentId;

    /** RECEIPT | SCREENSHOT | BANK_SLIP | OTHER. */
    @Column(name = "doc_type", nullable = false, length = 30)
    private String docType = "RECEIPT";

    protected PaymentAttachment() {
    }

    public PaymentAttachment(UUID paymentId, UUID attachmentId, String docType) {
        this.id = UUID.randomUUID();
        this.paymentId = paymentId;
        this.attachmentId = attachmentId;
        this.docType = docType == null || docType.isBlank() ? "RECEIPT" : docType;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getAttachmentId() {
        return attachmentId;
    }

    public String getDocType() {
        return docType;
    }
}

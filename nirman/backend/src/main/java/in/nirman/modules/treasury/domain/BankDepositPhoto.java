package in.nirman.modules.treasury.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A photograph of the certificate, joined to the deposit rather than to a pledge of it.
 *
 * <p>The paper is the bank's and it does not change when the deposit moves to the next
 * contract, so the pictures follow the instrument about. No {@code BaseEntity}: this is a join
 * row with a caption, exactly as {@code dpr_photos} is, and it carries no version of its own —
 * a photograph is added or removed, never edited into a different photograph.</p>
 */
@Entity
@Table(name = "bank_deposit_photos")
public class BankDepositPhoto {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "deposit_id", nullable = false, updatable = false)
    private UUID depositId;

    @Column(name = "attachment_id", nullable = false, updatable = false)
    private UUID attachmentId;

    @Column(name = "caption", length = 300)
    private String caption;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected BankDepositPhoto() {
    }

    public BankDepositPhoto(UUID depositId, UUID attachmentId, String caption, int sortOrder) {
        this.id = UUID.randomUUID();
        this.depositId = depositId;
        this.attachmentId = attachmentId;
        this.caption = caption;
        this.sortOrder = sortOrder;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDepositId() {
        return depositId;
    }

    public UUID getAttachmentId() {
        return attachmentId;
    }

    public String getCaption() {
        return caption;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}

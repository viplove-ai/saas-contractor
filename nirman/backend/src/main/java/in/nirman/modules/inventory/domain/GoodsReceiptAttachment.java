package in.nirman.modules.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Links a stored file to the delivery it evidences — what came off the lorry, and the paper
 * that came with it.
 *
 * <p>A delivery is the one document where the thing and the paper are both in front of one man
 * for five minutes and never again, and everything downstream rests on what he typed in those
 * five minutes. {@code docType} names what the picture is of because the two answer different
 * questions and neither can stand for the other: MATERIAL is what actually arrived, INVOICE is
 * what the supplier says he sent, and when they disagree the disagreement is the point.</p>
 */
@Entity
@Table(name = "goods_receipt_attachments")
public class GoodsReceiptAttachment {

    /** What the picture is of. MATERIAL and INVOICE are both required to book a delivery. */
    public enum DocType { MATERIAL, INVOICE, OTHER }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "goods_receipt_id", nullable = false, updatable = false)
    private UUID goodsReceiptId;

    @Column(name = "attachment_id", nullable = false, updatable = false)
    private UUID attachmentId;

    @Column(name = "doc_type", nullable = false, length = 30)
    private String docType = DocType.MATERIAL.name();

    protected GoodsReceiptAttachment() {
    }

    public GoodsReceiptAttachment(UUID goodsReceiptId, UUID attachmentId, DocType docType) {
        this.id = UUID.randomUUID();
        this.goodsReceiptId = goodsReceiptId;
        this.attachmentId = attachmentId;
        this.docType = (docType == null ? DocType.MATERIAL : docType).name();
    }

    public UUID getId() {
        return id;
    }

    public UUID getGoodsReceiptId() {
        return goodsReceiptId;
    }

    public UUID getAttachmentId() {
        return attachmentId;
    }

    public String getDocType() {
        return docType;
    }
}

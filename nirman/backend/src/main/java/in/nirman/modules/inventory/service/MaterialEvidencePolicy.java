package in.nirman.modules.inventory.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.attachment.service.AttachmentLookup;
import in.nirman.modules.inventory.domain.GoodsReceiptAttachment;
import in.nirman.modules.inventory.domain.MaterialIssueAttachment;
import in.nirman.modules.inventory.repository.GoodsReceiptAttachmentRepository;
import in.nirman.modules.inventory.repository.MaterialIssueAttachmentRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * What counts as evidence that material moved, in one place.
 *
 * <p>A delivery is the one document in the system where the thing and the paper are both
 * standing in front of one man for five minutes and never again. The lorry tips its load and
 * leaves, the challan goes into a pocket, and everything downstream — the moving average, the
 * month's consumption, a supplier's account, an argument nine months later about whether forty
 * bags or thirty-two came off that vehicle — rests on what he typed in those five minutes.</p>
 *
 * <p><b>Two pictures on a delivery, because they answer two questions.</b> The material says
 * what arrived; the invoice or challan says what the supplier claims he sent. When those
 * disagree the disagreement is the whole point, and one photograph would have settled it
 * silently in whichever direction the camera was pointed. <b>One on an issue</b>: there is no
 * third party and no paper, and what left the store is a fact about the store.</p>
 *
 * <p>Asked at creation and nowhere else, because creation is the only moment it can be
 * satisfied — the man is still at the gate. It is not a check constraint for the reason V40
 * moved the expense's rule out of the database: what has to be true spans two tables, and a
 * row-level check can see one.</p>
 *
 * <p>The file is <b>claimed</b> as it is linked, which is what stops the man who uploaded it
 * discarding it afterwards as a stray draft.</p>
 */
@Component
public class MaterialEvidencePolicy {

    private final GoodsReceiptAttachmentRepository receiptPhotos;
    private final MaterialIssueAttachmentRepository issuePhotos;
    private final AttachmentLookup attachments;

    public MaterialEvidencePolicy(GoodsReceiptAttachmentRepository receiptPhotos,
                                  MaterialIssueAttachmentRepository issuePhotos,
                                  AttachmentLookup attachments) {
        this.receiptPhotos = receiptPhotos;
        this.issuePhotos = issuePhotos;
        this.attachments = attachments;
    }

    /**
     * Links the two pictures a delivery cannot be booked without.
     *
     * @throws BusinessException 422 naming the one that is missing, rather than "evidence
     *                           required" — the man has to know which camera to point where
     */
    public void attachToReceipt(UUID receiptId, UUID materialPhotoId, UUID invoicePhotoId) {
        require(materialPhotoId, "receipt.material-photo-required",
                "A delivery needs a photograph of what came off the lorry. The load is gone "
                        + "by the time anybody asks about it.");
        require(invoicePhotoId, "receipt.invoice-photo-required",
                "A delivery needs a photograph of the invoice or challan that came with it. "
                        + "What arrived and what the supplier says he sent are two different "
                        + "claims, and the paper is the second one.");

        if (invoicePhotoId.equals(materialPhotoId)) {
            throw new BusinessException("receipt.one-photo-for-two-claims",
                    "The load and the paper are two different claims and need two different "
                            + "pictures. One photograph standing for both settles the "
                            + "disagreement they exist to show.");
        }

        link(receiptId, materialPhotoId, GoodsReceiptAttachment.DocType.MATERIAL);
        link(receiptId, invoicePhotoId, GoodsReceiptAttachment.DocType.INVOICE);
    }

    /** The one picture an issue cannot be recorded without. */
    public void attachToIssue(UUID issueId, UUID materialPhotoId) {
        require(materialPhotoId, "issue.material-photo-required",
                "An issue needs a photograph of the material going out. It is what stops a "
                        + "quantity being a figure somebody rounded on the way to the office.");

        requireAPicture(materialPhotoId, "issue.material-photo-not-an-image");
        attachments.claimFor(materialPhotoId, issueId);
        issuePhotos.save(new MaterialIssueAttachment(issueId, materialPhotoId,
                MaterialIssueAttachment.DocType.MATERIAL));
    }

    /** What is on a delivery, for the response. */
    public List<GoodsReceiptAttachment> onReceipt(UUID receiptId) {
        return receiptPhotos.findByGoodsReceiptId(receiptId);
    }

    /** What is on an issue, for the response. */
    public List<MaterialIssueAttachment> onIssue(UUID issueId) {
        return issuePhotos.findByMaterialIssueId(issueId);
    }

    private void link(UUID receiptId, UUID attachmentId, GoodsReceiptAttachment.DocType type) {
        /*
          The load has to be a picture; the paper does not. Nothing but a camera can say what
          came off a lorry, so a PDF there is somebody satisfying the rule rather than meeting
          it. An invoice, on the other hand, genuinely arrives as a PDF from half the suppliers
          who send one at all, and refusing it would teach the office to photograph its screen.
        */
        if (type == GoodsReceiptAttachment.DocType.MATERIAL) {
            requireAPicture(attachmentId, "receipt.material-photo-not-an-image");
        } else {
            attachments.require(attachmentId);
        }
        attachments.claimFor(attachmentId, receiptId);
        receiptPhotos.save(new GoodsReceiptAttachment(receiptId, attachmentId, type));
    }

    private void requireAPicture(UUID attachmentId, String code) {
        AttachmentLookup.FileInfo file = attachments.require(attachmentId);
        if (!file.image()) {
            throw new BusinessException(code, file.fileName()
                    + " is not a picture. Nothing but a camera can say what the material was.");
        }
    }

    private static void require(UUID photoId, String code, String message) {
        if (photoId == null) {
            throw new BusinessException(code, message);
        }
    }
}

package in.nirman.modules.expense.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Links a stored file to the expense it evidences — the bill photograph, usually. */
@Entity
@Table(name = "expense_attachments")
public class ExpenseAttachment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "expense_id", nullable = false, updatable = false)
    private UUID expenseId;

    @Column(name = "attachment_id", nullable = false, updatable = false)
    private UUID attachmentId;

    /** BILL | QUOTE | DELIVERY_NOTE | OTHER. */
    @Column(name = "doc_type", nullable = false, length = 30)
    private String docType = "BILL";

    protected ExpenseAttachment() {
    }

    public ExpenseAttachment(UUID expenseId, UUID attachmentId, String docType) {
        this.id = UUID.randomUUID();
        this.expenseId = expenseId;
        this.attachmentId = attachmentId;
        this.docType = docType == null ? "BILL" : docType;
    }

    public UUID getId() {
        return id;
    }

    public UUID getExpenseId() {
        return expenseId;
    }

    public UUID getAttachmentId() {
        return attachmentId;
    }

    public String getDocType() {
        return docType;
    }
}

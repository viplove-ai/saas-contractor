package in.nirman.modules.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Links a stored file to the issue it evidences — what left the store for the work face.
 *
 * <p>One picture, not two. There is no third party on an issue and so no paper to disagree
 * with: what went out is a fact about the store, and the photograph is what stops "6 bags of
 * cement" being a figure somebody rounded on the way to the office.</p>
 */
@Entity
@Table(name = "material_issue_attachments")
public class MaterialIssueAttachment {

    public enum DocType { MATERIAL, OTHER }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "material_issue_id", nullable = false, updatable = false)
    private UUID materialIssueId;

    @Column(name = "attachment_id", nullable = false, updatable = false)
    private UUID attachmentId;

    @Column(name = "doc_type", nullable = false, length = 30)
    private String docType = DocType.MATERIAL.name();

    protected MaterialIssueAttachment() {
    }

    public MaterialIssueAttachment(UUID materialIssueId, UUID attachmentId, DocType docType) {
        this.id = UUID.randomUUID();
        this.materialIssueId = materialIssueId;
        this.attachmentId = attachmentId;
        this.docType = (docType == null ? DocType.MATERIAL : docType).name();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMaterialIssueId() {
        return materialIssueId;
    }

    public UUID getAttachmentId() {
        return attachmentId;
    }

    public String getDocType() {
        return docType;
    }
}

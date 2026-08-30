package in.nirman.modules.identity.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One paper behind a staff record — the Aadhaar card the last four digits were read off, the
 * cheque the account number was copied from, the letter somebody signed.
 *
 * <p>Keyed to the member rather than to his profile: a login exists on the day he starts and
 * the record is filled in afterwards, so papers that could only hang off a saved profile
 * would stay in the drawer until somebody had typed the rest of it.</p>
 */
@Entity
@Table(name = "staff_documents")
public class StaffDocument extends BaseEntity {

    /**
     * What the paper is.
     *
     * <p>A closed list because the office's question is countable — who has no PAN copy on
     * file — and a caption spelled four ways answers it four times. The note beside it carries
     * everything a list cannot say.</p>
     */
    public enum Type {
        AADHAAR, PAN, BANK, APPOINTMENT, EDUCATION, POLICE_VERIFICATION, PHOTOGRAPH, OTHER
    }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "attachment_id", nullable = false, updatable = false)
    private UUID attachmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 30)
    private Type docType = Type.OTHER;

    @Column(name = "note", length = 200)
    private String note;

    protected StaffDocument() {
    }

    public StaffDocument(UUID orgId, UUID userId, UUID attachmentId, Type docType, String note) {
        this.orgId = orgId;
        this.userId = userId;
        this.attachmentId = attachmentId;
        this.docType = docType;
        this.note = note;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getAttachmentId() {
        return attachmentId;
    }

    public Type getDocType() {
        return docType;
    }

    public String getNote() {
        return note;
    }
}

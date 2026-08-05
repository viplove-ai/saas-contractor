package in.nirman.modules.dpr.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A site photograph attached to the day's report.
 *
 * <p>The image itself lives in object storage behind the attachment module; this row is the
 * link and the caption. {@code uq_dpr_photo} means the same attachment cannot be linked twice,
 * which is what makes a retried upload over a bad connection harmless rather than duplicated.</p>
 */
@Entity
@Table(name = "dpr_photos")
public class DprPhoto {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "dpr_id", nullable = false, updatable = false)
    private UUID dprId;

    @Column(name = "attachment_id", nullable = false, updatable = false)
    private UUID attachmentId;

    @Column(name = "caption", length = 300)
    private String caption;

    /** When the photograph was taken, which is not when it was uploaded. */
    @Column(name = "taken_at")
    private Instant takenAt;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected DprPhoto() {
    }

    public DprPhoto(UUID dprId, UUID attachmentId, String caption, Instant takenAt, int sortOrder) {
        this.id = UUID.randomUUID();
        this.dprId = dprId;
        this.attachmentId = attachmentId;
        this.caption = caption;
        this.takenAt = takenAt;
        this.sortOrder = sortOrder;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDprId() {
        return dprId;
    }

    public UUID getAttachmentId() {
        return attachmentId;
    }

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }

    public Instant getTakenAt() {
        return takenAt;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}

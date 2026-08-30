package in.nirman.modules.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * One picture of one machine.
 *
 * <p>Many per machine, because one was the wrong number. A mixer is identified by its plate,
 * its condition is argued about from the state of its drum, and the crack in a breaker's jaw
 * is not in the same frame as the asset code stencilled on its side — and the office accepting
 * the entry is being asked to agree to all three at once.</p>
 *
 * <p>No caption and no type. The delivery's evidence carries one because a picture of the load
 * and a picture of the challan are two different claims that can disagree, and the
 * disagreement is the point; pictures of a machine are all the same kind of claim, and a
 * closed list here would be four words nobody chooses between.</p>
 *
 * <p>No {@code updated_at} and no version: a photograph is added or removed, never edited. The
 * row is the link and the file is in {@code attachments}.</p>
 */
@Entity
@Table(name = "site_equipment_photos")
@EntityListeners(AuditingEntityListener.class)
public class SiteEquipmentPhoto {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "equipment_id", nullable = false, updatable = false)
    private UUID equipmentId;

    @Column(name = "attachment_id", nullable = false, updatable = false)
    private UUID attachmentId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected SiteEquipmentPhoto() {
    }

    public SiteEquipmentPhoto(UUID orgId, UUID equipmentId, UUID attachmentId) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.equipmentId = equipmentId;
        this.attachmentId = attachmentId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getEquipmentId() {
        return equipmentId;
    }

    public UUID getAttachmentId() {
        return attachmentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }
}

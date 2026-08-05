package in.nirman.modules.attachment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata for one object in MinIO. The bytes never live in Postgres; the row is what ties
 * an object key to an owning record, a site (for access control) and an uploader.
 */
@Entity
@Table(name = "attachments")
@EntityListeners(AuditingEntityListener.class)
public class Attachment {

    public enum Kind { BILL, CHALLAN, PHOTO, DOCUMENT }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "site_id", updatable = false)
    private UUID siteId;

    @Column(name = "owner_entity_type", nullable = false, length = 40, updatable = false)
    private String ownerEntityType;

    /** Null until the parent record is saved; set once when the parent claims the upload. */
    @Column(name = "owner_entity_id")
    private UUID ownerEntityId;

    @Column(name = "file_name", nullable = false, length = 255, updatable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 120, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "checksum_sha256", columnDefinition = "char(64)", updatable = false)
    private String checksumSha256;

    @Column(name = "bucket", nullable = false, length = 80, updatable = false)
    private String bucket;

    @Column(name = "object_key", nullable = false, length = 400, unique = true, updatable = false)
    private String objectKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 30, updatable = false)
    private Kind kind = Kind.DOCUMENT;

    @CreatedDate
    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    @CreatedBy
    @Column(name = "uploaded_by", updatable = false)
    private UUID uploadedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Attachment() {
    }

    public Attachment(UUID orgId, UUID siteId, String ownerEntityType, String fileName,
                      String contentType, long sizeBytes, String checksumSha256, String bucket,
                      String objectKey, Kind kind) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.siteId = siteId;
        this.ownerEntityType = ownerEntityType;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.checksumSha256 = checksumSha256;
        this.bucket = bucket;
        this.objectKey = objectKey;
        this.kind = kind;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getSiteId() {
        return siteId;
    }

    public String getOwnerEntityType() {
        return ownerEntityType;
    }

    public UUID getOwnerEntityId() {
        return ownerEntityId;
    }

    public void attachTo(UUID ownerEntityId) {
        this.ownerEntityId = ownerEntityId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public String getBucket() {
        return bucket;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public Kind getKind() {
        return kind;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public UUID getUploadedBy() {
        return uploadedBy;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void softDelete(Instant now) {
        this.deletedAt = now;
    }
}

package in.nirman.modules.attachment.api.dto;

import in.nirman.modules.attachment.domain.Attachment;

import java.time.Instant;
import java.util.UUID;

public final class AttachmentDtos {

    private AttachmentDtos() {
    }

    public record AttachmentResponse(
            UUID id,
            UUID siteId,
            String ownerEntityType,
            UUID ownerEntityId,
            String fileName,
            String contentType,
            long sizeBytes,
            String checksumSha256,
            Attachment.Kind kind,
            Instant uploadedAt,
            UUID uploadedBy) {
    }

    public record SignedUrlResponse(
            UUID id,
            String url,
            long expiresInSeconds,
            String fileName,
            String contentType) {
    }
}

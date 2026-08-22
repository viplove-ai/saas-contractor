package in.nirman.modules.attachment;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        @NotBlank String endpoint,
        @NotBlank String accessKey,
        @NotBlank String secretKey,
        @NotBlank String bucket,
        @Min(1) int signedUrlMinutes,
        @Min(1) long maxFileSizeBytes,
        /**
         * The cap for {@code DOCUMENT} uploads, which are a different kind of thing from a
         * photograph of a challan.
         *
         * <p>A published schedule of rates is a thousand-page government PDF and routinely
         * runs to tens of megabytes; a bill photograph taken on a site phone does not. One
         * limit for both means either the schedule cannot be stored or the phone can upload a
         * hundred megabytes of camera roll over a site connection, and neither is what was
         * wanted.</p>
         */
        @Min(1) long maxDocumentSizeBytes,
        List<String> allowedContentTypes) {
}

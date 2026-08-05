package in.nirman.modules.attachment.api;

import in.nirman.modules.attachment.api.dto.AttachmentDtos.AttachmentResponse;
import in.nirman.modules.attachment.api.dto.AttachmentDtos.SignedUrlResponse;
import in.nirman.modules.attachment.domain.Attachment;
import in.nirman.modules.attachment.service.AttachmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1/attachments")
@Tag(name = "Attachments", description = "Bills, challans and photos in object storage")
public class AttachmentController {

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a file; link it to its record via ownerEntityType now and the parent save later")
    public ResponseEntity<AttachmentResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam @NotBlank @Pattern(regexp = "[A-Z_]{2,40}") String ownerEntityType,
            @RequestParam(required = false) UUID siteId,
            @RequestParam(defaultValue = "DOCUMENT") Attachment.Kind kind) {
        AttachmentResponse created = attachmentService.upload(file, ownerEntityType, siteId, kind);
        return ResponseEntity.created(URI.create("/api/v1/attachments/" + created.id()))
                .body(created);
    }

    @GetMapping("/{id}/url")
    @Operation(summary = "Short-lived signed download URL, issued after a fresh site-access check")
    public SignedUrlResponse signedUrl(@PathVariable UUID id) {
        return attachmentService.signedUrl(id);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove a draft upload (uploader only, before a record claims it)")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        attachmentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

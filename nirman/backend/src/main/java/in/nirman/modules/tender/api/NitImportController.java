package in.nirman.modules.tender.api;

import in.nirman.modules.tender.api.dto.NitDtos.CreateFromNitRequest;
import in.nirman.modules.tender.api.dto.NitDtos.NitDocumentResponse;
import in.nirman.modules.tender.api.dto.NitDtos.NitImportResponse;
import in.nirman.modules.tender.api.dto.NitDtos.NitPreviewResponse;
import in.nirman.modules.tender.service.NitImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.UUID;

/**
 * Reading a tender notice into a project.
 *
 * <p>Two calls rather than one: {@code preview} says what the PDF appears to contain and
 * writes nothing but the file itself, and the second call creates the project from what the
 * user confirmed. A government notice is not a structured document, and a parse good enough
 * to save unreviewed is not a parse that exists.</p>
 */
@RestController
@RequestMapping("/api/v1/nit-imports")
@Tag(name = "NIT import", description = "Reading a tender notice into a project and its BOQ")
public class NitImportController {

    private final NitImportService service;

    public NitImportController(NitImportService service) {
        this.service = service;
    }

    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Read a NIT PDF and return what it contains, saving no project data")
    public NitPreviewResponse preview(@RequestPart("file") MultipartFile file) {
        return service.preview(file);
    }

    @PostMapping
    @Operation(summary = "Create a project, its BOQ and its tender record from a reviewed NIT")
    public ResponseEntity<NitImportResponse> create(
            @Valid @RequestBody CreateFromNitRequest request) {
        NitImportResponse created = service.createFromNit(request);
        return ResponseEntity
                .created(URI.create("/api/v1/projects/" + created.project().id()))
                .body(created);
    }

    @GetMapping("/projects/{projectId}")
    @Operation(summary = "The tender a project was created from")
    public NitDocumentResponse forProject(@PathVariable UUID projectId) {
        return service.forProject(projectId);
    }
}

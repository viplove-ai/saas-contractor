package in.nirman.modules.billing.api;

import in.nirman.modules.billing.api.dto.VaultDtos.AgreementSuggestion;
import in.nirman.modules.billing.api.dto.VaultDtos.AttachRequest;
import in.nirman.modules.billing.api.dto.VaultDtos.DocumentRequest;
import in.nirman.modules.billing.api.dto.VaultDtos.DocumentResponse;
import in.nirman.modules.billing.api.dto.VaultDtos.SupersedeRequest;
import in.nirman.modules.billing.api.dto.VaultDtos.TenderDocumentRequest;
import in.nirman.modules.billing.api.dto.VaultDtos.TenderDocumentResponse;
import in.nirman.modules.billing.domain.ReferenceDocument;
import in.nirman.modules.billing.service.AgreementService;
import in.nirman.modules.billing.service.ReferenceDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * The vault: the editions a bill is prepared against, and which of them govern a tender.
 *
 * <p>Reading goes with reading a bill; writing is {@code dsr:manage}, which already exists for
 * exactly this custody. Deciding which edition governs a tender is deciding its rates, so a
 * second permission would hand out the same power under a different name.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Billing", description = "Measurement sheets and running account bills")
public class ReferenceDocumentController {

    private final ReferenceDocumentService documents;
    private final AgreementService agreements;

    public ReferenceDocumentController(ReferenceDocumentService documents,
                                       AgreementService agreements) {
        this.documents = documents;
        this.agreements = agreements;
    }

    // ------------------------------------------------------------------ the shelf

    @GetMapping("/reference-documents")
    @Operation(summary = "Schedules of rates, cost index circulars and specifications on the shelf")
    public List<DocumentResponse> list(@RequestParam(required = false) ReferenceDocument.Kind kind) {
        return documents.list(kind);
    }

    @PostMapping("/reference-documents")
    @Operation(summary = "Register an edition. The file may follow later")
    public ResponseEntity<DocumentResponse> create(@Valid @RequestBody DocumentRequest request) {
        DocumentResponse created = documents.create(request);
        return ResponseEntity.created(URI.create("/api/v1/reference-documents/" + created.id()))
                .body(created);
    }

    @GetMapping("/reference-documents/{id}")
    public DocumentResponse get(@PathVariable UUID id) {
        return documents.get(id);
    }

    @PutMapping("/reference-documents/{id}")
    public DocumentResponse update(@PathVariable UUID id,
                                   @Valid @RequestBody DocumentRequest request) {
        return documents.update(id, request);
    }

    @PostMapping("/reference-documents/{id}/attach")
    @Operation(summary = "Attach the uploaded file to an edition already registered")
    public DocumentResponse attach(@PathVariable UUID id, @Valid @RequestBody AttachRequest request) {
        return documents.attach(id, request.attachmentId());
    }

    @PostMapping("/reference-documents/{id}/supersede")
    @Operation(summary = "Mark an edition replaced. Moves nothing that already cites it")
    public DocumentResponse supersede(@PathVariable UUID id,
                                      @Valid @RequestBody SupersedeRequest request) {
        return documents.supersede(id, request.replacedBy());
    }

    @DeleteMapping("/reference-documents/{id}")
    @Operation(summary = "Withdraw an edition no tender is priced under")
    public ResponseEntity<Void> withdraw(@PathVariable UUID id) {
        documents.withdraw(id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ what governs a tender

    @GetMapping("/projects/{projectId}/agreement/documents")
    @Operation(summary = "The editions this tender was priced under")
    public List<TenderDocumentResponse> tenderDocuments(@PathVariable UUID projectId) {
        return agreements.documentsFor(projectId);
    }

    @PutMapping("/projects/{projectId}/agreement/documents")
    @Operation(summary = "Record which editions govern this tender")
    public List<TenderDocumentResponse> linkDocuments(
            @PathVariable UUID projectId,
            @Valid @RequestBody List<TenderDocumentRequest> requests) {
        return agreements.linkDocuments(projectId, requests);
    }

    @GetMapping("/projects/{projectId}/agreement/suggestion")
    @Operation(summary = "What the tender notice said the work would be priced under")
    public AgreementSuggestion suggestion(@PathVariable UUID projectId) {
        return agreements.suggestion(projectId);
    }
}

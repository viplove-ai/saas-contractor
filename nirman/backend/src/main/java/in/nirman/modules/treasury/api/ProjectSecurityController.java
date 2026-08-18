package in.nirman.modules.treasury.api;

import in.nirman.modules.treasury.api.dto.TreasuryDtos.CreateSecurityRequest;
import in.nirman.modules.treasury.api.dto.TreasuryDtos.ForfeitRequest;
import in.nirman.modules.treasury.api.dto.TreasuryDtos.LodgeRequest;
import in.nirman.modules.treasury.api.dto.TreasuryDtos.ProposalResponse;
import in.nirman.modules.treasury.api.dto.TreasuryDtos.RedeployRequest;
import in.nirman.modules.treasury.api.dto.TreasuryDtos.ReleaseRequest;
import in.nirman.modules.treasury.api.dto.TreasuryDtos.RetainedRequest;
import in.nirman.modules.treasury.api.dto.TreasuryDtos.SecurityResponse;
import in.nirman.modules.treasury.api.dto.TreasuryDtos.UpdateSecurityRequest;
import in.nirman.modules.treasury.service.ProjectSecurityService;
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
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * The deposits and guarantees lodged against a contract.
 *
 * <p>Lodging, releasing, redeploying and forfeiting are each their own endpoint rather than a
 * status field on the update: every one of them is a statement about where the company's money
 * went, and none should be reachable by typing a word into a form.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Securities", description = "Earnest money, guarantees and retentions held against contracts")
public class ProjectSecurityController {

    private final ProjectSecurityService securities;

    public ProjectSecurityController(ProjectSecurityService securities) {
        this.securities = securities;
    }

    @GetMapping("/projects/{projectId}/securities")
    @Operation(summary = "Every deposit recorded against one contract")
    public List<SecurityResponse> forProject(@PathVariable UUID projectId) {
        return securities.forProject(projectId);
    }

    @GetMapping("/projects/{projectId}/securities/proposal")
    @Operation(summary = "What the contract and its notice say each deposit ought to be")
    public List<ProposalResponse> proposal(@PathVariable UUID projectId) {
        return securities.proposeFor(projectId);
    }

    @PostMapping("/securities")
    public ResponseEntity<SecurityResponse> create(
            @Valid @RequestBody CreateSecurityRequest request) {
        SecurityResponse created = securities.create(request);
        return ResponseEntity.created(URI.create("/api/v1/securities/" + created.id()))
                .body(created);
    }

    @PutMapping("/securities/{id}")
    public SecurityResponse update(@PathVariable UUID id,
                                   @Valid @RequestBody UpdateSecurityRequest request) {
        return securities.update(id, request);
    }

    @PostMapping("/securities/{id}/lodge")
    @Operation(summary = "The deposit has gone out")
    public SecurityResponse lodge(@PathVariable UUID id, @Valid @RequestBody LodgeRequest request) {
        return securities.lodge(id, request);
    }

    @PostMapping("/securities/{id}/retained")
    @Operation(summary = "The running total withheld from bills to date")
    public SecurityResponse retained(@PathVariable UUID id,
                                     @Valid @RequestBody RetainedRequest request) {
        return securities.recordRetained(id, request);
    }

    @PostMapping("/securities/{id}/release")
    @Operation(summary = "The money has come back")
    public SecurityResponse release(@PathVariable UUID id,
                                    @Valid @RequestBody ReleaseRequest request) {
        return securities.release(id, request);
    }

    @PostMapping("/securities/{id}/redeploy")
    @Operation(summary = "Name the tender a released deposit went on to fund")
    public SecurityResponse redeploy(@PathVariable UUID id,
                                     @Valid @RequestBody RedeployRequest request) {
        return securities.redeploy(id, request);
    }

    @PostMapping("/securities/{id}/forfeit")
    @Operation(summary = "The department has kept it")
    public SecurityResponse forfeit(@PathVariable UUID id,
                                    @Valid @RequestBody ForfeitRequest request) {
        return securities.forfeit(id, request);
    }

    /** Only while nothing has happened to it — see {@code ProjectSecurityService#delete}. */
    @DeleteMapping("/securities/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        securities.delete(id);
        return ResponseEntity.noContent().build();
    }
}

package in.nirman.modules.project.api;

import in.nirman.modules.project.api.dto.ProjectDtos.CreateSiteRequest;
import in.nirman.modules.project.api.dto.ProjectDtos.CreateStoreRequest;
import in.nirman.modules.project.api.dto.ProjectDtos.DeleteRequest;
import in.nirman.modules.project.api.dto.ProjectDtos.SiteDirectoryEntry;
import in.nirman.modules.project.api.dto.ProjectDtos.SiteResponse;
import in.nirman.modules.project.api.dto.ProjectDtos.StoreResponse;
import in.nirman.modules.project.api.dto.ProjectDtos.UpdateSiteRequest;
import in.nirman.modules.project.service.SiteService;
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

@RestController
@RequestMapping("/api/v1/sites")
@Tag(name = "Sites", description = "Work locations and their stores; access is per-assignment")
public class SiteController {

    private final SiteService siteService;

    public SiteController(SiteService siteService) {
        this.siteService = siteService;
    }

    @GetMapping
    @Operation(summary = "List sites visible to the caller, optionally for one project; "
            + "deleted=true swaps in the deleted ones")
    public List<SiteResponse> list(@RequestParam(required = false) UUID projectId,
                                   @RequestParam(defaultValue = "false") boolean deleted) {
        return deleted ? siteService.listDeleted(projectId) : siteService.list(projectId);
    }

    @PostMapping
    public ResponseEntity<SiteResponse> create(@Valid @RequestBody CreateSiteRequest request) {
        SiteResponse created = siteService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/sites/" + created.id())).body(created);
    }

    @GetMapping("/directory")
    @Operation(summary = "Every site in the company by code and name, for naming a transfer "
            + "destination. Not narrowed to your assignments, and carries nothing but the name.")
    public List<SiteDirectoryEntry> directory() {
        return siteService.directory();
    }

    @GetMapping("/{id}")
    public SiteResponse get(@PathVariable UUID id) {
        return siteService.get(id);
    }

    @PutMapping("/{id}")
    public SiteResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateSiteRequest request) {
        return siteService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a site. Refused if anything has been recorded against it.")
    public SiteResponse delete(@PathVariable UUID id, @Valid @RequestBody DeleteRequest request) {
        return siteService.delete(id, request.reason());
    }

    @PostMapping("/{id}/restore")
    @Operation(summary = "Restore a deleted site, re-posting the staff named on it")
    public SiteResponse restore(@PathVariable UUID id) {
        return siteService.restore(id);
    }

    @GetMapping("/{id}/stores")
    public List<StoreResponse> listStores(@PathVariable UUID id) {
        return siteService.listStores(id);
    }

    @PostMapping("/{id}/stores")
    public ResponseEntity<StoreResponse> createStore(@PathVariable UUID id,
                                                     @Valid @RequestBody CreateStoreRequest request) {
        StoreResponse created = siteService.createStore(id, request);
        return ResponseEntity.created(
                URI.create("/api/v1/sites/" + id + "/stores/" + created.id())).body(created);
    }
}

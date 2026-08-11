package in.nirman.modules.project.api;

import in.nirman.modules.project.api.dto.ProjectDtos.StoreDirectoryEntry;
import in.nirman.modules.project.api.dto.ProjectDtos.StoreResponse;
import in.nirman.modules.project.api.dto.ProjectDtos.UpdateStoreRequest;
import in.nirman.modules.project.service.SiteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Stores as a register of their own, rather than as a tail on one site.
 *
 * <p>Creating one still goes to {@code POST /sites/{id}/stores} — a store is always inside a
 * site, and the path saying so is the point. What lives here is everything the Stores screen
 * needs and the site-scoped path cannot answer: the list across sites, and the two verbs a
 * screen that owns a register has to have.</p>
 */
@RestController
@RequestMapping("/api/v1/stores")
@Tag(name = "Stores", description = "Stock locations. Reached through their site's access fence.")
public class StoreController {

    private final SiteService siteService;

    public StoreController(SiteService siteService) {
        this.siteService = siteService;
    }

    @GetMapping
    @Operation(summary = "Stores at the sites the caller can reach, or at one named site")
    public List<StoreDirectoryEntry> list(@RequestParam(required = false) UUID siteId) {
        return siteService.listAllStores(siteId);
    }

    @PutMapping("/{id}")
    public StoreResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateStoreRequest request) {
        return siteService.updateStore(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a store. Refused if anything has ever been recorded against "
            + "it — mark it inactive instead.")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        siteService.deleteStore(id);
        return ResponseEntity.noContent().build();
    }
}

package in.nirman.modules.inventory.api;

import in.nirman.modules.inventory.api.dto.EquipmentDtos.CreateEquipmentRequest;
import in.nirman.modules.inventory.api.dto.EquipmentDtos.DecideEquipmentRequest;
import in.nirman.modules.inventory.api.dto.EquipmentDtos.EquipmentResponse;
import in.nirman.modules.inventory.api.dto.EquipmentDtos.AddEquipmentPhotosRequest;
import in.nirman.modules.inventory.api.dto.EquipmentDtos.UpdateEquipmentRequest;
import in.nirman.modules.inventory.domain.SiteEquipment;
import in.nirman.modules.inventory.service.SiteEquipmentService;
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
 * The plant held at a site.
 *
 * <p>Under {@code /inventory} because that is where a storekeeper looks for it — the machine
 * is in the same yard as the cement — while remaining nothing to do with the stock ledger:
 * equipment is held rather than consumed, and a posting would report a mixer as used up by a
 * slab.</p>
 */
@RestController
@RequestMapping("/api/v1/inventory/equipment")
@Tag(name = "Equipment", description = "Plant standing at a site. Entered at the site, "
        + "accepted by the office.")
public class EquipmentController {

    private final SiteEquipmentService equipment;

    public EquipmentController(SiteEquipmentService equipment) {
        this.equipment = equipment;
    }

    @GetMapping
    @Operation(summary = "The register, narrowed to a site or a store; pending entries included")
    public List<EquipmentResponse> list(@RequestParam(required = false) UUID siteId,
                                        @RequestParam(required = false) UUID storeId,
                                        @RequestParam(required = false) SiteEquipment.Status status) {
        return equipment.list(siteId, storeId, status);
    }

    @PostMapping
    @Operation(summary = "Enter a machine standing at the site",
            description = "Anybody posted to the site may. The entry waits as PENDING until "
                    + "an administrator accepts it — except an administrator's own, which "
                    + "has nobody left to check it. Idempotent on the client id.")
    public ResponseEntity<EquipmentResponse> create(
            @Valid @RequestBody CreateEquipmentRequest request) {
        EquipmentResponse created = equipment.create(request);
        return ResponseEntity.created(
                URI.create("/api/v1/inventory/equipment/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public EquipmentResponse get(@PathVariable UUID id) {
        return equipment.get(id);
    }

    @PostMapping("/{id}/decision")
    @Operation(summary = "Accept the machine onto the register, or say it is not there")
    public EquipmentResponse decide(@PathVariable UUID id,
                                    @Valid @RequestBody DecideEquipmentRequest request) {
        return equipment.decide(id, request);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Correct an entry",
            description = "The office on any row; the man who entered the machine on his own. "
                    + "A correction by the field puts the row back to PENDING and drops the "
                    + "earlier decision with it, so nothing reaches the register unread.")
    public EquipmentResponse update(@PathVariable UUID id,
                                    @Valid @RequestBody UpdateEquipmentRequest request) {
        return equipment.update(id, request);
    }

    @PostMapping("/{id}/photos")
    @Operation(summary = "Add pictures of the machine to its entry",
            description = "Upload the files to /attachments first, then send their ids here. "
                    + "A list, because somebody standing at the machine photographs the plate "
                    + "and the damage in one go. The office on any row; anybody posted to the "
                    + "site on any row standing at it — the photograph usually arrives on a "
                    + "later day than the entry. Adding to a row that already carries pictures "
                    + "re-opens it for acceptance; adding the first ones does not, because "
                    + "nothing the office read has changed.")
    public EquipmentResponse addPhotos(@PathVariable UUID id,
                                       @Valid @RequestBody AddEquipmentPhotosRequest request) {
        return equipment.addPhotos(id, request.attachmentIds());
    }

    @DeleteMapping("/{id}/photos/{photoId}")
    @Operation(summary = "Take one picture off the machine, and the file with it",
            description = "Named by the photograph's own row rather than by the file behind "
                    + "it, so nobody can unpick a file from a machine it never belonged to. "
                    + "Removing from the field always re-opens a decided row: taking evidence "
                    + "away from an entry the office accepted always changes what it agreed to.")
    public EquipmentResponse removePhoto(@PathVariable UUID id, @PathVariable UUID photoId) {
        return equipment.removePhoto(id, photoId);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Take a machine off the register")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        equipment.delete(id);
        return ResponseEntity.noContent().build();
    }
}

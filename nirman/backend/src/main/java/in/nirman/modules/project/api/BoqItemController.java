package in.nirman.modules.project.api;

import in.nirman.modules.project.api.dto.BoqDtos.BoqItemResponse;
import in.nirman.modules.project.api.dto.BoqDtos.CreateBoqItemRequest;
import in.nirman.modules.project.api.dto.BoqDtos.ProgressEntryResponse;
import in.nirman.modules.project.api.dto.BoqDtos.ProgressLedger;
import in.nirman.modules.project.api.dto.BoqDtos.RecordProgressRequest;
import in.nirman.modules.project.api.dto.BoqDtos.UpdateBoqItemRequest;
import in.nirman.modules.project.service.BoqItemService;
import in.nirman.modules.project.service.BoqProgressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/boq-items")
@Tag(name = "BOQ", description = "Contract work items, which labour, material and cash all charge to")
public class BoqItemController {

    private final BoqItemService service;
    private final BoqProgressService progress;

    public BoqItemController(BoqItemService service, BoqProgressService progress) {
        this.service = service;
        this.progress = progress;
    }

    @GetMapping
    @Operation(summary = "Work items on a project, optionally narrowed to a site or category")
    public List<BoqItemResponse> list(@RequestParam(required = false) UUID projectId,
                                      @RequestParam(required = false) UUID siteId,
                                      @RequestParam(required = false) String category) {
        return service.list(projectId, siteId, category);
    }

    @PostMapping
    public ResponseEntity<BoqItemResponse> create(@Valid @RequestBody CreateBoqItemRequest request) {
        BoqItemResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/boq-items/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public BoqItemResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public BoqItemResponse update(@PathVariable UUID id,
                                  @Valid @RequestBody UpdateBoqItemRequest request) {
        return service.update(id, request);
    }

    @GetMapping("/{id}/progress")
    @Operation(summary = "The measurement book for one line: every dated claim, and the total they come to")
    public ProgressLedger progress(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return progress.ledger(id, from, to);
    }

    @PostMapping("/{id}/progress")
    @Operation(summary = "Record a measurement. Negative corrects an earlier over-measurement; nothing is ever edited.")
    public ResponseEntity<ProgressEntryResponse> recordProgress(
            @PathVariable UUID id, @Valid @RequestBody RecordProgressRequest request) {
        return ResponseEntity.created(URI.create("/api/v1/boq-items/" + id + "/progress"))
                .body(progress.record(id, request));
    }
}

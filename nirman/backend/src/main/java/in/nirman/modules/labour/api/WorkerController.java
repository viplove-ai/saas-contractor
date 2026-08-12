package in.nirman.modules.labour.api;

import in.nirman.common.PageResponse;
import in.nirman.modules.labour.api.dto.WorkerDtos.AllocateRequest;
import in.nirman.modules.labour.api.dto.WorkerDtos.AllocationResponse;
import in.nirman.modules.labour.api.dto.WorkerDtos.CreateWorkerRequest;
import in.nirman.modules.labour.api.dto.WorkerDtos.DeleteWorkerRequest;
import in.nirman.modules.labour.api.dto.WorkerDtos.ReviseWageRequest;
import in.nirman.modules.labour.api.dto.WorkerDtos.UpdateWorkerRequest;
import in.nirman.modules.labour.api.dto.WorkerDtos.WageRateResponse;
import in.nirman.modules.labour.api.dto.WorkerDtos.WorkerResponse;
import in.nirman.modules.labour.service.WorkerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
@RequestMapping("/api/v1/workers")
@Tag(name = "Workers", description = "Worker master, wage history and site postings")
public class WorkerController {

    private final WorkerService workerService;

    public WorkerController(WorkerService workerService) {
        this.workerService = workerService;
    }

    @GetMapping
    @Operation(summary = "List workers; siteId filters by the posting open today")
    public PageResponse<WorkerResponse> list(@RequestParam(required = false) UUID siteId,
                                             @RequestParam(required = false) UUID contractorId,
                                             @RequestParam(required = false) UUID skillId,
                                             @RequestParam(required = false) Boolean active,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "25") int size) {
        return workerService.list(siteId, contractorId, skillId, active, q,
                PageRequest.of(page, Math.min(size, 200), Sort.by("workerCode")));
    }

    @PostMapping
    @Operation(summary = "Create a worker, optionally with his first wage rate and posting")
    public ResponseEntity<WorkerResponse> create(@Valid @RequestBody CreateWorkerRequest request) {
        WorkerResponse created = workerService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/workers/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public WorkerResponse get(@PathVariable UUID id) {
        return workerService.get(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Correct a worker's particulars. Pay is not here — that is a revision.")
    public WorkerResponse update(@PathVariable UUID id,
                                 @Valid @RequestBody UpdateWorkerRequest request) {
        return workerService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a worker. Refused if anything has been recorded against him.")
    public WorkerResponse delete(@PathVariable UUID id,
                                 @Valid @RequestBody DeleteWorkerRequest request) {
        return workerService.delete(id, request.reason());
    }

    @GetMapping("/{id}/wage-rates")
    @Operation(summary = "Full pay history, newest first")
    public List<WageRateResponse> wageHistory(@PathVariable UUID id) {
        return workerService.wageHistory(id);
    }

    @PostMapping("/{id}/wage-rates")
    @Operation(summary = "Revise pay: closes the open rate and opens a new one. Never edits history.")
    public ResponseEntity<WageRateResponse> reviseWage(@PathVariable UUID id,
                                                       @Valid @RequestBody ReviseWageRequest request) {
        WageRateResponse created = workerService.reviseWage(id, request);
        return ResponseEntity.created(
                URI.create("/api/v1/workers/" + id + "/wage-rates/" + created.id())).body(created);
    }

    @GetMapping("/{id}/allocations")
    public List<AllocationResponse> allocationHistory(@PathVariable UUID id) {
        return workerService.allocationHistory(id);
    }

    @PostMapping("/{id}/allocations")
    @Operation(summary = "Post the worker to a site, closing his previous posting the day before")
    public ResponseEntity<AllocationResponse> allocate(@PathVariable UUID id,
                                                       @Valid @RequestBody AllocateRequest request) {
        AllocationResponse created = workerService.allocate(id, request);
        return ResponseEntity.created(
                URI.create("/api/v1/workers/" + id + "/allocations/" + created.id())).body(created);
    }
}

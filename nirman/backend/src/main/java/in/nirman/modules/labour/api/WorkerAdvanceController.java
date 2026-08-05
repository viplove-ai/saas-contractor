package in.nirman.modules.labour.api;

import in.nirman.common.PageResponse;
import in.nirman.modules.labour.api.dto.AdvanceDtos.AdvanceResponse;
import in.nirman.modules.labour.api.dto.AdvanceDtos.ApproveAdvanceRequest;
import in.nirman.modules.labour.api.dto.AdvanceDtos.CreateAdvanceRequest;
import in.nirman.modules.labour.api.dto.AdvanceDtos.SettlementResponse;
import in.nirman.modules.labour.service.WorkerAdvanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Worker advances", description = "Advances against wages, the wage ledger and settlement")
public class WorkerAdvanceController {

    private final WorkerAdvanceService advanceService;

    public WorkerAdvanceController(WorkerAdvanceService advanceService) {
        this.advanceService = advanceService;
    }

    @GetMapping("/worker-advances")
    public PageResponse<AdvanceResponse> list(
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) UUID workerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return advanceService.list(siteId, workerId, from, to,
                PageRequest.of(page, Math.min(size, 200),
                        Sort.by(Sort.Direction.DESC, "advanceDate")));
    }

    @PostMapping("/worker-advances")
    @Operation(summary = "Record an advance. Idempotent on the client id; the number is server-assigned.")
    public ResponseEntity<AdvanceResponse> create(@Valid @RequestBody CreateAdvanceRequest request) {
        AdvanceResponse created = advanceService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/worker-advances/" + created.id()))
                .body(created);
    }

    @PostMapping("/worker-advances/{id}/decision")
    @Operation(summary = "Approve or reject. Approving a recoverable advance posts it to the wage ledger.")
    public AdvanceResponse decide(@PathVariable UUID id,
                                  @Valid @RequestBody ApproveAdvanceRequest request) {
        return advanceService.decide(id, request);
    }

    @GetMapping("/workers/{id}/settlement")
    @Operation(summary = "Earned minus advance equals payable, with the ledger lines behind it")
    public SettlementResponse settlement(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return advanceService.settlement(id, from, to);
    }
}

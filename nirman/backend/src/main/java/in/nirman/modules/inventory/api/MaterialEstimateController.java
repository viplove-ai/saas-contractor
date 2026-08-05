package in.nirman.modules.inventory.api;

import in.nirman.modules.inventory.api.dto.EstimateDtos.DeriveEstimateRequest;
import in.nirman.modules.inventory.api.dto.EstimateDtos.EstimateResponse;
import in.nirman.modules.inventory.api.dto.EstimateDtos.SaveEstimateRequest;
import in.nirman.modules.inventory.api.dto.EstimateDtos.VarianceReport;
import in.nirman.modules.inventory.domain.MaterialEstimate.Level;
import in.nirman.modules.inventory.service.MaterialEstimateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/material-estimates")
@Tag(name = "Material estimates",
        description = "What the work should take, and how that compares with what it took")
public class MaterialEstimateController {

    private final MaterialEstimateService service;

    public MaterialEstimateController(MaterialEstimateService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Live estimates on a project; superseded revisions stay out of the list")
    public List<EstimateResponse> list(@RequestParam UUID projectId,
                                       @RequestParam(required = false) UUID materialId,
                                       @RequestParam(required = false) Level level) {
        return service.list(projectId, materialId, level);
    }

    @PostMapping
    @Operation(summary = "Record an estimate. A repeat for the same scope and level supersedes it.")
    public ResponseEntity<EstimateResponse> save(@Valid @RequestBody SaveEstimateRequest request) {
        EstimateResponse saved = service.save(request);
        return ResponseEntity.created(
                URI.create("/api/v1/material-estimates/" + saved.id())).body(saved);
    }

    @PostMapping("/derive")
    @Operation(summary = "Derive an estimate from a consumption norm and the BOQ line's quantity")
    public ResponseEntity<EstimateResponse> derive(
            @Valid @RequestBody DeriveEstimateRequest request) {
        EstimateResponse saved = service.derive(request);
        return ResponseEntity.created(
                URI.create("/api/v1/material-estimates/" + saved.id())).body(saved);
    }

    @GetMapping("/variance")
    @Operation(summary = "Estimated versus actual, measured only over the BOQ scope the estimate covers")
    public VarianceReport variance(@RequestParam UUID projectId,
                                   @RequestParam Level level,
                                   @RequestParam(required = false) UUID materialId) {
        return service.variance(projectId, level, materialId);
    }
}

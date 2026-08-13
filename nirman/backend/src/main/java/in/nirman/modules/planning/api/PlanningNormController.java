package in.nirman.modules.planning.api;

import in.nirman.modules.planning.api.dto.PlanningDtos.LeadTimeResponse;
import in.nirman.modules.planning.api.dto.PlanningDtos.ProductivityNormResponse;
import in.nirman.modules.planning.api.dto.PlanningDtos.ReviseLeadTimeRequest;
import in.nirman.modules.planning.api.dto.PlanningDtos.ReviseProductivityNormRequest;
import in.nirman.modules.planning.api.dto.PlanningDtos.ReviseSequenceNormRequest;
import in.nirman.modules.planning.api.dto.PlanningDtos.SequenceNormResponse;
import in.nirman.modules.planning.api.dto.PlanningDtos.WorkTypeProfileResponse;
import in.nirman.modules.planning.service.PlanningNormService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The norms a construction programme is derived from.
 *
 * <p>Read by anyone who is asked how long the work will take or what it will cost; written only
 * by an administrator, because a norm quietly changed is every future plan quietly changed.</p>
 *
 * <p>Corrections are {@code PATCH} and carry a {@code version}, so two people editing the same
 * catalogue produce a 409 rather than one silently overwriting the other.</p>
 */
@RestController
@RequestMapping("/api/v1/planning/norms")
@Tag(name = "Planning norms",
        description = "Productivity, sequencing and lead-time norms, and the work type profiles")
public class PlanningNormController {

    private final PlanningNormService norms;

    public PlanningNormController(PlanningNormService norms) {
        this.norms = norms;
    }

    @GetMapping("/work-type-profiles")
    @Operation(summary = "The kinds of work, and the planning defaults each carries")
    public List<WorkTypeProfileResponse> workTypeProfiles() {
        return norms.workTypeProfiles();
    }

    @GetMapping("/productivity")
    @Operation(summary = "Man-days of each trade per unit of work, by category")
    public List<ProductivityNormResponse> productivity() {
        return norms.productivityNorms();
    }

    @PatchMapping("/productivity/{id}")
    @Operation(summary = "Correct a productivity figure against your own site records")
    public ProductivityNormResponse reviseProductivity(
            @PathVariable UUID id, @Valid @RequestBody ReviseProductivityNormRequest request) {
        return norms.reviseProductivityNorm(id, request);
    }

    @GetMapping("/sequence")
    @Operation(summary = "What precedes what, how far the two may overlap, and the crew cap")
    public List<SequenceNormResponse> sequence() {
        return norms.sequenceNorms();
    }

    @PatchMapping("/sequence/{id}")
    @Operation(summary = "Correct a sequencing rule or the working-front limit")
    public SequenceNormResponse reviseSequence(
            @PathVariable UUID id, @Valid @RequestBody ReviseSequenceNormRequest request) {
        return norms.reviseSequenceNorm(id, request);
    }

    @GetMapping("/lead-times")
    @Operation(summary = "How far ahead each material must be ordered, and how long it keeps")
    public List<LeadTimeResponse> leadTimes() {
        return norms.leadTimes();
    }

    @PatchMapping("/lead-times/{id}")
    @Operation(summary = "Correct a lead time against your own suppliers")
    public LeadTimeResponse reviseLeadTime(
            @PathVariable UUID id, @Valid @RequestBody ReviseLeadTimeRequest request) {
        return norms.reviseLeadTime(id, request);
    }
}

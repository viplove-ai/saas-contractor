package in.nirman.modules.planning.api;

import in.nirman.modules.planning.api.dto.PlanDtos.GeneratePlanRequest;
import in.nirman.modules.planning.api.dto.PlanDtos.PlanResponse;
import in.nirman.modules.planning.service.ExecutionPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Planning and execution strategy for a project.
 *
 * <p>Three calls of deliberately different weight. {@code preview} runs the engine and keeps
 * nothing, so a scenario costs a request and no rows. {@code plans} keeps a draft.
 * {@code baseline} is the irreversible act: it freezes the figures the project is measured
 * against and stamps the planned dates onto the schedule of quantities.</p>
 */
@RestController
@RequestMapping("/api/v1/planning")
@Tag(name = "Execution plan",
        description = "Phasing, labour, procurement and cash flow for a project")
public class ExecutionPlanController {

    private final ExecutionPlanService plans;

    public ExecutionPlanController(ExecutionPlanService plans) {
        this.plans = plans;
    }

    @PostMapping("/projects/{projectId}/preview")
    @Operation(summary = "Generate a plan without storing it — the scenario call")
    public PlanResponse preview(@PathVariable UUID projectId,
                                @Valid @RequestBody GeneratePlanRequest request) {
        return plans.preview(projectId, request);
    }

    @PostMapping("/projects/{projectId}/plans")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Keep a generated plan as a draft")
    public PlanResponse create(@PathVariable UUID projectId,
                               @Valid @RequestBody GeneratePlanRequest request) {
        return plans.create(projectId, request);
    }

    @PostMapping("/plans/{planId}/baseline")
    @Operation(summary = "Freeze a plan as the baseline, and date the schedule of quantities")
    public PlanResponse baseline(@PathVariable UUID planId) {
        return plans.baseline(planId);
    }

    @GetMapping("/projects/{projectId}/plan")
    @Operation(summary = "The live baseline this project runs under")
    public PlanResponse forProject(@PathVariable UUID projectId) {
        return plans.forProject(projectId);
    }
}

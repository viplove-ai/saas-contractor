package in.nirman.modules.project.api;

import in.nirman.common.PageResponse;
import in.nirman.modules.project.api.dto.ProjectDtos.CreateProjectRequest;
import in.nirman.modules.project.api.dto.ProjectDtos.ProjectResponse;
import in.nirman.modules.project.api.dto.ProjectDtos.ProjectSummaryResponse;
import in.nirman.modules.project.api.dto.ProjectDtos.UpdateProjectRequest;
import in.nirman.modules.project.domain.Project;
import in.nirman.modules.project.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
@Tag(name = "Projects", description = "Contracts under execution; scoped to assigned sites for field roles")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    @Operation(summary = "List projects visible to the caller")
    public PageResponse<ProjectResponse> list(@RequestParam(required = false) Project.Status status,
                                              @RequestParam(required = false) String q,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "25") int size) {
        return projectService.list(status, q,
                PageRequest.of(page, Math.min(size, 100), Sort.by("code")));
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody CreateProjectRequest request) {
        ProjectResponse created = projectService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public ProjectResponse get(@PathVariable UUID id) {
        return projectService.get(id);
    }

    @PutMapping("/{id}")
    public ProjectResponse update(@PathVariable UUID id,
                                  @Valid @RequestBody UpdateProjectRequest request) {
        return projectService.update(id, request);
    }

    @GetMapping("/{id}/summary")
    @Operation(summary = "Headline figures and site/store counts for one project")
    public ProjectSummaryResponse summary(@PathVariable UUID id) {
        return projectService.summary(id);
    }
}

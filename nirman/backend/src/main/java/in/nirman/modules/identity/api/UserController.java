package in.nirman.modules.identity.api;

import in.nirman.common.PageResponse;
import in.nirman.modules.identity.api.dto.UserDtos.AssignRolesRequest;
import in.nirman.modules.identity.api.dto.UserDtos.AssignSitesRequest;
import in.nirman.modules.identity.api.dto.UserDtos.CreateUserRequest;
import in.nirman.modules.identity.api.dto.UserDtos.ResetPasswordRequest;
import in.nirman.modules.identity.api.dto.UserDtos.SiteAssignmentResponse;
import in.nirman.modules.identity.api.dto.UserDtos.UpdateUserRequest;
import in.nirman.modules.identity.api.dto.UserDtos.UpdateUserStatusRequest;
import in.nirman.modules.identity.api.dto.UserDtos.UserResponse;
import in.nirman.modules.identity.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "User administration: accounts, roles, site assignments")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "List users, filterable by free text, active flag and role code")
    public PageResponse<UserResponse> list(@RequestParam(required = false) String q,
                                           @RequestParam(required = false) Boolean active,
                                           @RequestParam(required = false) String role,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "25") int size) {
        return userService.list(q, active, role,
                PageRequest.of(page, Math.min(size, 100), Sort.by("username")));
    }

    @PostMapping
    @Operation(summary = "Create a user with a temporary password (must change on first login)")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        UserResponse created = userService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/users/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable UUID id) {
        return userService.get(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update profile fields; requires the current version")
    public UserResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Activate or deactivate; deactivation revokes all refresh tokens")
    public UserResponse updateStatus(@PathVariable UUID id,
                                     @Valid @RequestBody UpdateUserStatusRequest request) {
        return userService.updateStatus(id, request.active());
    }

    @PostMapping("/{id}/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Set a temporary password; the user must change it at next sign-in "
            + "and every open session is revoked")
    public void resetPassword(@PathVariable UUID id,
                              @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request);
    }

    @GetMapping("/{id}/roles")
    public List<String> getRoles(@PathVariable UUID id) {
        return userService.getRoles(id);
    }

    @PutMapping("/{id}/roles")
    public UserResponse putRoles(@PathVariable UUID id, @Valid @RequestBody AssignRolesRequest request) {
        return userService.putRoles(id, request);
    }

    @GetMapping("/{id}/sites")
    public List<SiteAssignmentResponse> getSites(@PathVariable UUID id) {
        return userService.getSites(id);
    }

    @PutMapping("/{id}/sites")
    @Operation(summary = "Replace the active site set; removed assignments are closed, not deleted")
    public List<SiteAssignmentResponse> putSites(@PathVariable UUID id,
                                                 @Valid @RequestBody AssignSitesRequest request) {
        return userService.putSites(id, request);
    }
}

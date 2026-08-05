package in.nirman.modules.identity.api;

import in.nirman.modules.identity.api.dto.UserDtos.PermissionResponse;
import in.nirman.modules.identity.api.dto.UserDtos.RoleResponse;
import in.nirman.modules.identity.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Roles", description = "Role and permission catalogue (read-only; changes come by migration)")
public class RoleController {

    private final UserService userService;

    public RoleController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/roles")
    @Operation(summary = "The four system roles")
    public List<RoleResponse> roles() {
        return userService.listRoles();
    }

    @GetMapping("/permissions")
    @Operation(summary = "Every grantable permission code")
    public List<PermissionResponse> permissions() {
        return userService.listPermissions();
    }
}

package in.nirman.modules.identity.api;

import in.nirman.modules.identity.api.dto.AuthDtos.ChangePasswordRequest;
import in.nirman.modules.identity.api.dto.AuthDtos.LoginRequest;
import in.nirman.modules.identity.api.dto.AuthDtos.MeResponse;
import in.nirman.modules.identity.api.dto.AuthDtos.RefreshRequest;
import in.nirman.modules.identity.api.dto.AuthDtos.TokenResponse;
import in.nirman.modules.identity.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Login, token refresh with rotation, logout, password change")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange username and password for an access and refresh token pair")
    public TokenResponse login(@Valid @RequestBody LoginRequest request,
                               HttpServletRequest http) {
        return authService.login(request.username(), request.password(),
                http.getHeader("User-Agent"), clientIp(http));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate the refresh token; reusing a rotated token revokes its family")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request,
                                 HttpServletRequest http) {
        return authService.refresh(request.refreshToken(),
                http.getHeader("User-Agent"), clientIp(http));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke the presented refresh token's whole family")
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
    }

    @PostMapping("/password/change")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Change the caller's password; revokes every open session")
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request.currentPassword(), request.newPassword());
    }

    @GetMapping("/me")
    @Operation(summary = "Profile, roles, permissions and assigned sites of the caller")
    public MeResponse me() {
        return authService.me();
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

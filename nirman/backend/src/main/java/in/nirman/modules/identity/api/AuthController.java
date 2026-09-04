package in.nirman.modules.identity.api;

import in.nirman.modules.identity.api.dto.AuthDtos.ChangePasswordRequest;
import in.nirman.modules.identity.api.dto.AuthDtos.LoginRequest;
import in.nirman.modules.identity.api.dto.AuthDtos.MeResponse;
import in.nirman.modules.identity.api.dto.AuthDtos.RefreshRequest;
import in.nirman.modules.identity.api.dto.AuthDtos.SetSignatureRequest;
import in.nirman.modules.identity.api.dto.AuthDtos.TokenResponse;
import in.nirman.modules.identity.service.AuthService;
import in.nirman.modules.identity.service.UserSignatureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final UserSignatureService signatures;

    public AuthController(AuthService authService, UserSignatureService signatures) {
        this.authService = authService;
        this.signatures = signatures;
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

    /*
      The caller's own signature, and nobody else's: the path carries no user id on purpose.
      An administrator uploading a signature for a supervisor could put that supervisor's name
      on a report he never saw, which is what a signature exists to rule out.
    */
    @PutMapping("/me/signature")
    @Operation(summary = "Put an uploaded picture on the caller's account as his signature")
    public MeResponse setSignature(@Valid @RequestBody SetSignatureRequest request) {
        return authService.meFor(signatures.set(request.attachmentId()));
    }

    @DeleteMapping("/me/signature")
    @Operation(summary = "Take the caller's signature off his account")
    public MeResponse clearSignature() {
        return authService.meFor(signatures.clear());
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

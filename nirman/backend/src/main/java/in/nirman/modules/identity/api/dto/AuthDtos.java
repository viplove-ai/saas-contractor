package in.nirman.modules.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for the /auth endpoints, grouped because they travel together. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank @Size(max = 60) String username,
            @NotBlank @Size(max = 72) String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    /** BCrypt reads at most 72 bytes, so longer passwords would silently truncate. */
    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 72) String newPassword) {
    }

    public record TokenResponse(
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            String refreshToken,
            MeResponse user) {
    }

    /**
     * Assigned sites travel as ids only: site names belong to the project module, and the
     * client already loads GET /sites. {@code allSites} true means the role is not
     * site-scoped (admin, accountant) and {@code siteIds} is empty.
     */
    public record MeResponse(
            UUID id,
            String username,
            String fullName,
            String email,
            String mobile,
            boolean mustChangePassword,
            Instant lastLoginAt,
            List<String> roles,
            List<String> permissions,
            List<UUID> siteIds,
            boolean allSites) {
    }
}

package in.nirman.modules.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for user administration. */
public final class UserDtos {

    private UserDtos() {
    }

    public record UserResponse(
            UUID id,
            String username,
            String fullName,
            String email,
            String mobile,
            boolean active,
            boolean mustChangePassword,
            Instant lastLoginAt,
            List<String> roles,
            List<UUID> siteIds,
            Long version) {
    }

    public record CreateUserRequest(
            @NotBlank @Size(max = 60) @Pattern(regexp = "[a-z0-9._-]+",
                    message = "lowercase letters, digits, dot, underscore and hyphen only")
            String username,
            @NotBlank @Size(max = 150) String fullName,
            @Email @Size(max = 150) String email,
            // Required: a member who has forgotten their password is reached by phone, and
            // email is optional because most site staff do not have one.
            @NotBlank(message = "A mobile number is required") @Size(max = 20) String mobile,
            @NotBlank @Size(min = 8, max = 72) String temporaryPassword,
            @NotEmpty List<String> roleCodes,
            List<UUID> siteIds) {
    }

    public record UpdateUserRequest(
            @NotBlank @Size(max = 150) String fullName,
            @Email @Size(max = 150) String email,
            @NotBlank(message = "A mobile number is required") @Size(max = 20) String mobile,
            @NotNull Long version) {
    }

    public record UpdateUserStatusRequest(@NotNull Boolean active) {
    }

    /**
     * An admin-set password. The user must change it at next sign-in, so it is a handover
     * value, not a credential the admin is meant to keep: the response never echoes it.
     */
    public record ResetPasswordRequest(@NotBlank @Size(min = 8, max = 72) String temporaryPassword) {
    }

    public record AssignRolesRequest(@NotEmpty List<String> roleCodes) {
    }

    /** Replaces the user's site set: omitted sites are closed today, new ones open today. */
    public record AssignSitesRequest(@NotNull List<UUID> siteIds) {
    }

    public record SiteAssignmentResponse(
            UUID siteId,
            LocalDate assignedFrom,
            LocalDate assignedTo,
            boolean primary) {
    }

    public record RoleResponse(String code, String name, String description) {
    }

    public record PermissionResponse(String code, String module, String description) {
    }
}

package in.nirman.security;

import in.nirman.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/** Reads the {@link AuthenticatedUser} the JWT filter placed in the security context. */
@Component
public class CurrentUserProviderImpl implements CurrentUserProvider {

    @Override
    public UUID currentUserIdOrNull() {
        AuthenticatedUser user = principalOrNull();
        return user == null ? null : user.userId();
    }

    @Override
    public UUID currentOrgId() {
        return required().orgId();
    }

    @Override
    public Set<UUID> assignedSiteIds() {
        return required().siteIds();
    }

    @Override
    public Set<String> roles() {
        return required().roles();
    }

    @Override
    public boolean seesAllSites() {
        return required().allSites();
    }

    @Override
    public boolean isAdmin() {
        return required().isAdmin();
    }

    @Override
    public boolean hasPermission(String permissionCode) {
        AuthenticatedUser user = principalOrNull();
        return user != null && user.hasPermission(permissionCode);
    }

    public AuthenticatedUser required() {
        AuthenticatedUser user = principalOrNull();
        if (user == null) {
            throw new BusinessException("auth.required", "Sign in again to continue.",
                    HttpStatus.UNAUTHORIZED);
        }
        return user;
    }

    private AuthenticatedUser principalOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user;
        }
        return null;
    }
}

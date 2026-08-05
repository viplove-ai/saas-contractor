package in.nirman.modules.identity.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.identity.api.dto.AuthDtos.MeResponse;
import in.nirman.modules.identity.api.dto.AuthDtos.TokenResponse;
import in.nirman.modules.identity.domain.RefreshToken;
import in.nirman.modules.identity.domain.Role;
import in.nirman.modules.identity.domain.User;
import in.nirman.modules.identity.repository.RefreshTokenRepository;
import in.nirman.modules.identity.repository.UserRepository;
import in.nirman.modules.identity.repository.UserSiteAssignmentRepository;
import in.nirman.security.AuthenticatedUser;
import in.nirman.security.CurrentUserProviderImpl;
import in.nirman.security.JwtProperties;
import in.nirman.security.JwtTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Login, refresh rotation, logout and password change.
 *
 * <p><b>Deliberately not class-level @Transactional.</b> Several paths must persist state
 * and then fail the request — a failed-login counter, a revoked token family after reuse.
 * Inside one transaction the throw would roll back exactly the write that matters, so each
 * repository call here commits on its own and the 401 is raised afterwards.</p>
 *
 * <p>Every credential failure returns the same message and status: the API must not reveal
 * whether a username exists, is locked, or is disabled.</p>
 */
@Service
public class AuthService {

    /**
     * Roles whose data visibility is company-wide rather than per-assignment: their token
     * carries the {@code ALL} sites claim. The accountant is here because the permission
     * matrix grants them read (Y, not A) across sites — their writes are still fenced by
     * permission codes.
     */
    static final Set<String> ALL_SITE_ROLES = Set.of("ADMIN", "ACCOUNTANT");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ENTITY_USER = "USER";

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final UserSiteAssignmentRepository siteAssignments;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final JwtProperties jwtProperties;
    private final AuditService audit;
    private final CurrentUserProviderImpl currentUser;
    private final int maxFailedLogins;
    private final int lockoutMinutes;

    public AuthService(UserRepository users,
                       RefreshTokenRepository refreshTokens,
                       UserSiteAssignmentRepository siteAssignments,
                       PasswordEncoder passwordEncoder,
                       JwtTokenService jwtTokenService,
                       JwtProperties jwtProperties,
                       AuditService audit,
                       CurrentUserProviderImpl currentUser,
                       @Value("${app.security.max-failed-logins}") int maxFailedLogins,
                       @Value("${app.security.lockout-minutes}") int lockoutMinutes) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.siteAssignments = siteAssignments;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.jwtProperties = jwtProperties;
        this.audit = audit;
        this.currentUser = currentUser;
        this.maxFailedLogins = maxFailedLogins;
        this.lockoutMinutes = lockoutMinutes;
    }

    public TokenResponse login(String username, String password, String userAgent, String ipAddress) {
        Instant now = Instant.now();
        User user = users.findByUsernameIgnoreCase(username).orElse(null);

        if (user == null) {
            audit.recordUnauthenticated(null, null, username, ENTITY_USER, null,
                    "LOGIN_FAILED", "unknown username");
            throw invalidCredentials();
        }
        if (!user.isActive() || user.isLockedAt(now)) {
            audit.recordUnauthenticated(user.getOrgId(), user.getId(), username, ENTITY_USER,
                    user.getId(), "LOGIN_FAILED", user.isActive() ? "account locked" : "account disabled");
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            user.registerFailedLogin(maxFailedLogins, now.plus(Duration.ofMinutes(lockoutMinutes)));
            boolean nowLocked = user.isLockedAt(now);
            users.save(user);   // commits on its own; the 401 below must not roll it back
            audit.recordUnauthenticated(user.getOrgId(), user.getId(), username, ENTITY_USER,
                    user.getId(), nowLocked ? "ACCOUNT_LOCKED" : "LOGIN_FAILED",
                    nowLocked ? "locked after " + maxFailedLogins + " failed attempts" : "wrong password");
            throw invalidCredentials();
        }

        user.registerSuccessfulLogin(now);
        users.save(user);

        AuthenticatedUser principal = buildPrincipal(user);
        String rawRefreshToken = issueRefreshToken(user, UUID.randomUUID(), now, userAgent, ipAddress);
        audit.recordUnauthenticated(user.getOrgId(), user.getId(), username, ENTITY_USER,
                user.getId(), "LOGIN", null);

        return tokenResponse(principal, rawRefreshToken, user);
    }

    public TokenResponse refresh(String rawRefreshToken, String userAgent, String ipAddress) {
        Instant now = Instant.now();
        RefreshToken presented = refreshTokens.findByTokenHash(sha256(rawRefreshToken)).orElse(null);
        if (presented == null) {
            throw sessionExpired();
        }

        User user = users.findById(presented.getUserId()).orElse(null);

        if (presented.isRevoked()) {
            // A rotated token came back: either a very stale client or a stolen token.
            // Kill the whole family so neither party can continue, and leave a loud trail.
            refreshTokens.revokeFamily(presented.getFamilyId(), now, "REUSE_DETECTED");
            audit.recordUnauthenticated(user == null ? null : user.getOrgId(),
                    presented.getUserId(), user == null ? null : user.getUsername(),
                    ENTITY_USER, presented.getUserId(), "TOKEN_REUSE",
                    "rotated refresh token presented again; family revoked");
            throw sessionExpired();
        }
        if (presented.isExpiredAt(now) || user == null || !user.isActive()) {
            refreshTokens.revokeFamily(presented.getFamilyId(), now,
                    presented.isExpiredAt(now) ? "EXPIRED" : "USER_INACTIVE");
            throw sessionExpired();
        }

        presented.revoke(now, "ROTATED");
        refreshTokens.save(presented);
        String newRawToken = issueRefreshToken(user, presented.getFamilyId(), now, userAgent, ipAddress);
        AuthenticatedUser principal = buildPrincipal(user);
        return tokenResponse(principal, newRawToken, user);
    }

    public void logout(String rawRefreshToken) {
        Instant now = Instant.now();
        refreshTokens.findByTokenHash(sha256(rawRefreshToken)).ifPresent(token -> {
            refreshTokens.revokeFamily(token.getFamilyId(), now, "LOGOUT");
            audit.record(ENTITY_USER, token.getUserId(), "LOGOUT", null, null, null);
        });
        // An unknown token still returns 204: logout is idempotent and reveals nothing.
    }

    @Transactional
    public void changePassword(String currentPassword, String newPassword) {
        AuthenticatedUser principal = currentUser.required();
        User user = users.findById(principal.userId())
                .orElseThrow(() -> BusinessException.notFound("User", principal.userId()));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException("auth.wrong-password", "The current password is incorrect.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        user.changePassword(passwordEncoder.encode(newPassword), false);
        refreshTokens.revokeAllForUser(user.getId(), Instant.now(), "PASSWORD_CHANGE");
        audit.record(ENTITY_USER, user.getId(), "PASSWORD_CHANGED", null, null, null);
    }

    @Transactional(readOnly = true)
    public MeResponse me() {
        AuthenticatedUser principal = currentUser.required();
        User user = users.findById(principal.userId())
                .orElseThrow(() -> BusinessException.notFound("User", principal.userId()));
        return meResponse(user, buildPrincipal(user));
    }

    // ------------------------------------------------------------------ internals

    /** Works on a detached user: roles and permissions are eager, sites are queried fresh. */
    AuthenticatedUser buildPrincipal(User user) {
        Set<String> roleCodes = user.getRoles().stream()
                .map(Role::getCode).collect(Collectors.toUnmodifiableSet());
        Set<String> permissions = user.getRoles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(p -> p.getCode())
                .collect(Collectors.toUnmodifiableSet());
        boolean allSites = roleCodes.stream().anyMatch(ALL_SITE_ROLES::contains);
        Set<UUID> siteIds = allSites ? Set.of() : activeSiteIds(user.getId());
        return new AuthenticatedUser(user.getId(), user.getOrgId(), user.getUsername(),
                roleCodes, permissions, siteIds, allSites);
    }

    private Set<UUID> activeSiteIds(UUID userId) {
        LocalDate today = LocalDate.now();
        return siteAssignments.findByUserId(userId).stream()
                .filter(a -> a.isActiveOn(today))
                .map(a -> a.getSiteId())
                .collect(Collectors.toUnmodifiableSet());
    }

    private String issueRefreshToken(User user, UUID familyId, Instant now,
                                     String userAgent, String ipAddress) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires = now.plus(Duration.ofDays(jwtProperties.refreshTokenDays()));
        refreshTokens.save(new RefreshToken(user.getId(), sha256(raw), familyId, now, expires,
                truncate(userAgent, 300), ipAddress));
        return raw;
    }

    private TokenResponse tokenResponse(AuthenticatedUser principal, String rawRefreshToken, User user) {
        return new TokenResponse(
                jwtTokenService.createAccessToken(principal),
                "Bearer",
                jwtTokenService.accessTokenSeconds(),
                rawRefreshToken,
                meResponse(user, principal));
    }

    private MeResponse meResponse(User user, AuthenticatedUser principal) {
        return new MeResponse(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getEmail(),
                user.getMobile(),
                user.isMustChangePassword(),
                user.getLastLoginAt(),
                principal.roles().stream().sorted().toList(),
                principal.permissions().stream().sorted().toList(),
                principal.siteIds().stream().sorted().toList(),
                principal.allSites());
    }

    private static BusinessException invalidCredentials() {
        return new BusinessException("auth.invalid-credentials",
                "The username or password is incorrect.", HttpStatus.UNAUTHORIZED);
    }

    private static BusinessException sessionExpired() {
        return new BusinessException("auth.session-expired",
                "Your session has expired. Sign in again.", HttpStatus.UNAUTHORIZED);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String truncate(String value, int max) {
        return value != null && value.length() > max ? value.substring(0, max) : value;
    }
}

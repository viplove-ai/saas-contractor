package in.nirman.modules.identity.service;

import in.nirman.common.BusinessException;
import in.nirman.common.PageResponse;
import in.nirman.common.SitePostingGuard;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.identity.api.dto.UserDtos.AssignRolesRequest;
import in.nirman.modules.identity.api.dto.UserDtos.AssignSitesRequest;
import in.nirman.modules.identity.api.dto.UserDtos.CreateUserRequest;
import in.nirman.modules.identity.api.dto.UserDtos.PermissionResponse;
import in.nirman.modules.identity.api.dto.UserDtos.ResetPasswordRequest;
import in.nirman.modules.identity.api.dto.UserDtos.RoleResponse;
import in.nirman.modules.identity.api.dto.UserDtos.SiteAssignmentResponse;
import in.nirman.modules.identity.api.dto.UserDtos.UpdateUserRequest;
import in.nirman.modules.identity.api.dto.UserDtos.UserResponse;
import in.nirman.modules.identity.domain.Role;
import in.nirman.modules.identity.domain.User;
import in.nirman.modules.identity.domain.UserSiteAssignment;
import in.nirman.modules.identity.mapper.UserMapper;
import in.nirman.modules.identity.repository.PermissionRepository;
import in.nirman.modules.identity.repository.RefreshTokenRepository;
import in.nirman.modules.identity.repository.RoleRepository;
import in.nirman.modules.identity.repository.UserRepository;
import in.nirman.modules.identity.repository.UserSiteAssignmentRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Administration of logins, their roles and their site assignments. Admin-only by matrix. */
@Service
@Transactional
public class UserService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final UserSiteAssignmentRepository assignments;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;
    private final UserMapper mapper;
    private final SitePostingGuard postings;

    public UserService(UserRepository users, RoleRepository roles,
                       PermissionRepository permissions,
                       UserSiteAssignmentRepository assignments,
                       RefreshTokenRepository refreshTokens,
                       PasswordEncoder passwordEncoder,
                       CurrentUserProvider currentUser,
                       AuditService audit,
                       UserMapper mapper,
                       SitePostingGuard postings) {
        this.users = users;
        this.roles = roles;
        this.permissions = permissions;
        this.assignments = assignments;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.currentUser = currentUser;
        this.audit = audit;
        this.mapper = mapper;
        this.postings = postings;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('user:read')")
    public PageResponse<UserResponse> list(String q, Boolean active, String roleCode,
                                           Pageable pageable) {
        // The absent cases are values, not nulls — see UserRepository#search for why.
        return PageResponse.from(
                users.search(currentUser.currentOrgId(), blankToEmpty(q), blankToEmpty(roleCode),
                        active == null || active, active == null || !active, pageable),
                this::toResponse);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('user:read')")
    public UserResponse get(UUID id) {
        return toResponse(requireUser(id));
    }

    @PreAuthorize("hasAuthority('user:write')")
    public UserResponse create(CreateUserRequest request) {
        UUID orgId = currentUser.currentOrgId();
        if (users.existsByOrgIdAndUsernameIgnoreCase(orgId, request.username())) {
            throw BusinessException.conflict("user.username-taken",
                    "The username '" + request.username() + "' is already in use.");
        }
        User user = new User(orgId, request.username().toLowerCase(), request.fullName(),
                passwordEncoder.encode(request.temporaryPassword()));
        user.setEmail(request.email());
        user.setMobile(request.mobile());
        user.replaceRoles(resolveRoles(request.roleCodes()));
        users.save(user);

        if (request.siteIds() != null) {
            for (UUID siteId : request.siteIds()) {
                assignments.save(new UserSiteAssignment(orgId, user.getId(), siteId,
                        LocalDate.now(), false));
            }
        }
        audit.record("USER", user.getId(), "CREATE", null,
                Map.of("username", user.getUsername(), "roles", request.roleCodes()), null);
        return toResponse(user);
    }

    @PreAuthorize("hasAuthority('user:write')")
    public UserResponse update(UUID id, UpdateUserRequest request) {
        User user = requireUser(id);
        requireVersion(user, request.version());
        Map<String, Object> before = Map.of("fullName", user.getFullName(),
                "email", String.valueOf(user.getEmail()), "mobile", String.valueOf(user.getMobile()));
        user.setFullName(request.fullName());
        user.setEmail(request.email());
        user.setMobile(request.mobile());
        audit.record("USER", user.getId(), "UPDATE", before,
                Map.of("fullName", request.fullName(), "email", String.valueOf(request.email()),
                        "mobile", String.valueOf(request.mobile())), null);
        return toResponse(user);
    }

    @PreAuthorize("hasAuthority('user:write')")
    public UserResponse updateStatus(UUID id, boolean active) {
        User user = requireUser(id);
        if (user.getId().equals(currentUser.currentUserIdOrNull()) && !active) {
            throw new BusinessException("user.self-deactivate", "You cannot deactivate your own account.");
        }
        boolean before = user.isActive();
        user.setActive(active);
        if (!active) {
            // A disabled user must not keep a live session through a stored refresh token.
            refreshTokens.revokeAllForUser(user.getId(), Instant.now(), "USER_DEACTIVATED");
        }
        audit.record("USER", user.getId(), active ? "ACTIVATE" : "DEACTIVATE",
                Map.of("active", before), Map.of("active", active), null);
        return toResponse(user);
    }

    /**
     * Sets a new password on someone else's account and forces a change at next sign-in, so
     * the value the admin hands over never stays live. Every open session is revoked: a
     * reset is normally asked for because the account is in the wrong hands.
     *
     * <p>Deliberately no way to read a password back — the admin knows what they typed, and
     * an endpoint that returns credentials is one that logs and caches them.</p>
     */
    @PreAuthorize("hasAuthority('user:write')")
    public void resetPassword(UUID id, ResetPasswordRequest request) {
        User user = requireUser(id);
        user.changePassword(passwordEncoder.encode(request.temporaryPassword()), true);
        refreshTokens.revokeAllForUser(user.getId(), Instant.now(), "PASSWORD_RESET");
        audit.record("USER", user.getId(), "PASSWORD_RESET", null,
                Map.of("mustChangePassword", true), null);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('user:read')")
    public List<String> getRoles(UUID id) {
        return requireUser(id).getRoles().stream().map(Role::getCode).sorted().toList();
    }

    /**
     * Replaces the whole set a member holds — they may hold several, and their permissions
     * are the union. The set is sent complete rather than as a delta, so the caller's view
     * of who someone is wins outright.
     *
     * <p>One refusal: you cannot take {@code role:assign} off yourself. Roles are edited as
     * a set, so dropping the one that carries it is a single untick away, and it would leave
     * the account unable to put it back — with nobody else necessarily holding it either.</p>
     */
    @PreAuthorize("hasAuthority('role:assign')")
    public UserResponse putRoles(UUID id, AssignRolesRequest request) {
        User user = requireUser(id);
        List<String> before = user.getRoles().stream().map(Role::getCode).sorted().toList();
        Set<Role> wanted = resolveRoles(request.roleCodes());
        if (user.getId().equals(currentUser.currentUserIdOrNull()) && !grantsRoleAssign(wanted)) {
            throw new BusinessException("user.self-role-lockout",
                    "That would leave you unable to assign roles, including your own. "
                            + "Ask another administrator to make this change.");
        }
        user.replaceRoles(wanted);
        audit.record("USER", user.getId(), "ROLES_CHANGED",
                Map.of("roles", before), Map.of("roles", request.roleCodes()), null);
        return toResponse(user);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('user:read')")
    public List<SiteAssignmentResponse> getSites(UUID id) {
        requireUser(id);
        return assignments.findByUserId(id).stream()
                .map(a -> new SiteAssignmentResponse(a.getSiteId(), a.getAssignedFrom(),
                        a.getAssignedTo(), a.isPrimary()))
                .toList();
    }

    /**
     * Replaces the active site set. Removed sites are closed rather than deleted — an old
     * assignment is the explanation for whose name is on last month's records — and the
     * closure takes effect immediately, not at midnight; see
     * {@link UserSiteAssignment#revoke}. A previously closed assignment for a re-added site
     * is reopened, because the schema keeps one row per user and site.
     *
     * <p>What it will not do is withdraw a site from the engineer or supervisor named on it.
     * The sites register and these assignment rows are two different facts — who runs the
     * site, and who may open it — and the sync between them runs one way, from the register
     * to the rows. Letting this screen cut a row the register still implies is the one way
     * the two can end up contradicting each other; see {@link SitePostingGuard}.</p>
     */
    @PreAuthorize("hasAuthority('user:write')")
    public List<SiteAssignmentResponse> putSites(UUID id, AssignSitesRequest request) {
        User user = requireUser(id);
        LocalDate today = LocalDate.now();
        Set<UUID> wanted = new HashSet<>(request.siteIds());
        List<UserSiteAssignment> existing = assignments.findByUserId(id);

        // Checked before anything is written, and over the whole withdrawal at once, so an
        // admin clearing two sites learns about both in one refusal rather than one per try.
        postings.assertNotPosted(id, existing.stream()
                .filter(assignment -> assignment.isActiveOn(today))
                .map(UserSiteAssignment::getSiteId)
                .filter(siteId -> !wanted.contains(siteId))
                .toList());

        for (UserSiteAssignment assignment : existing) {
            boolean shouldBeActive = wanted.remove(assignment.getSiteId());
            if (shouldBeActive && !assignment.isActiveOn(today)) {
                assignment.reopen();
            } else if (!shouldBeActive && assignment.isActiveOn(today)) {
                assignment.revoke(today);
            }
        }
        for (UUID siteId : wanted) {
            assignments.save(new UserSiteAssignment(user.getOrgId(), id, siteId, today, false));
        }
        audit.record("USER", id, "SITES_CHANGED", null,
                Map.of("siteIds", request.siteIds()), null);
        return getSitesInternal(id);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('user:read')")
    public List<RoleResponse> listRoles() {
        return roles.findBySystemTrueOrderByCode().stream()
                .map(r -> new RoleResponse(r.getCode(), r.getName(), r.getDescription()))
                .toList();
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('user:read')")
    public List<PermissionResponse> listPermissions() {
        return permissions.findAllByOrderByModuleAscCodeAsc().stream()
                .map(p -> new PermissionResponse(p.getCode(), p.getModule(), p.getDescription()))
                .toList();
    }

    // ------------------------------------------------------------------ internals

    private List<SiteAssignmentResponse> getSitesInternal(UUID id) {
        return assignments.findByUserId(id).stream()
                .map(a -> new SiteAssignmentResponse(a.getSiteId(), a.getAssignedFrom(),
                        a.getAssignedTo(), a.isPrimary()))
                .toList();
    }

    private User requireUser(UUID id) {
        User user = users.findById(id).orElseThrow(() -> BusinessException.notFound("User", id));
        if (!user.getOrgId().equals(currentUser.currentOrgId())) {
            throw BusinessException.notFound("User", id);   // other orgs' users do not exist for you
        }
        return user;
    }

    /** The union of a set's permissions is what the member ends up holding — see AuthService. */
    private static boolean grantsRoleAssign(Set<Role> roleSet) {
        return roleSet.stream()
                .flatMap(role -> role.getPermissions().stream())
                .anyMatch(permission -> "role:assign".equals(permission.getCode()));
    }

    private Set<Role> resolveRoles(List<String> roleCodes) {
        List<Role> found = roles.findByCodeInAndSystemTrue(roleCodes);
        if (found.size() != Set.copyOf(roleCodes).size()) {
            Set<String> known = found.stream().map(Role::getCode).collect(Collectors.toSet());
            String unknown = roleCodes.stream().filter(c -> !known.contains(c))
                    .collect(Collectors.joining(", "));
            throw new BusinessException("role.unknown", "Unknown role(s): " + unknown);
        }
        return new HashSet<>(found);
    }

    private static void requireVersion(User user, Long version) {
        if (!user.getVersion().equals(version)) {
            throw new OptimisticLockingFailureException(
                    "User " + user.getId() + " was changed by someone else");
        }
    }

    private UserResponse toResponse(User user) {
        LocalDate today = LocalDate.now();
        List<UUID> siteIds = assignments.findByUserId(user.getId()).stream()
                .filter(a -> a.isActiveOn(today))
                .map(UserSiteAssignment::getSiteId)
                .sorted()
                .toList();
        return mapper.toResponse(user, siteIds);
    }

    /** "No filter" travels as an empty string, never a null: see {@link UserRepository#search}. */
    private static String blankToEmpty(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}

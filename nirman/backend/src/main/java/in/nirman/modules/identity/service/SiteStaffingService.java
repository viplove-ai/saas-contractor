package in.nirman.modules.identity.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.identity.domain.Role;
import in.nirman.modules.identity.domain.User;
import in.nirman.modules.identity.domain.UserSiteAssignment;
import in.nirman.modules.identity.repository.UserRepository;
import in.nirman.modules.identity.repository.UserSiteAssignmentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Turns "this person runs that site" into the assignment rows {@code SiteAccessGuard}
 * reads.
 *
 * <p>No {@code @PreAuthorize}: the only caller is the project module's site editor, which
 * has already required {@code site:write}. Adding {@code user:write} here would mean nobody
 * could staff a site without also holding identity administration — and {@code site:write}
 * is exactly the permission that is meant to carry this.</p>
 */
@Service
@Transactional
public class SiteStaffingService implements SiteStaffing {

    /** An admin may stand in for any site role; the matrix already grants them everything. */
    private static final String ADMIN = "ADMIN";

    private final UserRepository users;
    private final UserSiteAssignmentRepository assignments;
    private final AuditService audit;

    public SiteStaffingService(UserRepository users, UserSiteAssignmentRepository assignments,
                               AuditService audit) {
        this.users = users;
        this.assignments = assignments;
        this.audit = audit;
    }

    @Override
    @Transactional(readOnly = true)
    public void requireStaffMember(UUID orgId, UUID userId, String roleCode) {
        User user = users.findById(userId)
                .filter(u -> u.getOrgId().equals(orgId))
                .orElseThrow(() -> new BusinessException("site.staff-unknown",
                        "That user does not exist.", HttpStatus.UNPROCESSABLE_ENTITY));
        if (!user.isActive()) {
            throw new BusinessException("site.staff-inactive",
                    user.getFullName() + " is deactivated and cannot be posted to a site.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        Set<String> held = user.getRoles().stream().map(Role::getCode).collect(Collectors.toSet());
        if (!held.contains(roleCode) && !held.contains(ADMIN)) {
            throw new BusinessException("site.staff-wrong-role",
                    user.getFullName() + " does not hold the " + roleCode + " role.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<SiteMember> postedTo(UUID orgId, UUID siteId) {
        List<UUID> ids = assignments.findActiveUserIds(orgId, siteId, LocalDate.now());
        if (ids.isEmpty()) {
            return List.of();
        }
        return users.findAllById(ids).stream()
                .filter(user -> user.getOrgId().equals(orgId) && user.isActive())
                .map(user -> new SiteMember(user.getId(), user.getUsername(), user.getFullName(),
                        user.getRoles().stream().map(Role::getCode).sorted().toList()))
                .sorted(Comparator.comparing(SiteMember::fullName,
                        Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    @Override
    public void updateSiteAccess(UUID orgId, UUID siteId, Set<UUID> granted, Set<UUID> revoked) {
        LocalDate today = LocalDate.now();
        for (UUID userId : granted) {
            grant(orgId, siteId, userId, today);
        }
        for (UUID userId : revoked) {
            revoke(siteId, userId, today);
        }
    }

    private void grant(UUID orgId, UUID siteId, UUID userId, LocalDate today) {
        List<UserSiteAssignment> existing = assignments.findByUserIdAndSiteId(userId, siteId);
        UserSiteAssignment live = existing.stream()
                .filter(a -> a.isActiveOn(today))
                .findFirst()
                .orElse(null);
        if (live != null) {
            return;     // already posted there; re-sending the same site edit changes nothing
        }
        if (existing.isEmpty()) {
            assignments.save(new UserSiteAssignment(orgId, userId, siteId, today, false));
        } else {
            // One row per user and site is the schema rule, so a past posting is reopened
            // rather than duplicated.
            existing.get(0).reopen();
        }
        audit.record("USER", userId, "SITE_ACCESS_GRANTED", null,
                Map.of("siteId", siteId.toString(), "via", "SITE_STAFFING"), null);
    }

    private void revoke(UUID siteId, UUID userId, LocalDate today) {
        assignments.findByUserIdAndSiteId(userId, siteId).stream()
                .filter(a -> a.isActiveOn(today))
                .forEach(a -> {
                    a.revoke(today);
                    audit.record("USER", userId, "SITE_ACCESS_REVOKED", null,
                            Map.of("siteId", siteId.toString(), "via", "SITE_STAFFING"), null);
                });
    }
}

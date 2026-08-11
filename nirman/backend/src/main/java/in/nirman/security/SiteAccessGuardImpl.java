package in.nirman.security;

import in.nirman.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.UUID;

/**
 * Site scope enforcement: the JWT {@code sites} claim answers first (cheap, no query), and
 * a positive answer is then re-validated against {@code user_site_assignments}, because an
 * assignment can be revoked while an issued token is still alive. The claim alone can say
 * no; only the database can say yes.
 *
 * <p>Deliberately queries by JDBC rather than through the identity module's repositories:
 * this is cross-cutting security code, not module business logic, and it must not create a
 * dependency cycle with the modules that call it.</p>
 */
@Component
public class SiteAccessGuardImpl implements SiteAccessGuard {

    private static final String ASSIGNMENT_EXISTS = """
            SELECT count(*) FROM user_site_assignments
            WHERE user_id = ? AND site_id = ?
              AND assigned_from <= CURRENT_DATE
              AND (assigned_to IS NULL OR assigned_to >= CURRENT_DATE)
            """;

    private static final String SITE_IS_LIVE = """
            SELECT count(*) FROM sites WHERE id = ? AND deleted_at IS NULL
            """;

    private final CurrentUserProviderImpl currentUser;
    private final JdbcTemplate jdbc;

    public SiteAccessGuardImpl(CurrentUserProviderImpl currentUser, JdbcTemplate jdbc) {
        this.currentUser = currentUser;
        this.jdbc = jdbc;
    }

    @Override
    public void assertCanAccess(UUID siteId) {
        if (!canAccess(siteId)) {
            throw BusinessException.forbidden("This site is not assigned to you.");
        }
    }

    @Override
    public void assertCanAccessAll(Collection<UUID> siteIds) {
        siteIds.forEach(this::assertCanAccess);
    }

    @Override
    public boolean canAccess(UUID siteId) {
        if (siteId == null) {
            return false;
        }
        // Ahead of the all-sites shortcut, and deliberately: a deleted site is closed to
        // everybody, an administrator included. Deleting a site does not delete the rows in
        // user_site_assignments that point at it, and most write paths in labour, inventory
        // and expense reach their own repositories through this guard rather than through
        // SiteService — so without this check a deleted site would go on accepting work.
        if (!isLive(siteId)) {
            return false;
        }
        AuthenticatedUser user = currentUser.required();
        if (user.allSites()) {
            return true;
        }
        if (!user.siteIds().contains(siteId)) {
            return false;
        }
        Integer count = jdbc.queryForObject(ASSIGNMENT_EXISTS, Integer.class,
                user.userId(), siteId);
        return count != null && count > 0;
    }

    private boolean isLive(UUID siteId) {
        Integer count = jdbc.queryForObject(SITE_IS_LIVE, Integer.class, siteId);
        return count != null && count > 0;
    }
}

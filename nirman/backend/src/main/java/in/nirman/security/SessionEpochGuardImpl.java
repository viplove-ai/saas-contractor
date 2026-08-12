package in.nirman.security;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * One primary-key lookup per authenticated request. See {@link SessionEpochGuard} for why
 * it is worth paying, and why it is asked in SQL rather than through the identity module.
 *
 * <p>Deliberately not cached. A cache would put a window back exactly where the counter was
 * added to close one, and the difference between "signed out now" and "signed out in thirty
 * seconds" is the whole feature. The query is a single row by primary key; the request it
 * precedes will do heavier work than this before it answers.</p>
 */
@Component
public class SessionEpochGuardImpl implements SessionEpochGuard {

    private static final String CURRENT_EPOCH =
            "SELECT session_epoch FROM users WHERE id = ? AND is_active = true";

    private final JdbcTemplate jdbc;

    public SessionEpochGuardImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isCurrent(UUID userId, long sessionEpoch) {
        if (userId == null || sessionEpoch < 0) {
            return false;
        }
        try {
            Long current = jdbc.queryForObject(CURRENT_EPOCH, Long.class, userId);
            return current != null && current == sessionEpoch;
        } catch (EmptyResultDataAccessException deleted) {
            // A deactivated or removed account: the token outlived the login it belongs to.
            return false;
        }
    }
}

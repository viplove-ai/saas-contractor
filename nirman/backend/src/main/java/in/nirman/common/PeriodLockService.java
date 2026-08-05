package in.nirman.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/**
 * The write side of {@code period_locks}; {@link PeriodLockGuard} is the read side.
 *
 * <p>Closing a month is what turns a set of verified records into an accounting fact. It is
 * cross-cutting rather than owned by any module — labour, inventory and expense all respect
 * the same lock — so it lives here alongside the guard and, like it, works in plain JDBC
 * rather than dragging an entity into three modules.</p>
 *
 * <p>Unlocking is deliberately not the inverse of locking: it records who reopened the month
 * and why, and leaves the original lock row in place. "This month was closed on the 3rd and
 * reopened on the 11th by the administrator, because…" is the sentence an auditor needs.</p>
 */
@Service
public class PeriodLockService {

    private static final String INSERT_LOCK = """
            INSERT INTO period_locks (org_id, site_id, module, year_month, locked_by)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (site_id, module, year_month) DO UPDATE
                SET unlocked_at = NULL, unlocked_by = NULL, unlock_reason = NULL,
                    locked_at = now(), locked_by = EXCLUDED.locked_by
                WHERE period_locks.unlocked_at IS NOT NULL
            """;

    private static final String RELEASE_LOCK = """
            UPDATE period_locks
               SET unlocked_at = now(), unlocked_by = ?, unlock_reason = ?
             WHERE site_id = ? AND module = ? AND year_month = ? AND unlocked_at IS NULL
            """;

    private final JdbcTemplate jdbc;

    public PeriodLockService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return false when the month was already locked, so the caller can answer 409 rather
     *         than silently re-closing a period someone is relying on
     */
    @Transactional
    public boolean lock(UUID orgId, UUID siteId, PeriodLockGuard.Module module,
                        YearMonth yearMonth, UUID lockedBy) {
        return jdbc.update(INSERT_LOCK, orgId, siteId, module.name(), yearMonth.toString(),
                lockedBy) > 0;
    }

    @Transactional
    public boolean unlock(UUID siteId, PeriodLockGuard.Module module, YearMonth yearMonth,
                          UUID unlockedBy, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("period.unlock-reason",
                    "Reopening a closed month needs a written reason.");
        }
        return jdbc.update(RELEASE_LOCK, unlockedBy, reason, siteId, module.name(),
                yearMonth.toString()) > 0;
    }

    public static LocalDate firstDayOf(YearMonth yearMonth) {
        return yearMonth.atDay(1);
    }

    public static LocalDate lastDayOf(YearMonth yearMonth) {
        return yearMonth.atEndOfMonth();
    }
}

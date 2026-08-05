package in.nirman.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Checks {@code period_locks} for the site and month a write is aimed at. A lock on module
 * {@code ALL} closes every module at once. Queried by JDBC because this is cross-cutting
 * infrastructure shared by three modules, none of which should own the table's entity.
 */
@Component
public class PeriodLockGuardImpl implements PeriodLockGuard {

    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private static final String LOCK_EXISTS = """
            SELECT count(*) FROM period_locks
            WHERE site_id = ? AND year_month = ?
              AND module IN (?, 'ALL')
              AND unlocked_at IS NULL
            """;

    private final JdbcTemplate jdbc;

    public PeriodLockGuardImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void assertOpen(UUID siteId, LocalDate businessDate, Module module) {
        String yearMonth = YEAR_MONTH.format(businessDate);
        Integer count = jdbc.queryForObject(LOCK_EXISTS, Integer.class,
                siteId, yearMonth, module.name());
        if (count != null && count > 0) {
            throw new BusinessException("period.locked",
                    "The period " + yearMonth + " is locked for " + module.name().toLowerCase()
                            + " on this site. Ask an administrator to unlock it.");
        }
    }
}

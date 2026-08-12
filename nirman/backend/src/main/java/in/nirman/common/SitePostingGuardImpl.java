package in.nirman.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reads the posts on a site straight off {@code site_staff}. See {@link SitePostingGuard}
 * for why the question is asked here in SQL rather than through the project module.
 */
@Component
public class SitePostingGuardImpl implements SitePostingGuard {

    private final JdbcTemplate jdbc;

    public SitePostingGuardImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void assertNotPosted(UUID userId, Collection<UUID> siteIds) {
        if (siteIds.isEmpty()) {
            return;
        }
        // A placeholder per id rather than the ids themselves pasted into the statement:
        // the list is short, and this is the difference between a bind and an injection.
        String placeholders = siteIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        List<Object> args = new ArrayList<>();
        args.addAll(siteIds);
        args.add(userId);

        // One row per site, not per post: somebody who is both engineer and supervisor of a
        // small job holds it once as far as a refusal is concerned, and naming him twice in
        // the message would read as two different sites.
        List<String> held = jdbc.query("""
                        SELECT s.code || ' — ' || s.name AS site,
                               string_agg(DISTINCT CASE WHEN ss.post = 'ENGINEER'
                                                        THEN 'site engineer'
                                                        ELSE 'supervisor' END, ' and ') AS post
                        FROM sites s
                        JOIN site_staff ss ON ss.site_id = s.id
                        WHERE s.deleted_at IS NULL
                          AND s.id IN (%s)
                          AND ss.user_id = ?
                        GROUP BY s.id, s.code, s.name
                        ORDER BY s.code
                        """.formatted(placeholders),
                (rs, row) -> rs.getString("site") + " (" + rs.getString("post") + ")",
                args.toArray());

        if (held.isEmpty()) {
            return;
        }
        throw new BusinessException("user.still-posted",
                "This member is still named on " + join(held) + ". Taking the site away here "
                        + "would leave the sites register saying they run it while the door is "
                        + "shut to them — post somebody else on the Sites screen first.");
    }

    @Override
    public Collection<UUID> postedSites(UUID userId) {
        return jdbc.queryForList("""
                SELECT DISTINCT s.id FROM sites s
                JOIN site_staff ss ON ss.site_id = s.id
                WHERE s.deleted_at IS NULL AND ss.user_id = ?
                """, UUID.class, userId);
    }

    /** "KSN-A (site engineer) and KSN-B (supervisor)", not a comma-separated dump. */
    private static String join(List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1))
                + " and " + parts.get(parts.size() - 1);
    }
}

package in.nirman.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Counts what stands at a site before it can be deleted. See {@link SiteDeletionGuard} for
 * why the rule exists and why it is written in SQL here rather than through the modules
 * that own these tables.
 */
@Component
public class SiteDeletionGuardImpl implements SiteDeletionGuard {

    /**
     * What counts as history, in the words the answer will be read in.
     *
     * <p>Ordered by how much an administrator cares: attendance and the stock ledger are the
     * two that carry frozen money, and naming them first makes the refusal legible without
     * reading the whole list. The intermediate documents (receipts, issues) are here as well
     * as the ledger they post to, because a draft receipt has no ledger row yet and would
     * otherwise slip through as "nothing recorded".</p>
     */
    private static final Map<String, Noun> RECORD_TABLES = new LinkedHashMap<>();

    /** Both forms spelled out: "entrys" and "goods receipts" do not both fall out of one rule. */
    private record Noun(String one, String many) {
        String of(long count) {
            return count + " " + (count == 1 ? one : many);
        }
    }

    static {
        RECORD_TABLES.put("attendance_records", new Noun("attendance record", "attendance records"));
        RECORD_TABLES.put("stock_transactions", new Noun("stock ledger entry", "stock ledger entries"));
        RECORD_TABLES.put("expenses", new Noun("expense", "expenses"));
        RECORD_TABLES.put("daily_progress_reports",
                new Noun("daily progress report", "daily progress reports"));
        RECORD_TABLES.put("goods_receipts", new Noun("goods receipt", "goods receipts"));
        RECORD_TABLES.put("material_issues", new Noun("material issue", "material issues"));
        RECORD_TABLES.put("purchase_orders", new Noun("purchase order", "purchase orders"));
        RECORD_TABLES.put("site_advances", new Noun("site advance", "site advances"));
        RECORD_TABLES.put("worker_advances", new Noun("worker advance", "worker advances"));
        RECORD_TABLES.put("boq_progress_entries", new Noun("progress entry", "progress entries"));
    }

    private final JdbcTemplate jdbc;

    public SiteDeletionGuardImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void assertDeletable(UUID siteId) {
        List<String> found = countsAt(siteId);
        if (found.isEmpty()) {
            return;
        }
        throw new BusinessException("site.has-records",
                "This site has " + join(found) + " recorded against it. Work already booked "
                        + "cannot be taken off the books — set the site to Closed instead, "
                        + "which stops new entries and keeps the figures.");
    }

    @Override
    public void assertProjectDeletable(Collection<UUID> siteIds) {
        List<String> blocked = new ArrayList<>();
        for (UUID siteId : siteIds) {
            List<String> found = countsAt(siteId);
            if (!found.isEmpty()) {
                blocked.add(siteName(siteId) + " (" + join(found) + ")");
            }
        }
        if (blocked.isEmpty()) {
            return;
        }
        throw new BusinessException("project.has-records",
                "This project cannot be deleted: " + String.join("; ", blocked)
                        + ". Work already booked cannot be taken off the books — set the "
                        + "project to Closed instead, which keeps the figures.");
    }

    // ------------------------------------------------------------------ internals

    /**
     * One statement rather than ten round trips, and only the non-zero rows come back — the
     * caller is building a sentence, not a report.
     */
    private List<String> countsAt(UUID siteId) {
        String sql = RECORD_TABLES.keySet().stream()
                .map(table -> "SELECT '" + table + "' AS t, count(*) AS n FROM " + table
                        + " WHERE site_id = ?")
                .collect(Collectors.joining(" UNION ALL "));
        Object[] args = new Object[RECORD_TABLES.size()];
        java.util.Arrays.fill(args, siteId);

        return jdbc.query(sql, (rs, row) -> Map.entry(rs.getString("t"), rs.getLong("n")), args)
                .stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> RECORD_TABLES.get(entry.getKey()).of(entry.getValue()))
                .toList();
    }

    private String siteName(UUID siteId) {
        List<String> names = jdbc.queryForList(
                "SELECT code || ' — ' || name FROM sites WHERE id = ?", String.class, siteId);
        return names.isEmpty() ? siteId.toString() : names.get(0);
    }

    /** "3 expenses and 1 goods receipt", not "3 expenses, 1 goods receipt". */
    private static String join(List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1))
                + " and " + parts.get(parts.size() - 1);
    }
}

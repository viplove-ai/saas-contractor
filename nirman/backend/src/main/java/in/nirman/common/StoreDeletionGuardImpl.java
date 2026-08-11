package in.nirman.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Counts what has been recorded against a store before it can be deleted. See
 * {@link StoreDeletionGuard} for why the rule exists and why it is written in SQL here.
 */
@Component
public class StoreDeletionGuardImpl implements StoreDeletionGuard {

    /**
     * What counts as history, in the words the refusal will be read in, and the column each
     * table names the store by.
     *
     * <p>The ledger leads, because it is the one that cannot be reconstructed. The documents
     * that post to it are listed as well as the ledger itself: a draft receipt has no ledger
     * row yet and would otherwise slip through as "nothing recorded", and the day it is
     * approved it would post to a store that had been deleted underneath it.</p>
     *
     * <p>A transfer names two stores and appears under either end. Both directions are
     * checked — the store material left is as much a part of that record as the one it
     * arrived at.</p>
     */
    private static final Map<String, Entry> RECORD_TABLES = new LinkedHashMap<>();

    private record Entry(String column, String one, String many) {
        String of(long count) {
            return count + " " + (count == 1 ? one : many);
        }
    }

    static {
        RECORD_TABLES.put("stock_transactions",
                new Entry("store_id", "stock ledger entry", "stock ledger entries"));
        RECORD_TABLES.put("stock_balances",
                new Entry("store_id", "material balance", "material balances"));
        RECORD_TABLES.put("goods_receipts",
                new Entry("store_id", "goods receipt", "goods receipts"));
        RECORD_TABLES.put("material_issues",
                new Entry("store_id", "material issue", "material issues"));
        RECORD_TABLES.put("stock_transfers_out",
                new Entry("from_store_id", "outgoing transfer", "outgoing transfers"));
        RECORD_TABLES.put("stock_transfers_in",
                new Entry("to_store_id", "incoming transfer", "incoming transfers"));
        RECORD_TABLES.put("physical_stock_counts",
                new Entry("store_id", "stock count", "stock counts"));
    }

    private final JdbcTemplate jdbc;

    public StoreDeletionGuardImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void assertDeletable(UUID storeId) {
        List<String> found = countsAt(storeId);
        if (found.isEmpty()) {
            return;
        }
        throw new BusinessException("store.has-records",
                "This store has " + join(found) + " recorded against it. A store that has held "
                        + "material cannot be taken off the books — mark it inactive instead, "
                        + "which stops new documents naming it and keeps its ledger.");
    }

    /** One statement rather than seven round trips; only the non-zero rows come back. */
    private List<String> countsAt(UUID storeId) {
        String sql = RECORD_TABLES.entrySet().stream()
                .map(entry -> "SELECT '" + entry.getKey() + "' AS t, count(*) AS n FROM "
                        + tableOf(entry.getKey()) + " WHERE " + entry.getValue().column() + " = ?")
                .collect(Collectors.joining(" UNION ALL "));
        Object[] args = new Object[RECORD_TABLES.size()];
        java.util.Arrays.fill(args, storeId);

        return jdbc.query(sql, (rs, row) -> Map.entry(rs.getString("t"), rs.getLong("n")), args)
                .stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> RECORD_TABLES.get(entry.getKey()).of(entry.getValue()))
                .toList();
    }

    /** The two transfer keys are two readings of one table, so the key is not the table name. */
    private static String tableOf(String key) {
        return key.startsWith("stock_transfers") ? "stock_transfers" : key;
    }

    /** "3 goods receipts and 1 material issue", not "3 goods receipts, 1 material issue". */
    private static String join(List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1))
                + " and " + parts.get(parts.size() - 1);
    }
}

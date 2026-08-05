package in.nirman;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the two schema rules the rest of the system depends on:
 * the ledger is the only way stock changes, and attendance cannot be duplicated.
 */
class SchemaMigrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("all migrations apply and the expected core tables exist")
    void migrationsApply() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains(
                "organisations", "users", "roles", "permissions", "projects", "sites",
                "workers", "wage_rates", "attendance_records", "materials",
                "stock_transactions", "stock_balances", "expenses", "payments",
                "site_advances", "boq_items", "daily_progress_reports", "audit_logs");
    }

    @Test
    @DisplayName("stock balance cannot be stored as a negative quantity")
    void stockBalanceCannotGoNegative() {
        List<String> constraints = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'stock_balances'::regclass",
                String.class);
        assertThat(constraints).contains("ck_balance_non_negative");
    }

    @Test
    @DisplayName("attendance is unique per worker, site and date while not cancelled")
    void attendanceIsUniquePerWorkerSiteDate() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'attendance_records'",
                String.class);
        assertThat(indexes).contains("uq_attendance_worker_site_date");
    }

    /**
     * The two guards V7 adds. Both exist so an offline device re-sending a document cannot
     * move stock twice — the same guarantee {@code uq_wle_attendance_posting} gives a wage.
     */
    @Test
    @DisplayName("a document line can move stock at most once in each direction")
    void ledgerPostingsAreIdempotentAtTheDatabase() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'stock_transactions'",
                String.class);
        assertThat(indexes).contains("uq_stx_source_line", "uq_stx_opening_once");
    }

    /** V8: no cash against an expense nobody approved. */
    @Test
    @DisplayName("an expense cannot carry a payment unless it is approved or voided")
    void unapprovedExpensesCannotBePaid() {
        List<String> constraints = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'expenses'::regclass",
                String.class);
        assertThat(constraints).contains("ck_expense_paid_only_when_approved");
    }

    /** V8: the same bill cannot clear two different floats. */
    @Test
    @DisplayName("an expense settles at most one advance")
    void anExpenseSettlesOneFloat() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'advance_settlement_expenses'",
                String.class);
        assertThat(indexes).contains("uq_ase_expense_settled_once");
    }

    /**
     * V8: the approval chain remembers what it was raised for. Without it the engine cannot
     * tell whether a second level applies without asking the business module — the coupling
     * the generic engine exists to avoid.
     */
    @Test
    @DisplayName("an approval carries the amount it was raised against")
    void approvalsRememberTheirAmount() {
        List<String> columns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'approvals'""", String.class);
        assertThat(columns).contains("entity_amount");
    }

    /**
     * V10's query tuning. Asserted by name because the whole point of these is that they are
     * invisible: nothing fails without them, the seeded dataset is far too small for anyone
     * to notice, and a merge that dropped the migration would be found in production by a
     * supervisor whose expense list took nine seconds to open.
     */
    @Test
    @DisplayName("the hot-path queries have an index behind them")
    void phase7IndexesExist() {
        assertThat(indexesOn("expenses"))
                .as("the supervisor's own-records list, the period roll-up and the ageing report")
                .contains("ix_expenses_own", "ix_expenses_org_date", "ix_expenses_outstanding",
                        "ix_expenses_similar");
        assertThat(indexesOn("goods_receipts"))
                .contains("ix_grn_store_status", "ix_grn_vendor_invoice");
        assertThat(indexesOn("material_issues")).contains("ix_issues_store_status");
        assertThat(indexesOn("daily_progress_reports")).contains("ix_dpr_status_date");
        assertThat(indexesOn("refresh_tokens")).contains("ix_refresh_tokens_expiry");
        assertThat(indexesOn("audit_logs")).contains("ix_audit_org_occurred");
    }

    /**
     * The one index whose absence would be a correctness problem rather than a slow screen:
     * the duplicate-invoice check compares case- and space-insensitively, so it needs an
     * expression index or it needs a full scan of every delivery ever booked.
     */
    @Test
    @DisplayName("the delivery duplicate check is an expression index, not a column one")
    void theInvoiceCheckIsIndexedOnTheExpression() {
        String definition = jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_grn_vendor_invoice'
                """, String.class);
        assertThat(definition).contains("upper", "btrim");
    }

    private List<String> indexesOn(String table) {
        return jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = ?",
                String.class, table);
    }
}

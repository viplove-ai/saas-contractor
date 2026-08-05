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
}

package in.nirman;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Phase 7 exit criterion: <b>the same draft sent twice creates one row.</b>
 *
 * <p>Each module has been idempotent on its client-generated id since the phase that built
 * it, and each has a test to that effect inside its own suite. This one exists because the
 * offline queue is the first caller that will actually do it — not once as a defensive
 * check, but repeatedly and by design, on every reconnect, from a device that may have been
 * killed mid-drain and has no idea which of its records got through. So the guarantee is
 * asserted here as one property across modules rather than as an incidental note in three
 * places, and it is asserted the way the queue does it: send the identical body again,
 * exactly as it sits in IndexedDB, and check the tables afterwards.</p>
 *
 * <p>The second half is the other side of the same coin. A repeat of an id is a replay and
 * must be absorbed; a <i>different</i> id for something the server already has is a genuine
 * collision and must be refused with a 409, because that is a second device or a re-created
 * draft, not a retry. Silently accepting it would mark a man present twice, and the fact
 * that both requests came from the same supervisor's phone is not evidence of anything.</p>
 */
class OfflineReplayIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";
    private static final String STORE_A = "32000000-0000-0000-0000-000000000001";
    private static final String NARESH = "60000000-0000-0000-0000-000000000107";
    private static final String TEST_MARK = "PHASE7-REPLAY";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeTestRows() {
        jdbc.update("""
                DELETE FROM approvals WHERE entity_id IN
                    (SELECT id FROM expenses WHERE description LIKE ?)""", TEST_MARK + "%");
        jdbc.update("DELETE FROM expenses WHERE description LIKE ?", TEST_MARK + "%");
    }

    // ------------------------------------------------------------------ replay is absorbed

    @Test
    @DisplayName("a muster batch sent three times marks the day once")
    void attendanceReplayCreatesOneRow() throws Exception {
        String supervisor = token("vivek");
        LocalDate day = freeAttendanceDay();
        String entryId = UUID.randomUUID().toString();
        // The body exactly as the queue holds it: same ids, same figures, byte for byte.
        String body = """
                {"siteId":"%s","date":"%s","entries":[
                  {"id":"%s","workerId":"%s","status":"PRESENT","breakMinutes":0,
                   "enteredHours":7}]}"""
                .formatted(SITE_A, day, entryId, NARESH);

        MvcResult first = saveBulk(supervisor, body);
        assertThat(outcome(first)).isEqualTo("CREATED");

        // Second and third: the response that never arrived, and the queue draining twice.
        assertThat(outcome(saveBulk(supervisor, body))).isEqualTo("UNCHANGED");
        assertThat(outcome(saveBulk(supervisor, body))).isEqualTo("UNCHANGED");

        Integer rows = jdbc.queryForObject("""
                SELECT count(*) FROM attendance_records
                WHERE worker_id = ?::uuid AND site_id = ?::uuid AND attendance_date = ?
                """, Integer.class, NARESH, SITE_A, day);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("an expense sent three times is booked once and keeps its first number")
    void expenseReplayCreatesOneRow() throws Exception {
        String supervisor = token("vivek");
        String id = UUID.randomUUID().toString();
        String body = expenseBody(id, "3400");

        String firstNumber = createExpense(supervisor, body, status().isCreated());
        // The document number is the tell. It comes from a counter, so a second row would
        // carry a different one even if every other column matched — which is precisely the
        // failure a naive "insert if not exists" would let through.
        assertThat(createExpense(supervisor, body, status().isCreated())).isEqualTo(firstNumber);
        assertThat(createExpense(supervisor, body, status().isCreated())).isEqualTo(firstNumber);

        Integer rows = jdbc.queryForObject("SELECT count(*) FROM expenses WHERE id = ?::uuid",
                Integer.class, id);
        assertThat(rows).isEqualTo(1);
    }

    /**
     * The one where a replay would cost real money. A goods receipt moves stock at
     * verification, so a duplicated delivery is not a duplicated row on a screen — it is
     * cement the store believes it has.
     */
    @Test
    @DisplayName("a delivery sent twice is one receipt and does not move stock twice")
    void goodsReceiptReplayCreatesOneRow() throws Exception {
        String storekeeper = token("vivek");
        String id = UUID.randomUUID().toString();
        String materialId = materialId();
        String unitId = unitId();
        String body = """
                {"id":"%s","storeId":"%s","receiptDate":"%s","challanNumber":"CH-PHASE7",
                 "lines":[{"materialId":"%s","unitId":"%s","quantity":40,"rate":410}]}"""
                .formatted(id, STORE_A, LocalDate.now(), materialId, unitId);

        mockMvc.perform(post("/api/v1/inventory/goods-receipts")
                        .header("Authorization", "Bearer " + storekeeper)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/inventory/goods-receipts")
                        .header("Authorization", "Bearer " + storekeeper)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        Integer receipts = jdbc.queryForObject(
                "SELECT count(*) FROM goods_receipts WHERE id = ?::uuid", Integer.class, id);
        assertThat(receipts).isEqualTo(1);
        Integer lines = jdbc.queryForObject(
                "SELECT count(*) FROM goods_receipt_items WHERE grn_id = ?::uuid", Integer.class, id);
        assertThat(lines)
                .as("the second send must not append the same line again")
                .isEqualTo(1);
        // Nothing is verified, so nothing has posted — but the ledger is where a double would
        // show up first, and checking zero here is what makes the next assertion meaningful.
        Integer postings = jdbc.queryForObject("""
                SELECT count(*) FROM stock_transactions WHERE source_id = ?::uuid
                """, Integer.class, id);
        assertThat(postings).isZero();
    }

    // ------------------------------------------------------------------ collision is refused

    @Test
    @DisplayName("a different id for a day already marked is refused rather than absorbed")
    void aFreshIdForTheSameDayIsAConflict() throws Exception {
        String supervisor = token("vivek");
        LocalDate day = freeAttendanceDay();
        saveBulk(supervisor, musterBody(day, UUID.randomUUID().toString()));

        // A second device, or a draft the first device lost and re-created. Same man, same
        // day, new id — and the server has no way to tell the two cases apart, which is why
        // it refuses both and lets a person decide.
        MvcResult clash = saveBulk(supervisor, musterBody(day, UUID.randomUUID().toString()));
        JsonNode outcome = objectMapper.readTree(clash.getResponse().getContentAsString())
                .get("outcomes").get(0);
        assertThat(outcome.get("outcome").asText()).isEqualTo("REJECTED");
        assertThat(outcome.get("reason").asText()).contains("already marked");

        Integer rows = jdbc.queryForObject("""
                SELECT count(*) FROM attendance_records
                WHERE worker_id = ?::uuid AND site_id = ?::uuid AND attendance_date = ?
                """, Integer.class, NARESH, SITE_A, day);
        assertThat(rows).isEqualTo(1);
    }

    /**
     * The conflict the sync screen's prompt exists for: a 409 the person can resolve, with
     * the row it collided with attached so they can tell a second delivery from a double
     * entry. Phase 5 established the behaviour; this asserts the queue can act on it.
     */
    @Test
    @DisplayName("a duplicate expense answers 409 with candidates, and force plus a reason books it")
    void aDuplicateExpenseIsResolvable() throws Exception {
        String supervisor = token("vivek");
        createExpense(supervisor, expenseBody(UUID.randomUUID().toString(), "7700", vendorId()),
                status().isCreated());

        MvcResult clash = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expenseBody(UUID.randomUUID().toString(), "7700", vendorId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.candidates").isNotEmpty())
                .andReturn();
        assertThat(clash.getResponse().getContentAsString()).contains("candidates");

        // What "Keep mine" sends: the same body, force=true, and the stated reason.
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", "Bearer " + supervisor)
                        .param("force", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expenseBody(UUID.randomUUID().toString(), "7700", vendorId(),
                                "Second lorry the same afternoon, separate challan")))
                .andExpect(status().isCreated())
                // Booked, and booked as knowing what it collided with. The reason itself is
                // not on the response — it lives on the row and in the audit trail, where the
                // person approving it will read it, rather than on a body the device throws
                // away the moment the queue marks the record sent.
                .andExpect(jsonPath("$.duplicateOfId").exists());
    }

    // ------------------------------------------------------------------ helpers

    private MvcResult saveBulk(String token, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/attendance/bulk")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String outcome(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("outcomes").get(0).get("outcome").asText();
    }

    private String musterBody(LocalDate day, String entryId) {
        return """
                {"siteId":"%s","date":"%s","entries":[
                  {"id":"%s","workerId":"%s","status":"PRESENT","breakMinutes":0,
                   "enteredHours":7}]}"""
                .formatted(SITE_A, day, entryId, NARESH);
    }

    private String createExpense(String token, String body, org.springframework.test.web.servlet.ResultMatcher expected)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(expected)
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("expenseNumber").asText();
    }

    private String expenseBody(String id, String amount) {
        return expenseBody(id, amount, null, null);
    }

    private String expenseBody(String id, String amount, String vendorId) {
        return expenseBody(id, amount, vendorId, null);
    }

    private String expenseBody(String id, String amount, String vendorId, String overrideReason) {
        String vendorClause = vendorId == null ? "" : "\"vendorId\":\"%s\",".formatted(vendorId);
        String overrideClause = overrideReason == null ? ""
                : ",\"duplicateOverrideReason\":\"%s\"".formatted(overrideReason);
        return """
                {"id":"%s","siteId":"%s","expenseDate":"%s","categoryId":"%s",%s
                 "description":"%s cartage","amountBeforeTax":%s,"gstPercent":0,
                 "paymentMode":"CASH"%s}"""
                .formatted(id, SITE_A, LocalDate.now(), categoryId(), vendorClause, TEST_MARK,
                        amount, overrideClause);
    }

    /**
     * A day nobody at this site has been marked on yet. The suite shares one database and
     * nothing rolls back, so a fixed date would pass alone and fail in a full run.
     */
    private synchronized LocalDate freeAttendanceDay() {
        Integer marked = jdbc.queryForObject("""
                SELECT count(*) FROM attendance_records WHERE site_id = ?::uuid
                """, Integer.class, SITE_A);
        return LocalDate.of(2025, 9, 1).plusDays(marked == null ? 0 : marked);
    }

    private String categoryId() {
        return jdbc.queryForObject("SELECT id FROM expense_categories WHERE code = 'MISC-SITE'",
                String.class);
    }

    private String vendorId() {
        return jdbc.queryForObject("SELECT id FROM vendors WHERE code = 'VND-001'", String.class);
    }

    private String materialId() {
        return jdbc.queryForObject("SELECT id FROM materials WHERE code = 'CEM-OPC43'",
                String.class);
    }

    /** Cement is received in bags, which is its base unit — no conversion in the way. */
    private String unitId() {
        return jdbc.queryForObject(
                "SELECT base_unit_id FROM materials WHERE code = 'CEM-OPC43'", String.class);
    }

    private String token(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}"
                                .formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}

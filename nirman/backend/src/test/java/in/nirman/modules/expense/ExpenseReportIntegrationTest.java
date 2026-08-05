package in.nirman.modules.expense;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The three cash reports, and the guard that makes the register honest.
 *
 * <p>{@code is_material_purchase} and {@code is_labour_payment} have sat unused on
 * {@code expense_categories} since Phase 2. This is where they finally do something, and
 * the reason they exist: material purchase becomes inventory and is costed again when the
 * material is issued, and money handed to a worker settles a wage already costed through
 * verified attendance. Add either to the project's cost and it is overstated — at Kausani
 * by most of ₹4,99,528 on the labour side alone (docs/09).</p>
 */
class ExpenseReportIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";
    private static final String TEST_MARK = "PHASE5-REPORT";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeTestExpenses() {
        jdbc.update("""
                DELETE FROM approvals WHERE entity_id IN
                    (SELECT id FROM expenses WHERE description LIKE ?)""", TEST_MARK + "%");
        jdbc.update("DELETE FROM payments WHERE expense_id IN "
                + "(SELECT id FROM expenses WHERE description LIKE ?)", TEST_MARK + "%");
        jdbc.update("DELETE FROM expenses WHERE description LIKE ?", TEST_MARK + "%");
    }

    /**
     * The split, with numbers somebody can check: ₹10,000 of site expenses, ₹40,000 of
     * cement bought, ₹25,000 handed to a labour contractor. Booked: ₹75,000. Cost the
     * project incurred: ₹10,000.
     */
    @Test
    @DisplayName("the register separates cost incurred from material purchase and wage settlement")
    void registerKeepsTheDoubleCountingGuardsApart() throws Exception {
        String uttam = loginToken("uttam");
        approved(uttam, "MISC-SITE", "10000", "site expenses");
        approved(uttam, "MAT-PURCHASE", "40000", "cement bought");
        approved(uttam, "LABOUR-CONTRACT", "25000", "contractor paid");

        JsonNode report = register(uttam);

        assertThat(decimal(report, "totalBooked")).isEqualByComparingTo("75000.00");
        assertThat(decimal(report, "materialPurchases")).isEqualByComparingTo("40000.00");
        assertThat(decimal(report, "labourDisbursements")).isEqualByComparingTo("25000.00");
        // The one figure that may be added to labour and material consumption.
        assertThat(decimal(report, "costIncurred")).isEqualByComparingTo("10000.00");

        // And the identity holds: the three parts add back to the whole.
        assertThat(decimal(report, "costIncurred")
                .add(decimal(report, "materialPurchases"))
                .add(decimal(report, "labourDisbursements")))
                .isEqualByComparingTo(decimal(report, "totalBooked"));
    }

    /** Every row says in one word whether it adds to what the project cost. */
    @Test
    @DisplayName("each register row is labelled with what it counts as")
    void everyRowSaysWhatItCountsAs() throws Exception {
        String uttam = loginToken("uttam");
        approved(uttam, "MAT-PURCHASE", "40000", "cement bought");

        JsonNode rows = register(uttam).get("rows");
        JsonNode row = rowFor(rows, "cement bought");
        assertThat(row.get("materialPurchase").asBoolean()).isTrue();
        assertThat(row.get("labourPayment").asBoolean()).isFalse();
    }

    /** The report explains its own arithmetic, so no client has to invent the wording. */
    @Test
    @DisplayName("the register carries a plain-English caveat about what its total is not")
    void registerExplainsItself() throws Exception {
        String uttam = loginToken("uttam");
        approved(uttam, "MISC-SITE", "1000", "anything");
        assertThat(register(uttam).get("caveat").asText())
                .contains("not what the project cost");
    }

    @Test
    @DisplayName("payable ageing buckets by how long a bill has been outstanding")
    void payableAgeingBuckets() throws Exception {
        String uttam = loginToken("uttam");
        String recent = approved(uttam, "MISC-SITE", "5000", "recent bill");
        String old = approvedDated(uttam, "MISC-SITE", "7000", "old bill",
                LocalDate.now().minusDays(75));

        MvcResult result = mockMvc.perform(get("/api/v1/reports/payable-ageing")
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode report = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(bucketOf(report, recent)).isEqualTo("0-30");
        assertThat(bucketOf(report, old)).isEqualTo("61-90");
        assertThat(decimal(report, "current")).isGreaterThanOrEqualTo(new BigDecimal("5000"));
        assertThat(decimal(report, "days61to90")).isGreaterThanOrEqualTo(new BigDecimal("7000"));
    }

    @Test
    @DisplayName("advance balances show the seeded float still in the supervisor's pocket")
    void advanceBalancesShowOpenFloats() throws Exception {
        String uttam = loginToken("uttam");
        MvcResult result = mockMvc.perform(get("/api/v1/reports/advance-balances")
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode report = objectMapper.readTree(result.getResponse().getContentAsString());

        JsonNode row = null;
        for (JsonNode candidate : report.get("rows")) {
            if ("SAD-2025-0001".equals(candidate.get("advanceNumber").asText())) {
                row = candidate;
            }
        }
        assertThat(row).as("the seeded float").isNotNull();
        assertThat(row.get("heldByName").asText()).isEqualTo("Vivek Aggarwal");
        assertThat(new BigDecimal(row.get("balanceAmount").asText()))
                .isEqualByComparingTo("20000.00");
    }

    /** Money is a financial report. A supervisor holds report:operational and not this. */
    @Test
    @DisplayName("a supervisor cannot pull the expense register")
    void supervisorCannotReadTheRegister() throws Exception {
        String vivek = loginToken("vivek");
        mockMvc.perform(get("/api/v1/reports/expense-register")
                        .param("from", LocalDate.now().minusDays(7).toString())
                        .param("to", LocalDate.now().toString())
                        .header("Authorization", "Bearer " + vivek))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("every cash report exports to xlsx")
    void reportsExportToSpreadsheets() throws Exception {
        String uttam = loginToken("uttam");
        approved(uttam, "MISC-SITE", "1000", "anything");
        String from = LocalDate.now().minusDays(7).toString();
        String to = LocalDate.now().toString();

        mockMvc.perform(get("/api/v1/reports/expense-register")
                        .param("from", from).param("to", to).param("format", "xlsx")
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".xlsx")));
        mockMvc.perform(get("/api/v1/reports/payable-ageing").param("format", "xlsx")
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/reports/advance-balances").param("format", "xlsx")
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode register(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/reports/expense-register")
                        .param("siteId", SITE_A)
                        .param("from", LocalDate.now().minusDays(90).toString())
                        .param("to", LocalDate.now().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode rowFor(JsonNode rows, String what) {
        for (JsonNode row : rows) {
            if (row.get("description").asText().endsWith(what)) {
                return row;
            }
        }
        throw new AssertionError("No register row for " + what);
    }

    /** Ageing rows identify a bill by its number, so resolve the id through the database. */
    private String bucketOf(JsonNode report, String expenseId) {
        String number = jdbc.queryForObject(
                "SELECT expense_number FROM expenses WHERE id = ?::uuid", String.class, expenseId);
        for (JsonNode row : report.get("rows")) {
            if (number.equals(row.get("expenseNumber").asText())) {
                return row.get("bucket").asText();
            }
        }
        throw new AssertionError("No ageing row for " + number);
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asText());
    }

    private String approved(String token, String categoryCode, String amount, String what)
            throws Exception {
        return approvedDated(token, categoryCode, amount, what, LocalDate.now());
    }

    private String approvedDated(String token, String categoryCode, String amount, String what,
                                 LocalDate date) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","siteId":"%s","expenseDate":"%s","categoryId":"%s",
                                 "description":"%s %s","billNumber":"R-%s",
                                 "amountBeforeTax":%s,"gstPercent":0,"paymentMode":"CASH"}"""
                                .formatted(UUID.randomUUID(), SITE_A, date,
                                        categoryId(categoryCode), TEST_MARK, what,
                                        UUID.randomUUID().toString().substring(0, 8), amount)))
                .andExpect(status().isCreated())
                .andReturn();
        String id = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(post("/api/v1/expenses/" + id + "/submit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        for (int level = 0; level < 3; level++) {
            String status = jdbc.queryForObject(
                    "SELECT workflow_status FROM expenses WHERE id = ?::uuid", String.class, id);
            if (!"SUBMITTED".equals(status) && !"L1_APPROVED".equals(status)) {
                break;
            }
            mockMvc.perform(post("/api/v1/expenses/" + id + "/approve")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"APPROVE\"}"))
                    .andExpect(status().isOk());
        }
        return id;
    }

    private String categoryId(String code) {
        return jdbc.queryForObject("SELECT id FROM expense_categories WHERE code = ?",
                String.class, code);
    }

    private String loginToken(String username) throws Exception {
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

package in.nirman.modules.dpr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.AbstractIntegrationTest;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Phase 6 exit criterion about the DPR: <b>the prefill matches the underlying records
 * exactly.</b>
 *
 * <p>Every assertion here compares the API's figure against the same figure summed straight out
 * of the tables with SQL. That is the only version of this test worth having. Comparing the
 * prefill against hand-written constants would prove that somebody typed the same number twice;
 * comparing it against the rows themselves is what catches the failure that actually happens —
 * a figure that was right when it was computed and has since drifted, because something along
 * the way decided to cache it.</p>
 *
 * <p>The fixture is the seeded 10 June 2025 at Kausani Main Block (V903): three men on the
 * muster with an hour of overtime between them, one cartage bill, and no material movement.</p>
 */
class DprPrefillIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";
    private static final LocalDate SEEDED_DAY = LocalDate.of(2025, 6, 10);
    private static final String BOQ_BRICKWORK_14 = "70000000-0000-0000-0000-000000000003";
    private static final String BOQ_STEEL = "70000000-0000-0000-0000-000000000007";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * The criterion itself, checked field by field against SQL over the attendance rows.
     */
    @Test
    @DisplayName("the labour prefill matches the attendance rows for the day exactly")
    void labourPrefillMatchesTheAttendanceRecords() throws Exception {
        JsonNode labour = prefill("uttam").get("labour");

        assertThat(labour.get("presentCount").asInt())
                .isEqualTo(count("status IN ('PRESENT','HALF_DAY')"));
        assertThat(labour.get("recordCount").asInt()).isEqualTo(count("1 = 1"));
        assertThat(decimal(labour, "regularHours")).isEqualByComparingTo(sum("regular_hours"));
        assertThat(decimal(labour, "overtimeHours")).isEqualByComparingTo(sum("overtime_hours"));
        assertThat(decimal(labour, "cost")).isEqualByComparingTo(
                sum("coalesce(computed_wage_amount,0) + coalesce(computed_ot_amount,0)"));

        // The seed's three men: 21 regular hours, one hour of overtime on the bar bender.
        assertThat(labour.get("presentCount").asInt()).isEqualTo(3);
        assertThat(decimal(labour, "regularHours")).isEqualByComparingTo("21.00");
        assertThat(decimal(labour, "overtimeHours")).isEqualByComparingTo("1.00");
    }

    /**
     * The wage is frozen at verification, so money on an unverified row is provisional. A prefill
     * that reported it as final would be quoting a figure that changes overnight without anybody
     * editing anything, which is the fastest way to lose an engineer's trust in every other
     * number on the screen.
     */
    @Test
    @DisplayName("labour cost is flagged provisional while any attendance row is unverified")
    void unverifiedLabourCostIsDeclaredProvisional() throws Exception {
        JsonNode prefill = prefill("uttam");

        assertThat(prefill.get("labourCostProvisional").asBoolean()).isTrue();
        JsonNode labour = prefill.get("labour");
        assertThat(labour.get("unverifiedCount").asInt())
                .isEqualTo(count("workflow_status NOT IN ('VERIFIED','LOCKED')"));
        // Nothing is signed, so the whole cost is provisional rather than part of it.
        assertThat(decimal(labour, "unverifiedCost"))
                .isEqualByComparingTo(decimal(labour, "cost"));
    }

    /** The labour table the DPR prints: one line per trade and contractor, from the muster. */
    @Test
    @DisplayName("the labour lines group the muster by trade and contractor")
    void labourLinesGroupByTradeAndContractor() throws Exception {
        JsonNode lines = prefill("uttam").get("labour").get("lines");

        int headCount = 0;
        for (JsonNode line : lines) {
            headCount += line.get("headCount").asInt();
            assertThat(line.get("labourSupplierName").asText())
                    .isEqualTo("Kausani Labour Co-operative");
        }
        assertThat(headCount).as("every man on the muster appears on exactly one line")
                .isEqualTo(3);
        // A bar bender, a helper and a mason: three trades, so three lines.
        assertThat(lines).hasSize(3);
    }

    /**
     * The double-counting guard, on the screen where it is easiest to get wrong. A supervisor
     * confirming "what did today cost" must not be handed total booked.
     */
    @Test
    @DisplayName("the expense prefill separates cost incurred from total booked")
    void expensePrefillKeepsCostIncurredApartFromTotalBooked() throws Exception {
        JsonNode expense = prefill("uttam").get("expense");

        assertThat(decimal(expense, "totalBooked")).isEqualByComparingTo(expenseSum(null));
        assertThat(decimal(expense, "totalBooked")).isEqualByComparingTo("1800.00");
        // Cartage is neither a material purchase nor a wage payment, so all of it is cost.
        assertThat(decimal(expense, "costIncurred")).isEqualByComparingTo("1800.00");
        assertThat(decimal(expense, "materialPurchases")).isEqualByComparingTo("0");
        assertThat(decimal(expense, "labourDisbursements")).isEqualByComparingTo("0");
        // The bill is still a draft, and the prefill says so rather than hiding it.
        assertThat(expense.get("unapprovedCount").asInt()).isEqualTo(1);
    }

    /**
     * A material purchase must not reach cost incurred: it becomes inventory and is costed again
     * when the material is issued. Booked on the same day, it has to raise total booked and
     * leave cost incurred exactly where it was.
     */
    @Test
    @DisplayName("a material purchase raises total booked and never cost incurred")
    void materialPurchaseStaysOutOfCostIncurred() throws Exception {
        String uttam = loginToken("uttam");
        BigDecimal costBefore = decimal(prefill("uttam").get("expense"), "costIncurred");

        UUID expenseId = UUID.randomUUID();
        try {
            mockMvc.perform(post("/api/v1/expenses")
                            .header("Authorization", "Bearer " + uttam)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"id":"%s","siteId":"%s","expenseDate":"%s",
                                     "categoryId":"50000000-0000-0000-0000-000000000002",
                                     "subcategoryId":"%s",
                                     "description":"PREFILL-TEST cement, 40 bags",
                                     "billNumber":"PREFILL-TEST/1","amountBeforeTax":16000,
                                     "gstPercent":0}"""
                                    .formatted(expenseId, SITE_A, SEEDED_DAY,
                                            subcategoryId("MAT-PURCHASE"))))
                    .andExpect(status().isCreated());

            JsonNode expense = prefill("uttam").get("expense");
            assertThat(decimal(expense, "materialPurchases")).isEqualByComparingTo("16000.00");
            assertThat(decimal(expense, "totalBooked")).isEqualByComparingTo("17800.00");
            assertThat(decimal(expense, "costIncurred"))
                    .as("a purchase is inventory, not cost")
                    .isEqualByComparingTo(costBefore);
        } finally {
            jdbc.update("DELETE FROM expenses WHERE id = ?::uuid", expenseId.toString());
        }
    }

    /**
     * Suggestions, not entries — and each with the evidence behind it. Cement issued against a
     * line proves somebody worked on it and says nothing about how much got built, so the
     * quantity stays the supervisor's own claim.
     */
    @Test
    @DisplayName("work items are suggested from what the day charged, with the reason on each")
    void workItemsAreSuggestedWithTheirEvidence() throws Exception {
        JsonNode suggestions = prefill("uttam").get("suggestedWorkItems");

        assertThat(suggestions).hasSize(2);
        for (JsonNode suggestion : suggestions) {
            assertThat(suggestion.get("because").asText()).isEqualTo("labour charged");
            assertThat(suggestion.has("boqItemId")).isTrue();
            // A suggestion carries no quantity. Nothing here pre-fills a measurement.
            assertThat(suggestion.has("quantity")).isFalse();
        }
        assertThat(suggestions.get(0).get("itemNumber").asText()).isEqualTo("B-003");
        assertThat(suggestions.get(1).get("itemNumber").asText()).isEqualTo("B-007");
        assertThat(suggestionIds(suggestions)).containsExactlyInAnyOrder(BOQ_BRICKWORK_14, BOQ_STEEL);
    }

    /** The prefill points at the report already covering the day rather than inviting a second. */
    @Test
    @DisplayName("the prefill names the report that already covers the day")
    void prefillNamesAnExistingReport() throws Exception {
        mockMvc.perform(get("/api/v1/dprs/prefill")
                        .param("siteId", SITE_A)
                        .param("date", SEEDED_DAY.toString())
                        .header("Authorization", "Bearer " + loginToken("uttam")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportExists").value(true))
                .andExpect(jsonPath("$.existingDprId")
                        .value("82000000-0000-0000-0000-000000000002"))
                .andExpect(jsonPath("$.siteName").value("Kausani Main Block"));
    }

    /**
     * The sentence that travels with the numbers. Written once on the server so a phone, a desk
     * screen and a PDF cannot each explain the figures differently.
     */
    @Test
    @DisplayName("the prefill carries a plain-English caveat about which figures add up")
    void prefillExplainsWhichFiguresAddUp() throws Exception {
        String caveat = prefill("uttam").get("caveat").asText();

        assertThat(caveat).contains("Material received is inventory, not cost");
        assertThat(caveat).contains("must not be added to anything");
    }

    /** A day nobody has worked yet has nothing to confirm, and says so with zeros, not an error. */
    @Test
    @DisplayName("a day with no records prefills as zeros rather than failing")
    void anEmptyDayPrefillsAsZero() throws Exception {
        mockMvc.perform(get("/api/v1/dprs/prefill")
                        .param("siteId", SITE_A)
                        .param("date", "2025-05-04")
                        .header("Authorization", "Bearer " + loginToken("uttam")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.labour.presentCount").value(0))
                .andExpect(jsonPath("$.labour.cost").value(0))
                .andExpect(jsonPath("$.material.consumedValue").value(0))
                .andExpect(jsonPath("$.expense.costIncurred").value(0))
                .andExpect(jsonPath("$.labourCostProvisional").value(false))
                .andExpect(jsonPath("$.suggestedWorkItems").isEmpty())
                .andExpect(jsonPath("$.reportExists").value(false));
    }

    /** Site scoping applies to the prefill like everything else: Vivek is posted to KSN-A only. */
    @Test
    @DisplayName("a supervisor cannot prefill a report for a site he is not posted to")
    void prefillIsSiteScoped() throws Exception {
        mockMvc.perform(get("/api/v1/dprs/prefill")
                        .param("siteId", "31000000-0000-0000-0000-000000000002")
                        .param("date", SEEDED_DAY.toString())
                        .header("Authorization", "Bearer " + loginToken("vivek")))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode prefill(String username) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/dprs/prefill")
                        .param("siteId", SITE_A)
                        .param("date", SEEDED_DAY.toString())
                        .header("Authorization", "Bearer " + loginToken(username)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static java.util.List<String> suggestionIds(JsonNode suggestions) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        suggestions.forEach(node -> ids.add(node.get("boqItemId").asText()));
        return ids;
    }

    /** The same figure, summed straight out of the table the prefill claims to reflect. */
    private BigDecimal sum(String expression) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(%s), 0) FROM attendance_records
                WHERE site_id = ?::uuid AND attendance_date = ?
                  AND workflow_status <> 'CANCELLED' AND status IN ('PRESENT','HALF_DAY')
                """.formatted(expression), BigDecimal.class, SITE_A, SEEDED_DAY);
    }

    private int count(String predicate) {
        Integer value = jdbc.queryForObject("""
                SELECT count(*) FROM attendance_records
                WHERE site_id = ?::uuid AND attendance_date = ?
                  AND workflow_status <> 'CANCELLED' AND (%s)
                """.formatted(predicate), Integer.class, SITE_A, SEEDED_DAY);
        return value == null ? 0 : value;
    }

    private BigDecimal expenseSum(String predicate) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(total_amount), 0) FROM expenses
                WHERE site_id = ?::uuid AND expense_date = ?
                  AND workflow_status <> 'VOIDED' AND (%s)
                """.formatted(predicate == null ? "1 = 1" : predicate),
                BigDecimal.class, SITE_A, SEEDED_DAY);
    }

    private String subcategoryId(String code) {
        return jdbc.queryForObject(
                "SELECT id::text FROM expense_categories WHERE code = ? AND org_id = ?::uuid",
                String.class, code, "10000000-0000-0000-0000-000000000001");
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asText());
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

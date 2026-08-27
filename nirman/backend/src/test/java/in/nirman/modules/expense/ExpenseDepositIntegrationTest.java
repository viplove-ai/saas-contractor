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
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The part of a bill that is coming back (V48).
 *
 * <p>An electricity connection costs ₹18,000 and ₹12,000 of it is a security against the
 * meter. The whole ₹18,000 leaves the bank and the vendor is paid all of it — but ₹12,000 of
 * it is still the company's, and reporting it as the day's cost overstates the job in exactly
 * the way counting a material purchase twice does. Worse, because nothing ever reports it
 * back: the refund arrives months later and lands nowhere.</p>
 *
 * <p>So the tests below hold three lines. <b>The deposit is out of cost incurred</b> from the
 * day the bill is booked, and the five figures still add to total booked. <b>Settling is a
 * register</b>, because a deposit comes back in parts. And <b>a write-off is not a quiet
 * cost</b>: it closes the row with a reason and books nothing, because the loss belongs to
 * the day somebody decided it.</p>
 */
class ExpenseDepositIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";
    private static final String TEST_MARK = "DEPOSIT-TEST";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeTestExpenses() {
        jdbc.update("""
                DELETE FROM expense_refunds WHERE expense_id IN
                    (SELECT id FROM expenses WHERE description LIKE ?)""", TEST_MARK + "%");
        jdbc.update("""
                DELETE FROM approvals WHERE entity_id IN
                    (SELECT id FROM expenses WHERE description LIKE ?)""", TEST_MARK + "%");
        jdbc.update("DELETE FROM payments WHERE expense_id IN "
                + "(SELECT id FROM expenses WHERE description LIKE ?)", TEST_MARK + "%");
        jdbc.update("DELETE FROM expenses WHERE description LIKE ?", TEST_MARK + "%");
    }

    // ------------------------------------------------------------------ what the bill says

    @Test
    @DisplayName("a deposit is part of the bill and out of what the bill cost")
    void theDepositIsCarvedOutOfTheCost() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "18000", "12000", "electricity connection");

        mockMvc.perform(get("/api/v1/expenses/" + id)
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(status().isOk())
                // The vendor is still owed all of it.
                .andExpect(jsonPath("$.totalAmount").value(18000.00))
                .andExpect(jsonPath("$.payableAmount").value(18000.00))
                .andExpect(jsonPath("$.refundableAmount").value(12000.00))
                .andExpect(jsonPath("$.outstandingDeposit").value(12000.00))
                .andExpect(jsonPath("$.depositStatus").value("OUTSTANDING"))
                // And this is what the work actually cost.
                .andExpect(jsonPath("$.spentAmount").value(6000.00));
    }

    /**
     * The figure the whole carve-out exists to protect.
     *
     * <p>₹18,000 left the books and ₹6,000 of it was spent on the work. A site whose cost
     * tile said ₹18,000 would be overstated by twice what it actually paid for — and nothing
     * would ever report the ₹12,000 back, because the refund arrives months later and used to
     * land nowhere. So the deposit is out of cost incurred and reported beside it, which is
     * what makes the omission checkable rather than a matter of trust.</p>
     */
    @Test
    @DisplayName("the day's cost is what was spent, and the deposit is reported beside it")
    void theDepositIsOutOfCostAndStillReported() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "18000", "12000", "electricity connection");
        approve(uttam, id);

        JsonNode cash = json(get("/api/v1/dashboard/site/" + SITE_A)
                .param("from", LocalDate.now().toString())
                .param("to", LocalDate.now().toString()), uttam).get("cash");

        assertThat(cash.get("totalBooked").decimalValue())
                .as("the whole bill still left the books")
                .isEqualByComparingTo("18000.00");
        assertThat(cash.get("costIncurred").decimalValue())
                .as("and only the spent part of it cost the job anything")
                .isEqualByComparingTo("6000.00");
        assertThat(cash.get("depositsPlaced").decimalValue()).isEqualByComparingTo("12000.00");
        assertThat(cash.get("depositsOutstanding").decimalValue())
                .isEqualByComparingTo("12000.00");
    }

    @Test
    @DisplayName("a deposit larger than the bill it came on is refused with a sentence")
    void aDepositCannotExceedItsBill() throws Exception {
        String uttam = loginToken("uttam");
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("4500", "12000", "typed the wrong way round")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://nirman/errors/expense.deposit-above-total"))
                .andExpect(jsonPath("$.detail").value(containsString("part of the bill")));
    }

    /**
     * A deposit comes back in one piece from one payer. Splitting the bill would leave two
     * answers to whose refund it is when the cheque arrives.
     */
    @Test
    @DisplayName("a bill carrying a deposit cannot be split between the site and the company")
    void aDepositIsNotSplit() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "18000", "12000", "electricity connection");
        submit(uttam, id).andExpect(status().isOk());

        decide(uttam, id, """
                {"action":"APPROVE","allocation":"SPLIT","siteShare":9000}""")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://nirman/errors/expense.deposit-not-splittable"));

        // The company carries the whole of an office connection, deposit and all — that is a
        // different question and it is still allowed.
        decide(uttam, id, "{\"action\":\"APPROVE\",\"allocation\":\"COMPANY\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.costAllocation").value("COMPANY"));
    }

    // ------------------------------------------------------------------ settling it later

    @Test
    @DisplayName("a deposit comes back in parts, and the register follows it down to nothing")
    void aDepositIsSettledInParts() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "18000", "12000", "electricity connection");
        approve(uttam, id);

        // Half of it adjusted against a later bill.
        settle(uttam, id, """
                {"outcome":"RECEIVED","settledOn":"%s","amount":5000,
                 "paymentMode":"ADJUSTMENT","referenceNumber":"CN-4471"}"""
                .formatted(LocalDate.now()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RECEIVED"));

        mockMvc.perform(get("/api/v1/expenses/" + id)
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(jsonPath("$.refundedAmount").value(5000.00))
                .andExpect(jsonPath("$.outstandingDeposit").value(7000.00))
                .andExpect(jsonPath("$.depositStatus").value("PARTIAL"));

        // The rest by cheque.
        settle(uttam, id, """
                {"outcome":"RECEIVED","settledOn":"%s","amount":7000,
                 "paymentMode":"CHEQUE","referenceNumber":"114552"}"""
                .formatted(LocalDate.now()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/expenses/" + id)
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(jsonPath("$.outstandingDeposit").value(0))
                .andExpect(jsonPath("$.depositStatus").value("SETTLED"));

        // And nothing about the bill itself moved: the cost was never the deposit's to change.
        mockMvc.perform(get("/api/v1/expenses/" + id)
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(jsonPath("$.totalAmount").value(18000.00))
                .andExpect(jsonPath("$.spentAmount").value(6000.00));
    }

    @Test
    @DisplayName("more than is outstanding is not this deposit coming back")
    void nothingSettlesAboveTheOutstanding() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "18000", "12000", "electricity connection");
        approve(uttam, id);

        settle(uttam, id, """
                {"outcome":"RECEIVED","settledOn":"%s","amount":15000}"""
                .formatted(LocalDate.now()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://nirman/errors/deposit.exceeds-outstanding"));
    }

    @Test
    @DisplayName("a bill with no deposit on it has nothing to settle")
    void anOrdinaryBillHasNothingToSettle() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "4000", null, "cartage");
        approve(uttam, id);

        settle(uttam, id, """
                {"outcome":"RECEIVED","settledOn":"%s","amount":1000}"""
                .formatted(LocalDate.now()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://nirman/errors/deposit.none-on-expense"));
    }

    @Test
    @DisplayName("giving up on a deposit needs a reason, and books no cost")
    void aWriteOffNeedsAReasonAndCostsNothing() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "18000", "12000", "electricity connection");
        approve(uttam, id);

        settle(uttam, id, """
                {"outcome":"WRITTEN_OFF","settledOn":"%s","amount":12000}"""
                .formatted(LocalDate.now()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://nirman/errors/deposit.writeoff-reason-required"));

        settle(uttam, id, """
                {"outcome":"WRITTEN_OFF","settledOn":"%s","amount":12000,
                 "reason":"Board says the meter was never surrendered; papers lost"}"""
                .formatted(LocalDate.now()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/expenses/" + id)
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(jsonPath("$.writtenOffAmount").value(12000.00))
                .andExpect(jsonPath("$.outstandingDeposit").value(0))
                .andExpect(jsonPath("$.depositStatus").value("SETTLED"))
                // The loss belongs to the day it was decided, not to the day the connection
                // was taken. This bill still says what it always said.
                .andExpect(jsonPath("$.spentAmount").value(6000.00));
    }

    @Test
    @DisplayName("nothing settles against a bill nobody has approved")
    void nothingSettlesAgainstADraft() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "18000", "12000", "electricity connection");

        settle(uttam, id, """
                {"outcome":"RECEIVED","settledOn":"%s","amount":1000}"""
                .formatted(LocalDate.now()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://nirman/errors/deposit.expense-not-approved"));
    }

    // ------------------------------------------------------------------ the register

    @Test
    @DisplayName("the register answers what is still out there, and with whom")
    void theRegisterAnswersWhatIsOutThere() throws Exception {
        String uttam = loginToken("uttam");
        String open = createExpense(uttam, "18000", "12000", "electricity connection");
        approve(uttam, open);
        String closed = createExpense(uttam, "30000", "25000", "mixer hire deposit");
        approve(uttam, closed);
        settle(uttam, closed, """
                {"outcome":"RECEIVED","settledOn":"%s","amount":25000,"paymentMode":"NEFT"}"""
                .formatted(LocalDate.now()))
                .andExpect(status().isOk());

        JsonNode openOnly = json(get("/api/v1/deposits").param("siteId", SITE_A), uttam);
        assertThat(rowFor(openOnly, open)).isNotNull();
        assertThat(rowFor(openOnly, closed))
                .as("a settled deposit is off the open register")
                .isNull();
        assertThat(rowFor(openOnly, open).get("outstandingAmount").decimalValue())
                .isEqualByComparingTo("12000.00");

        JsonNode everything = json(get("/api/v1/deposits")
                .param("siteId", SITE_A)
                .param("openOnly", "false"), uttam);
        JsonNode settled = rowFor(everything, closed);
        assertThat(settled).isNotNull();
        assertThat(settled.get("status").asText()).isEqualTo("SETTLED");
        assertThat(settled.get("settlements")).hasSize(1);
        assertThat(settled.get("settlements").get(0).get("paymentMode").asText())
                .isEqualTo("NEFT");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode rowFor(JsonNode register, String expenseId) {
        for (JsonNode row : register.get("rows")) {
            if (row.get("expenseId").asText().equals(expenseId)) {
                return row;
            }
        }
        return null;
    }

    private String createExpense(String token, String amount, String refundable, String what)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(amount, refundable, what)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String body(String amount, String refundable, String what) {
        return """
                {"id":"%s","siteId":"%s","expenseDate":"%s","categoryId":"%s",
                 "description":"%s %s","billNumber":"BILL-%s",
                 "amountBeforeTax":%s,"gstPercent":0,"paymentMode":"CASH"%s}"""
                .formatted(UUID.randomUUID(), SITE_A, LocalDate.now(), head("MISC-SITE"),
                        TEST_MARK, what, UUID.randomUUID().toString().substring(0, 8), amount,
                        refundable == null ? "" : ",\"refundableAmount\":" + refundable);
    }

    /**
     * All the way through the chain. A bill above the organisation's first-level limit routes
     * two levels, and a test that approved once would leave it sitting at L1_APPROVED — which
     * is a real state and not the one these tests are about.
     */
    private void approve(String token, String id) throws Exception {
        submit(token, id).andExpect(status().isOk());
        for (int level = 0; level < 3; level++) {
            String state = objectMapper.readTree(decide(token, id, "{\"action\":\"APPROVE\"}")
                            .andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString())
                    .get("workflowStatus").asText();
            if ("APPROVED".equals(state)) {
                return;
            }
        }
        throw new IllegalStateException("expense " + id + " never reached APPROVED");
    }

    private ResultActions submit(String token, String id) throws Exception {
        return mockMvc.perform(post("/api/v1/expenses/" + id + "/submit")
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions decide(String token, String id, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/expenses/" + id + "/approve")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions settle(String token, String expenseId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/deposits/" + expenseId + "/settlements")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String head(String code) {
        return jdbc.queryForObject("SELECT id FROM expense_categories WHERE code = ?",
                String.class, code);
    }

    private JsonNode json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
                                  request, String token) throws Exception {
        MvcResult result = mockMvc.perform(request.header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
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

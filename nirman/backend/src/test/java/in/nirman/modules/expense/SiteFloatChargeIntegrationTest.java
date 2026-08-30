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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The other way a bill gets settled: out of the float in somebody's pocket (V49).
 *
 * <p>{@link CashReconciliationIntegrationTest} covers the batch route — the holder submits a
 * settlement listing his bills and the office approves it. That is right for a fortnight of
 * pocket receipts and wrong for the ordinary case, which is one bill the supervisor paid at the
 * counter while the lorry stood at the gate. Under the old flow that bill sat in the payable
 * queue looking like money owed to a shopkeeper who had been paid an hour after delivery.</p>
 *
 * <p>Two things these tests exist to pin down. First, the identity survives: a float charge is a
 * payment, so approved cost, cash paid and payable still reconcile — the supplier <i>was</i>
 * paid, and only the source of the cash is different. Second, <b>the float may be overdrawn</b>,
 * because a man who bought ₹7,000 of steel carrying ₹5,000 is owed ₹2,000, and refusing that row
 * never stopped it happening.</p>
 */
class SiteFloatChargeIntegrationTest extends AbstractIntegrationTest {

    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";
    private static final String SEEDED_FLOAT = "80000000-0000-0000-0000-000000000001";
    /** Vivek Aggarwal — the one seeded login that is a pure supervisor, and the float's holder. */
    private static final String VIVEK = "20000000-0000-0000-0000-000000000003";
    private static final String TEST_MARK = "V49-FLOAT";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeTestRecords() {
        jdbc.update("""
                DELETE FROM advance_settlement_expenses WHERE expense_id IN
                    (SELECT id FROM expenses WHERE description LIKE ?)""", TEST_MARK + "%");
        jdbc.update("""
                DELETE FROM approvals WHERE entity_id IN
                    (SELECT id FROM expenses WHERE description LIKE ?)""", TEST_MARK + "%");
        jdbc.update("""
                DELETE FROM approvals WHERE entity_type = 'ADVANCE_SETTLEMENT' AND entity_id IN
                    (SELECT id FROM advance_settlements WHERE advance_id IN
                        (SELECT id FROM site_advances WHERE purpose LIKE ?))""", TEST_MARK + "%");
        jdbc.update("DELETE FROM payments WHERE expense_id IN "
                + "(SELECT id FROM expenses WHERE description LIKE ?)", TEST_MARK + "%");
        jdbc.update("DELETE FROM expenses WHERE description LIKE ?", TEST_MARK + "%");
        jdbc.update("DELETE FROM advance_settlements WHERE advance_id IN "
                + "(SELECT id FROM site_advances WHERE purpose LIKE ?)", TEST_MARK + "%");
        jdbc.update("DELETE FROM site_advances WHERE purpose LIKE ?", TEST_MARK + "%");
        // The seeded float goes back to untouched: ₹20,000 open, nothing spent.
        jdbc.update("""
                UPDATE site_advances SET adjusted_amount = 0, returned_amount = 0,
                    settlement_status = 'OPEN', closed_at = NULL WHERE id = ?::uuid""",
                SEEDED_FLOAT);
    }

    // ------------------------------------------------------------------ charging a bill

    /**
     * The whole feature in one test. The supplier was paid — by the supervisor, at the counter
     * — so the bill leaves the payable queue exactly as a bank transfer would take it out, and
     * what changes is only whose money it was.
     */
    @Test
    @DisplayName("charging a bill to a float pays the bill and empties that much of the pocket")
    void chargingABillMovesBothSides() throws Exception {
        String uttam = loginToken("uttam");
        String bill = approvedExpense(uttam, "8000", "cartage paid at the gate");

        assertThat(floatBalance(SEEDED_FLOAT)).isEqualByComparingTo("20000.00");

        chargeToFloat(uttam, bill, VIVEK)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(8000.00))
                .andExpect(jsonPath("$.paymentMode").value("SITE_FLOAT"))
                .andExpect(jsonPath("$.siteAdvanceNumber").value("SAD-2025-0001"));

        // The identity holds: 8,000 approved, 8,000 paid, nothing payable.
        assertAmounts(uttam, bill, "8000.00", "8000.00", "0.00");
        assertThat(paymentStatus(bill)).isEqualTo("PAID");
        // And the float carries it: 20,000 handed over less 8,000 spent.
        assertThat(floatBalance(SEEDED_FLOAT)).isEqualByComparingTo("12000.00");
        assertThat(floatStatus(SEEDED_FLOAT)).isEqualTo("PARTIALLY_SETTLED");
    }

    /**
     * The row V1 could not hold and V49 exists for. A lorry does not wait while an accountant
     * is telephoned, so the man at the gate pays past his float — and the company owes him the
     * difference whether or not the register is willing to say so.
     */
    @Test
    @DisplayName("a float may be overdrawn, and the balance then says the company owes him")
    void aFloatMayBeOverdrawn() throws Exception {
        String uttam = loginToken("uttam");
        chargeToFloat(uttam, approvedExpense(uttam, "18000", "steel"), VIVEK)
                .andExpect(status().isCreated());
        chargeToFloat(uttam, approvedExpense(uttam, "7000", "more steel"), VIVEK)
                .andExpect(status().isCreated());

        // 20,000 handed over, 25,000 spent. He is out of pocket by 5,000.
        assertThat(floatBalance(SEEDED_FLOAT)).isEqualByComparingTo("-5000.00");
        assertThat(floatStatus(SEEDED_FLOAT)).isEqualTo("OVERSPENT");

        JsonNode row = holderBalance(uttam, VIVEK);
        assertThat(new BigDecimal(row.get("inHandAmount").asText()))
                .isEqualByComparingTo("-5000.00");
        assertThat(new BigDecimal(row.get("issuedAmount").asText()))
                .isEqualByComparingTo("20000.00");
        assertThat(new BigDecimal(row.get("spentAmount").asText()))
                .isEqualByComparingTo("25000.00");
    }

    /**
     * An overdrawn float is still open business, so it stays on the register. Dropping it as
     * "settled" would hide the one position the office most needs in front of it — a man it
     * owes money to.
     */
    @Test
    @DisplayName("an overdrawn float is still listed among the open ones")
    void overdrawnFloatsStayOnTheRegister() throws Exception {
        String uttam = loginToken("uttam");
        chargeToFloat(uttam, approvedExpense(uttam, "25000", "everything"), VIVEK)
                .andExpect(status().isCreated());

        MvcResult open = mockMvc.perform(get("/api/v1/advances/balances")
                        .param("siteId", SITE_A)
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = objectMapper.readTree(open.getResponse().getContentAsString());
        assertThat(rows).anySatisfy(row ->
                assertThat(row.get("advanceNumber").asText()).isEqualTo("SAD-2025-0001"));
    }

    /** Nothing goes out against a bill nobody has agreed to, whichever pocket it comes from. */
    @Test
    @DisplayName("an unapproved bill cannot be charged to anybody's float")
    void unapprovedBillsCannotBeCharged() throws Exception {
        String uttam = loginToken("uttam");
        String draft = createExpense(uttam, "3000", "still a draft");

        chargeToFloat(uttam, draft, VIVEK)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value(containsString("Nothing is settled until it is approved")));
    }

    /**
     * Deciding that a supervisor already paid a supplier is deciding the company's debt has
     * moved to its own man. That is the accountant's, on the same permission as recording a
     * bank transfer — and deliberately not the author's.
     */
    @Test
    @DisplayName("a supervisor cannot charge a bill to his own float")
    void supervisorCannotChargeHisOwnFloat() throws Exception {
        String uttam = loginToken("uttam");
        String vivek = loginToken("vivek");
        String bill = approvedExpense(uttam, "4000", "consumables");

        chargeToFloat(vivek, bill, VIVEK).andExpect(status().isForbidden());
        assertThat(floatBalance(SEEDED_FLOAT)).isEqualByComparingTo("20000.00");
    }

    /** Charging a bill to somebody the office has never handed cash to is a mistake, said so. */
    @Test
    @DisplayName("a bill cannot be charged to somebody holding no float at this site")
    void cannotChargeSomebodyWithNoFloat() throws Exception {
        String uttam = loginToken("uttam");
        String bill = approvedExpense(uttam, "1000", "nobody's float");

        // Uttam is posted to the site but has never been handed a float there.
        chargeToFloat(uttam, bill, "20000000-0000-0000-0000-000000000002")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value(containsString("No float has been handed to that person")));
    }

    /**
     * The double-clearing hole the two routes would otherwise leave between them. A bill
     * charged to a float has already taken those rupees out of that pocket; claiming it again
     * on a settlement would take them out a second time.
     */
    @Test
    @DisplayName("a bill already charged to a float cannot also be claimed on a settlement")
    void aChargedBillCannotBeSettledAgain() throws Exception {
        String uttam = loginToken("uttam");
        String vivek = loginToken("vivek");
        String bill = approvedExpense(uttam, "6000", "claimed twice");

        chargeToFloat(uttam, bill, VIVEK).andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/advances/" + SEEDED_FLOAT + "/settlements")
                        .header("Authorization", "Bearer " + vivek)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"settlementDate":"%s","expenseIds":["%s"]}"""
                                .formatted(LocalDate.now(), bill)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value(containsString("has already been settled")));
    }

    // ------------------------------------------------------------------ the register

    /** What the field is shown on the expense form: his own pocket, and nobody else's. */
    @Test
    @DisplayName("a holder reads his own float and only his own")
    void myFloatIsMineAlone() throws Exception {
        String vivek = loginToken("vivek");

        MvcResult mine = mockMvc.perform(get("/api/v1/advances/my-float")
                        .param("siteId", SITE_A)
                        .header("Authorization", "Bearer " + vivek))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = objectMapper.readTree(mine.getResponse().getContentAsString());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("userId").asText()).isEqualTo(VIVEK);
        assertThat(new BigDecimal(rows.get(0).get("inHandAmount").asText()))
                .isEqualByComparingTo("20000.00");
    }

    /**
     * Handing over cash, then reading it back. The register is what the treasury screen draws,
     * and a float that does not show up on it a moment after it was issued is a float the
     * office will hand over twice.
     */
    @Test
    @DisplayName("a float issued shows up on the holder's balance")
    void issuingAFloatMovesTheBalance() throws Exception {
        String uttam = loginToken("uttam");

        mockMvc.perform(post("/api/v1/advances")
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"siteId":"%s","issuedToUserId":"%s","advanceDate":"%s",
                                 "amount":5000,"paymentMode":"CASH",
                                 "purpose":"%s topping him up"}"""
                                .formatted(SITE_A, VIVEK, LocalDate.now(), TEST_MARK)))
                .andExpect(status().isCreated());

        // The seeded ₹20,000 and this ₹5,000 are one person's position, summed per call.
        assertThat(new BigDecimal(holderBalance(uttam, VIVEK).get("inHandAmount").asText()))
                .isEqualByComparingTo("25000.00");
    }

    /**
     * Who a float can be handed to. The user list is behind {@code user:read}, an
     * administrator's, so an accountant could reach the call that hands over the money and not
     * the one that says who is standing there to take it.
     */
    @Test
    @DisplayName("the holder picker offers the people posted to the site")
    void holdersAreThePeoplePostedToTheSite() throws Exception {
        String uttam = loginToken("uttam");

        MvcResult result = mockMvc.perform(get("/api/v1/advances/holders")
                        .param("siteId", SITE_A)
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(rows).anySatisfy(row ->
                assertThat(row.get("userId").asText()).isEqualTo(VIVEK));
        // Viplove is posted to the other site only, and must not be offered here.
        assertThat(rows).noneSatisfy(row -> assertThat(row.get("userId").asText())
                .isEqualTo("20000000-0000-0000-0000-000000000001"));
    }

    /** Handing over petty cash is the office's. A supervisor topping himself up is the hole. */
    @Test
    @DisplayName("a supervisor cannot see who could be handed a float")
    void supervisorCannotReadTheHolderPicker() throws Exception {
        mockMvc.perform(get("/api/v1/advances/holders")
                        .param("siteId", SITE_A)
                        .header("Authorization", "Bearer " + loginToken("vivek")))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions chargeToFloat(String token, String expenseId, String holderUserId)
            throws Exception {
        return mockMvc.perform(post("/api/v1/expenses/" + expenseId + "/charge-to-float")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"holderUserId":"%s","paymentDate":"%s","remarks":"paid at the counter"}"""
                        .formatted(holderUserId, LocalDate.now())));
    }

    private JsonNode holderBalance(String token, String userId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/advances/float-balances")
                        .param("siteId", SITE_A)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = objectMapper.readTree(result.getResponse().getContentAsString());
        for (JsonNode row : rows) {
            if (row.get("userId").asText().equals(userId)) {
                return row;
            }
        }
        throw new AssertionError("no float balance row for " + userId);
    }

    private void assertAmounts(String token, String id, String total, String paid,
                               String payable) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/expenses/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(new BigDecimal(body.get("totalAmount").asText())).isEqualByComparingTo(total);
        assertThat(new BigDecimal(body.get("paidAmount").asText())).isEqualByComparingTo(paid);
        assertThat(new BigDecimal(body.get("payableAmount").asText()))
                .isEqualByComparingTo(payable);
    }

    private String approvedExpense(String token, String amount, String what) throws Exception {
        String id = createExpense(token, amount, what);
        mockMvc.perform(post("/api/v1/expenses/" + id + "/submit")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        for (int level = 0; level < 3; level++) {
            String status = statusOf(id);
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

    private String createExpense(String token, String amount, String what) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","siteId":"%s","expenseDate":"%s","categoryId":"%s",
                                 "vendorId":"%s","description":"%s %s","billNumber":"B-%s",
                                 "amountBeforeTax":%s,"gstPercent":0,"paymentMode":"CASH"}"""
                                .formatted(UUID.randomUUID(), SITE_A, LocalDate.now(),
                                        categoryId(), vendorId(), TEST_MARK, what,
                                        UUID.randomUUID().toString().substring(0, 8), amount)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String statusOf(String expenseId) {
        return jdbc.queryForObject("SELECT workflow_status FROM expenses WHERE id = ?::uuid",
                String.class, expenseId);
    }

    private String paymentStatus(String expenseId) {
        return jdbc.queryForObject("SELECT payment_status FROM expenses WHERE id = ?::uuid",
                String.class, expenseId);
    }

    private BigDecimal floatBalance(String advanceId) {
        return jdbc.queryForObject("SELECT balance_amount FROM site_advances WHERE id = ?::uuid",
                BigDecimal.class, advanceId);
    }

    private String floatStatus(String advanceId) {
        return jdbc.queryForObject("SELECT settlement_status FROM site_advances WHERE id = ?::uuid",
                String.class, advanceId);
    }

    private String loginToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"Nirman@123\"}"
                                .formatted(username)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private String vendorId() {
        return jdbc.queryForObject("SELECT id FROM vendors WHERE code = 'VND-001'", String.class);
    }

    private String categoryId() {
        return jdbc.queryForObject(
                "SELECT id FROM expense_categories WHERE code = 'MISC-SITE'", String.class);
    }
}

package in.nirman.modules.labour;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The payday: what the field sheet does at the foot of the column, through the real
 * endpoints. {@code Total Amount − Advance = Balance Payment}, and the balance payment is
 * the moment the advance is recovered.
 *
 * <p>Every test takes on its own man at the annexe, so nothing here disturbs the exact
 * earned totals the attendance suite pins the seeded workers to, and nothing there can
 * move a balance one of these tests is about to settle.</p>
 */
class WorkerPaydayIntegrationTest extends AbstractIntegrationTest {

    private static final String ANNEXE = "31000000-0000-0000-0000-000000000002";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminToken;

    @BeforeEach
    void signIn() throws Exception {
        adminToken = token("viplove");
    }

    @Test
    @DisplayName("paying the balance posts to the ledger and marks the advance recovered")
    void paydaySettlesTheBalanceAndRecoversTheAdvance() throws Exception {
        // Two days at 600: earned 1,200. Drew 500 on the first: owed 700.
        String worker = takeOn("WP Balance Man", "600");
        verifiedDay(worker, LocalDate.of(2025, 10, 6));
        verifiedDay(worker, LocalDate.of(2025, 10, 7));
        UUID advance = approvedAdvance(worker, "500.00", LocalDate.of(2025, 10, 6));

        JsonNode before = settlement(worker);
        assertThat(before.get("netPayable").decimalValue()).isEqualByComparingTo("700.00");
        assertThat(before.get("openAdvanceAmount").decimalValue())
                .as("approved and deducted, and no payday has closed it")
                .isEqualByComparingTo("500.00");

        MvcResult paid = mockMvc.perform(post("/api/v1/worker-payments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payment(UUID.randomUUID(), worker, "700.00", LocalDate.of(2025, 10, 11))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentNumber").value(org.hamcrest.Matchers.matchesPattern("WPY-2025-\\d{4}")))
                .andExpect(jsonPath("$.advancesRecovered").value(500.00))
                .andReturn();
        assertThat(objectMapper.readTree(paid.getResponse().getContentAsString())
                .get("workerName").asText()).isEqualTo("WP Balance Man");

        JsonNode after = settlement(worker);
        assertThat(after.get("paidAmount").decimalValue()).isEqualByComparingTo("700.00");
        assertThat(after.get("netPayable").decimalValue())
                .as("earned − advance − paid, and the sheet balances")
                .isEqualByComparingTo("0.00");
        assertThat(after.get("openAdvanceAmount").decimalValue()).isEqualByComparingTo("0.00");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT recovered_amount, balance_amount, status FROM worker_advances WHERE id = ?::uuid",
                advance.toString());
        assertThat((BigDecimal) row.get("recovered_amount")).isEqualByComparingTo("500.00");
        assertThat((BigDecimal) row.get("balance_amount")).isEqualByComparingTo("0.00");
        assertThat(row.get("status")).isEqualTo("RECOVERED");

        // One PAYMENT line on the ledger, pointing back at the payday.
        Integer lines = jdbc.queryForObject(
                "SELECT count(*) FROM worker_ledger_entries WHERE worker_id = ?::uuid "
                        + "AND entry_type = 'PAYMENT' AND source_type = 'PAYMENT'",
                Integer.class, worker);
        assertThat(lines).isEqualTo(1);
    }

    @Test
    @DisplayName("a payday closes the advances oldest first and leaves the rest open")
    void olderAdvancesAreRecoveredFirst() throws Exception {
        // One day at 600. Two advances, 400 then 300: he has drawn 700 against 600 earned,
        // owes 100, and nothing can be paid to him. Recovery is a payday's act, so until
        // there is one both advances stay open whatever the wages have covered.
        String worker = takeOn("WP Two Advances", "600");
        verifiedDay(worker, LocalDate.of(2025, 10, 13));
        UUID older = approvedAdvance(worker, "400.00", LocalDate.of(2025, 10, 8));
        UUID newer = approvedAdvance(worker, "300.00", LocalDate.of(2025, 10, 9));

        assertThat(settlement(worker).get("netPayable").decimalValue()).isEqualByComparingTo("-100.00");

        mockMvc.perform(post("/api/v1/worker-payments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payment(UUID.randomUUID(), worker, "1.00", LocalDate.of(2025, 10, 14))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Nothing is owed to him yet.")));

        // A second day: earned 1,200 against 700 drawn, owed 500. Paying it recovers both.
        verifiedDay(worker, LocalDate.of(2025, 10, 15));
        mockMvc.perform(post("/api/v1/worker-payments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payment(UUID.randomUUID(), worker, "500.00", LocalDate.of(2025, 10, 16))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.advancesRecovered").value(700.00));

        assertThat(statusOf(older)).isEqualTo("RECOVERED");
        assertThat(statusOf(newer)).isEqualTo("RECOVERED");
    }

    @Test
    @DisplayName("a man cannot be paid more than the ledger says he is owed")
    void payingPastTheBalanceIsRefused() throws Exception {
        String worker = takeOn("WP Overpaid Man", "600");
        verifiedDay(worker, LocalDate.of(2025, 10, 20));

        mockMvc.perform(post("/api/v1/worker-payments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payment(UUID.randomUUID(), worker, "601.00", LocalDate.of(2025, 10, 21))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("payment.exceeds-payable")))
                .andExpect(jsonPath("$.detail").value(
                        "He is owed ₹600.00. Anything beyond that is an advance against wages "
                                + "not yet earned — record it as one."));

        assertThat(settlement(worker).get("paidAmount").decimalValue())
                .as("a refused payday moves nothing")
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("re-sending the same payday is one row and one ledger line, not two")
    void paymentIsIdempotentOnTheClientId() throws Exception {
        String worker = takeOn("WP Sent Twice", "600");
        verifiedDay(worker, LocalDate.of(2025, 10, 27));
        UUID id = UUID.randomUUID();
        String body = payment(id, worker, "600.00", LocalDate.of(2025, 10, 28));

        mockMvc.perform(post("/api/v1/worker-payments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        // The replay: the balance is already zero, and the first answer comes back rather
        // than a refusal for paying past it.
        mockMvc.perform(post("/api/v1/worker-payments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()));

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM worker_payments WHERE worker_id = ?::uuid", Integer.class, worker);
        assertThat(rows).isEqualTo(1);
        assertThat(settlement(worker).get("paidAmount").decimalValue()).isEqualByComparingTo("600.00");
    }

    @Test
    @DisplayName("the payday register is behind wage:read and lists what the site handed over")
    void paymentsAreListedForTheSite() throws Exception {
        String worker = takeOn("WP Listed Man", "600");
        verifiedDay(worker, LocalDate.of(2025, 11, 3));
        mockMvc.perform(post("/api/v1/worker-payments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payment(UUID.randomUUID(), worker, "600.00", LocalDate.of(2025, 11, 4))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/worker-payments")
                        .param("siteId", ANNEXE).param("workerId", worker)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].amount").value(600.00))
                .andExpect(jsonPath("$.content[0].workerName").value("WP Listed Man"));
    }

    // ------------------------------------------------------------------ helpers

    /** A fresh man at the annexe with a day rate, so no other test's arithmetic is his. */
    private String takeOn(String name, String rate) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/workers")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"%s","mobile":"+91-9800000331","siteId":"%s",
                                 "joiningDate":"2025-10-01","normalRate":%s}"""
                                .formatted(name, ANNEXE, rate)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    /** One full shift, marked, submitted and verified — which is what posts the wage. */
    private void verifiedDay(String worker, LocalDate day) throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/attendance/bulk")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"siteId":"%s","date":"%s","entries":[
                                  {"id":"%s","workerId":"%s","status":"PRESENT","breakMinutes":0,"enteredHours":7}]}"""
                                .formatted(ANNEXE, day, id, worker)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/attendance/submit")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"siteId\":\"" + ANNEXE + "\",\"date\":\"" + day + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/attendance/verify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + id + "\"],\"action\":\"VERIFY\"}"))
                .andExpect(status().isOk());
    }

    private UUID approvedAdvance(String worker, String amount, LocalDate on) throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/worker-advances")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","siteId":"%s","workerId":"%s","advanceDate":"%s",
                                 "amount":%s,"paymentMode":"CASH","purpose":"Cash advance","recoverable":true}"""
                                .formatted(id, ANNEXE, worker, on, amount)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/worker-advances/" + id + "/decision")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isOk());
        return id;
    }

    private static String payment(UUID id, String worker, String amount, LocalDate on) {
        return """
                {"id":"%s","siteId":"%s","workerId":"%s","paymentDate":"%s",
                 "amount":%s,"paymentMode":"CASH","remarks":"Payday"}"""
                .formatted(id, ANNEXE, worker, on, amount);
    }

    private JsonNode settlement(String worker) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/workers/" + worker + "/settlement")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String statusOf(UUID advance) {
        return jdbc.queryForObject("SELECT status FROM worker_advances WHERE id = ?::uuid",
                String.class, advance.toString());
    }

    private String token(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Nirman@123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}

package in.nirman.modules.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.AbstractIntegrationTest;
import in.nirman.InMemoryStorageConfig;
import in.nirman.MovementEvidence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
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
 * The two Phase 4 exit criteria that are about the ledger itself: the moving-average rate is
 * right after mixed-rate receipts, and negative stock is rejected and logged.
 *
 * <p>Every test works with a material it creates itself. Stock is cumulative by nature —
 * a balance is the sum of everything that ever happened to it — so a test that leant on the
 * seeded cement would be a test whose expected numbers depend on which other tests ran
 * first.</p>
 */
// V52 demands a photograph of every movement of stock; the bucket here is a map.
@Import(InMemoryStorageConfig.class)
class StockLedgerIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String STORE_A = "32000000-0000-0000-0000-000000000001";
    private static final String TEST_CODE_PREFIX = "TM-LEDGER-";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeTestMaterials() {
        String materials = "SELECT id FROM materials WHERE code LIKE ?";
        jdbc.update("DELETE FROM stock_transactions WHERE material_id IN (" + materials + ")",
                TEST_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM stock_balances WHERE material_id IN (" + materials + ")",
                TEST_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM stock_violation_log WHERE material_id IN (" + materials + ")",
                TEST_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM goods_receipt_items WHERE material_id IN (" + materials + ")",
                TEST_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM material_issue_items WHERE material_id IN (" + materials + ")",
                TEST_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM goods_receipts WHERE id NOT IN (SELECT grn_id FROM goods_receipt_items)");
        jdbc.update("DELETE FROM material_issues WHERE id NOT IN (SELECT issue_id FROM material_issue_items)");
        jdbc.update("DELETE FROM materials WHERE code LIKE ?", TEST_CODE_PREFIX + "%");
    }

    /**
     * The arithmetic, stated as numbers somebody can check by hand:
     *
     * <pre>
     *   100 @ 400          → 100 bags, ₹40,000, average 400.00
     *   +100 @ 450         → 200 bags, ₹85,000, average 425.00
     *   −50 at the average → 150 bags, ₹63,750, average 425.00  (unchanged)
     *   +50 @ 500          → 200 bags, ₹88,750, average 443.75
     * </pre>
     *
     * <p>The third step is the one worth staring at. Taking material out of a heap does not
     * change what the heap cost, so the average holds and only the value falls — by exactly
     * what the issue was worth at that average.</p>
     */
    @Test
    @DisplayName("the moving average is correct after receipts at three different rates")
    void movingAverageAfterMixedRateReceipts() throws Exception {
        String uttam = loginToken("uttam");
        String bagUnit = unitId(uttam, "BAG");
        String material = createMaterial(uttam, TEST_CODE_PREFIX + "AVG", bagUnit);

        receiveAndVerify(uttam, material, bagUnit, "100", "400");
        assertBalance(uttam, material, "100.0000", "400.0000", "40000.00");

        receiveAndVerify(uttam, material, bagUnit, "100", "450");
        assertBalance(uttam, material, "200.0000", "425.0000", "85000.00");

        issueAndApprove(uttam, material, bagUnit, "50");
        assertBalance(uttam, material, "150.0000", "425.0000", "63750.00");

        receiveAndVerify(uttam, material, bagUnit, "50", "500");
        assertBalance(uttam, material, "200.0000", "443.7500", "88750.00");
    }

    /** The issue is valued at the average in force when it leaves, and that rate is frozen. */
    @Test
    @DisplayName("an issue is valued at the average in force, and the rate is frozen onto the line")
    void issueFreezesTheRateItLeftAt() throws Exception {
        String uttam = loginToken("uttam");
        String bagUnit = unitId(uttam, "BAG");
        String material = createMaterial(uttam, TEST_CODE_PREFIX + "FREEZE", bagUnit);

        receiveAndVerify(uttam, material, bagUnit, "100", "400");
        receiveAndVerify(uttam, material, bagUnit, "100", "450");
        String issueId = issueAndApprove(uttam, material, bagUnit, "40");

        mockMvc.perform(get("/api/v1/inventory/issues/" + issueId)
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].issuedRate").value(425.0000))
                .andExpect(jsonPath("$.lines[0].value").value(17000.00));

        // A later delivery at a different rate must not reprice what is already in the wall.
        receiveAndVerify(uttam, material, bagUnit, "100", "900");
        mockMvc.perform(get("/api/v1/inventory/issues/" + issueId)
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].issuedRate").value(425.0000));
    }

    @Test
    @DisplayName("stock cannot go negative, and the attempt is logged")
    void negativeStockIsRejectedAndLogged() throws Exception {
        String uttam = loginToken("uttam");
        String bagUnit = unitId(uttam, "BAG");
        String material = createMaterial(uttam, TEST_CODE_PREFIX + "NEG", bagUnit);
        receiveAndVerify(uttam, material, bagUnit, "10", "400");

        // Wastage goes straight to the ledger with no document to approve, so it is the
        // shortest path to the guard itself rather than to the pre-check in front of it.
        mockMvc.perform(post("/api/v1/inventory/wastage")
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeId":"%s","materialId":"%s","unitId":"%s","quantity":25,
                                 "wastageDate":"%s","type":"WASTAGE","reason":"Test overdraw"}"""
                                .formatted(STORE_A, material, bagUnit, LocalDate.now())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("cannot go negative")));

        // The balance is untouched...
        assertBalance(uttam, material, "10.0000", "400.0000", "4000.00");

        // ...and the rejected attempt survived the rollback, which is the whole point of
        // recording it in its own transaction.
        Integer violations = jdbc.queryForObject("""
                SELECT count(*) FROM stock_violation_log
                WHERE material_id = ?::uuid AND attempted_qty_base = 25""",
                Integer.class, material);
        assertThat(violations).isEqualTo(1);
    }

    @Test
    @DisplayName("an issue for more than the store holds is refused before anything is written")
    void issueBeyondStockIsRefused() throws Exception {
        String uttam = loginToken("uttam");
        String bagUnit = unitId(uttam, "BAG");
        String material = createMaterial(uttam, TEST_CODE_PREFIX + "ISSUE", bagUnit);
        receiveAndVerify(uttam, material, bagUnit, "10", "400");

        mockMvc.perform(post("/api/v1/inventory/issues")
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MovementEvidence.onIssue(mockMvc, objectMapper, uttam,
"""
                                {"id":"%s","storeId":"%s","issueDate":"%s","purpose":"Test",
                                 "lines":[{"materialId":"%s","unitId":"%s","quantity":40}]}"""
                                .formatted(UUID.randomUUID(), STORE_A, LocalDate.now(),
                                        material, bagUnit))))
                .andExpect(status().isUnprocessableEntity());

        assertBalance(uttam, material, "10.0000", "400.0000", "4000.00");
    }

    @Test
    @DisplayName("verifying the same receipt twice does not deliver the same lorry twice")
    void verifyingTwiceDoesNotDoubleTheStock() throws Exception {
        String uttam = loginToken("uttam");
        String bagUnit = unitId(uttam, "BAG");
        String material = createMaterial(uttam, TEST_CODE_PREFIX + "IDEM", bagUnit);

        String grnId = createReceipt(uttam, material, bagUnit, "60", "400");
        verify(uttam, grnId).andExpect(status().isOk());
        assertBalance(uttam, material, "60.0000", "400.0000", "24000.00");

        // The second call is refused by the state machine, and even if it were not, the
        // ledger would answer the repeat with a no-op.
        verify(uttam, grnId).andExpect(status().isUnprocessableEntity());
        assertBalance(uttam, material, "60.0000", "400.0000", "24000.00");

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM stock_transactions WHERE material_id = ?::uuid",
                Integer.class, material);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("opening stock can be declared only once per store and material")
    void openingStockIsDeclaredOnce() throws Exception {
        String uttam = loginToken("uttam");
        String bagUnit = unitId(uttam, "BAG");
        String material = createMaterial(uttam, TEST_CODE_PREFIX + "OPEN", bagUnit);

        openingStock(uttam, material, bagUnit, "30").andExpect(status().isOk());
        openingStock(uttam, material, bagUnit, "30").andExpect(status().isConflict());
        assertBalance(uttam, material, "30.0000", "415.0000", "12450.00");
    }

    @Test
    @DisplayName("a delivery booked twice under the same client id is one delivery")
    void resendingTheSameReceiptIsOneReceipt() throws Exception {
        String uttam = loginToken("uttam");
        String bagUnit = unitId(uttam, "BAG");
        String material = createMaterial(uttam, TEST_CODE_PREFIX + "RESEND", bagUnit);

        String clientId = UUID.randomUUID().toString();
        String first = postReceipt(uttam, clientId, material, bagUnit, "25", "400")
                .get("grnNumber").asText();
        String second = postReceipt(uttam, clientId, material, bagUnit, "25", "400")
                .get("grnNumber").asText();

        assertThat(second).isEqualTo(first);
        Integer receipts = jdbc.queryForObject(
                "SELECT count(*) FROM goods_receipts WHERE id = ?::uuid", Integer.class, clientId);
        assertThat(receipts).isEqualTo(1);
    }

    @Test
    @DisplayName("a supervisor may book a delivery but not sign it off")
    void supervisorCannotVerifyHisOwnReceipt() throws Exception {
        String uttam = loginToken("uttam");
        String vivek = loginToken("vivek");
        String bagUnit = unitId(uttam, "BAG");
        String material = createMaterial(uttam, TEST_CODE_PREFIX + "PERM", bagUnit);

        // Unpriced, because a supervisor sending a rate is refused outright now (V20).
        String grnId = createReceipt(vivek, material, bagUnit, "20", null);
        verify(vivek, grnId).andExpect(status().isForbidden());

        // And nothing reached the store on the strength of his own say-so.
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM stock_transactions WHERE material_id = ?::uuid",
                Integer.class, material);
        assertThat(rows).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private void assertBalance(String token, String materialId, String quantity, String rate,
                               String value) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/inventory/stock")
                        .param("storeId", STORE_A)
                        .param("materialId", materialId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = objectMapper.readTree(result.getResponse().getContentAsString()).get("rows");
        assertThat(rows).hasSize(1);
        JsonNode row = rows.get(0);
        assertThat(new BigDecimal(row.get("quantityBase").asText()))
                .isEqualByComparingTo(quantity);
        assertThat(new BigDecimal(row.get("movingAvgRate").asText()))
                .isEqualByComparingTo(rate);
        assertThat(new BigDecimal(row.get("stockValue").asText()))
                .isEqualByComparingTo(value);
    }

    private void receiveAndVerify(String token, String materialId, String unitId,
                                  String quantity, String rate) throws Exception {
        String grnId = createReceipt(token, materialId, unitId, quantity, rate);
        verify(token, grnId).andExpect(status().isOk());
    }

    /** A line as it goes out, with the rate left off entirely when nobody has priced it. */
    private static String line(String materialId, String unitId, String quantity, String rate) {
        String priced = rate == null ? "" : ",\"rate\":" + rate;
        return """
                {"materialId":"%s","unitId":"%s","quantity":%s%s}"""
                .formatted(materialId, unitId, quantity, priced);
    }

    private String createReceipt(String token, String materialId, String unitId,
                                 String quantity, String rate) throws Exception {
        return postReceipt(token, UUID.randomUUID().toString(), materialId, unitId, quantity, rate)
                .get("id").asText();
    }

    private JsonNode postReceipt(String token, String clientId, String materialId, String unitId,
                                 String quantity, String rate) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/inventory/goods-receipts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MovementEvidence.onReceipt(mockMvc, objectMapper, token,
"""
                                {"id":"%s","storeId":"%s","receiptDate":"%s",
                                 "challanNumber":"CH-TEST","lines":[%s]}"""
                                .formatted(clientId, STORE_A, LocalDate.now(),
                                        line(materialId, unitId, quantity, rate)))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions verify(String token, String grnId)
            throws Exception {
        return mockMvc.perform(post("/api/v1/inventory/goods-receipts/" + grnId + "/verify")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"VERIFY\"}"));
    }

    private String issueAndApprove(String token, String materialId, String unitId, String quantity)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/inventory/issues")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MovementEvidence.onIssue(mockMvc, objectMapper, token,
"""
                                {"id":"%s","storeId":"%s","issueDate":"%s","purpose":"Ledger test",
                                 "lines":[{"materialId":"%s","unitId":"%s","quantity":%s}]}"""
                                .formatted(UUID.randomUUID(), STORE_A, LocalDate.now(),
                                        materialId, unitId, quantity))))
                .andExpect(status().isCreated())
                .andReturn();
        String issueId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
        mockMvc.perform(post("/api/v1/inventory/issues/" + issueId + "/approve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isOk());
        return issueId;
    }

    private org.springframework.test.web.servlet.ResultActions openingStock(
            String token, String materialId, String unitId, String quantity) throws Exception {
        return mockMvc.perform(post("/api/v1/inventory/opening-stock")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"storeId":"%s","materialId":"%s","unitId":"%s","quantity":%s,
                         "rate":415,"asOfDate":"%s","remarks":"Test opening"}"""
                        .formatted(STORE_A, materialId, unitId, quantity, LocalDate.now())));
    }

    private String createMaterial(String token, String code, String baseUnitId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/materials")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"Ledger test material %s","baseUnitId":"%s",
                                 "gstPercent":0,"minStockLevel":0,"consumable":true}"""
                                .formatted(code, code, baseUnitId)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String unitId(String token, String code) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/units")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode units = objectMapper.readTree(result.getResponse().getContentAsString());
        for (JsonNode unit : units) {
            if (code.equals(unit.get("code").asText())) {
                return unit.get("id").asText();
            }
        }
        throw new IllegalStateException("The dev seed has no unit " + code);
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

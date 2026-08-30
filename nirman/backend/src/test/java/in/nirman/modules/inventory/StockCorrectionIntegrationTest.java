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
 * The storekeeper saying a figure is wrong, and the office being the only one who can change
 * it.
 *
 * <p>Both halves matter and they pull against each other. The man who can see the shed could
 * not move a balance — {@code inventory:adjust} is the office's, because a role that can adjust
 * a balance can hide a loss — so a delivery booked as twelve bags when eleven arrived stayed
 * wrong until a physical count found it a quarter later. And the answer cannot be to hand him
 * the adjustment, because then the reason the ledger is believed goes with it.</p>
 *
 * <p>So he asks. Nothing moves until an administrator accepts, and accepting posts the
 * ordinary signed ADJUSTMENT through the ordinary service — the tests below check that the
 * balance only ever moves on the second act, and that it moves by exactly what he counted.</p>
 */
// V52 demands a photograph of every movement of stock; the bucket here is a map.
@Import(InMemoryStorageConfig.class)
class StockCorrectionIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String STORE_A = "32000000-0000-0000-0000-000000000001";
    private static final String TEST_CODE_PREFIX = "TM-CORRECT-";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeTestMaterials() {
        String materials = "SELECT id FROM materials WHERE code LIKE ?";
        jdbc.update("DELETE FROM stock_correction_requests WHERE material_id IN ("
                + materials + ")", TEST_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM stock_transactions WHERE material_id IN (" + materials + ")",
                TEST_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM stock_balances WHERE material_id IN (" + materials + ")",
                TEST_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM goods_receipt_items WHERE material_id IN (" + materials + ")",
                TEST_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM goods_receipts WHERE id NOT IN "
                + "(SELECT grn_id FROM goods_receipt_items)");
        jdbc.update("DELETE FROM materials WHERE code LIKE ?", TEST_CODE_PREFIX + "%");
    }

    /**
     * The whole feature in one test: twelve booked, eleven in the shed, and the balance does
     * not move until somebody who is allowed to move it agrees.
     */
    @Test
    @DisplayName("a correction the field asks for moves nothing until the office accepts it")
    void theFieldAsksAndTheOfficePosts() throws Exception {
        String office = loginToken("uttam");
        String bag = unitId(office, "BAG");
        String material = createMaterial(office, TEST_CODE_PREFIX + "SHORT", bag);
        receive(office, material, bag, "12", "400");
        assertQuantity(office, material, "12.0000");

        String supervisor = loginToken("vivek");
        JsonNode raised = raise(supervisor, material, bag, "-1",
                "Twelve on the challan, eleven off the lorry. Counted twice.");

        assertThat(raised.get("status").asText()).isEqualTo("PENDING");
        // Nothing has happened to the store. This is the assertion the feature stands on.
        assertQuantity(office, material, "12.0000");

        mockMvc.perform(post("/api/v1/inventory/stock-corrections/"
                        + raised.get("id").asText() + "/decision")
                        .header("Authorization", "Bearer " + office)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"ACCEPT\",\"remarks\":\"Agreed with the driver\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                // Named, so "what did the office do about my count" has an answer.
                .andExpect(jsonPath("$.postedTxnId").isNotEmpty());

        assertQuantity(office, material, "11.0000");
    }

    /**
     * Refusing keeps the row and posts nothing. A request that vanished when it was refused
     * would be a storekeeper who counts the shed once and never types it again.
     */
    @Test
    @DisplayName("a refused correction keeps its reason and leaves the ledger alone")
    void aRefusalPostsNothing() throws Exception {
        String office = loginToken("uttam");
        String bag = unitId(office, "BAG");
        String material = createMaterial(office, TEST_CODE_PREFIX + "REFUSED", bag);
        receive(office, material, bag, "20", "400");

        String id = raise(loginToken("vivek"), material, bag, "-5", "Five bags I cannot find")
                .get("id").asText();

        mockMvc.perform(post("/api/v1/inventory/stock-corrections/" + id + "/decision")
                        .header("Authorization", "Bearer " + office)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"REJECT",
                                 "remarks":"They went to the other store on Tuesday"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.postedTxnId").doesNotExist())
                .andExpect(jsonPath("$.reason").value("Five bags I cannot find"));

        assertQuantity(office, material, "20.0000");
    }

    /**
     * The permission split, stated. If the supervisor could post the adjustment himself the
     * request would be decoration, and every argument for holding {@code inventory:adjust} in
     * the office would have been quietly abandoned by the feature meant to work around it.
     */
    @Test
    @DisplayName("the field still cannot post an adjustment, or decide its own request")
    void askingIsNotAdjusting() throws Exception {
        String office = loginToken("uttam");
        String bag = unitId(office, "BAG");
        String material = createMaterial(office, TEST_CODE_PREFIX + "SPLIT", bag);
        receive(office, material, bag, "10", "400");
        String supervisor = loginToken("vivek");

        mockMvc.perform(post("/api/v1/inventory/adjustments")
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeId":"%s","materialId":"%s","unitId":"%s",
                                 "quantityDelta":-2,"adjustmentDate":"%s",
                                 "reason":"straight to the ledger"}"""
                                .formatted(STORE_A, material, bag, LocalDate.now())))
                .andExpect(status().isForbidden());

        String id = raise(supervisor, material, bag, "-2", "Two short").get("id").asText();
        mockMvc.perform(post("/api/v1/inventory/stock-corrections/" + id + "/decision")
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"ACCEPT\"}"))
                .andExpect(status().isForbidden());

        assertQuantity(office, material, "10.0000");
    }

    /**
     * A second open request for the same figure is nearly always the same man asking again
     * because nobody answered the first, and the way that ends is with both accepted and the
     * correction written on twice.
     */
    @Test
    @DisplayName("one open request per store and material")
    void refusesASecondOpenRequestForTheSameFigure() throws Exception {
        String office = loginToken("uttam");
        String bag = unitId(office, "BAG");
        String material = createMaterial(office, TEST_CODE_PREFIX + "TWICE", bag);
        receive(office, material, bag, "30", "400");
        String supervisor = loginToken("vivek");

        raise(supervisor, material, bag, "-3", "Three short");

        mockMvc.perform(post("/api/v1/inventory/stock-corrections")
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID().toString(), material, bag, "-3",
                                "Three short, asking again")))
                .andExpect(status().isConflict());
    }

    /** Sent three times from a shed with no signal is one request, not three corrections. */
    @Test
    @DisplayName("the same request id sent twice is one request")
    void isIdempotentOnTheClientGeneratedId() throws Exception {
        String office = loginToken("uttam");
        String bag = unitId(office, "BAG");
        String material = createMaterial(office, TEST_CODE_PREFIX + "REPLAY", bag);
        receive(office, material, bag, "10", "400");

        String supervisor = loginToken("vivek");
        String id = UUID.randomUUID().toString();
        String payload = body(id, material, bag, "-1", "One short");

        mockMvc.perform(post("/api/v1/inventory/stock-corrections")
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/inventory/stock-corrections")
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id));
    }

    /** A correction of nothing is not a correction, and it would post an empty ledger row. */
    @Test
    @DisplayName("a correction of zero is refused")
    void refusesAZeroCorrection() throws Exception {
        String office = loginToken("uttam");
        String bag = unitId(office, "BAG");
        String material = createMaterial(office, TEST_CODE_PREFIX + "ZERO", bag);

        mockMvc.perform(post("/api/v1/inventory/stock-corrections")
                        .header("Authorization", "Bearer " + loginToken("vivek"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID().toString(), material, bag, "0", "nothing")))
                .andExpect(status().isUnprocessableEntity());
    }

    /**
     * Accepting is a posting, so the ledger's own guards answer it. A write-off larger than
     * the balance is refused there, and the request must not be left marked accepted against
     * a correction that never happened.
     */
    @Test
    @DisplayName("a correction the ledger refuses leaves the request undecided")
    void aRefusedPostingLeavesTheRequestOpen() throws Exception {
        String office = loginToken("uttam");
        String bag = unitId(office, "BAG");
        String material = createMaterial(office, TEST_CODE_PREFIX + "NEG", bag);
        receive(office, material, bag, "5", "400");

        String id = raise(loginToken("vivek"), material, bag, "-50", "Shed is empty")
                .get("id").asText();

        mockMvc.perform(post("/api/v1/inventory/stock-corrections/" + id + "/decision")
                        .header("Authorization", "Bearer " + office)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"ACCEPT\"}"))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(get("/api/v1/inventory/stock-corrections")
                        .param("storeId", STORE_A)
                        .header("Authorization", "Bearer " + office))
                .andExpect(status().isOk());
        assertThat(statusOf(office, id)).isEqualTo("PENDING");
        assertQuantity(office, material, "5.0000");
    }

    // ------------------------------------------------------------------ helpers

    private String statusOf(String token, String id) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/inventory/stock-corrections")
                        .param("storeId", STORE_A)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode row : objectMapper.readTree(result.getResponse().getContentAsString())) {
            if (id.equals(row.get("id").asText())) {
                return row.get("status").asText();
            }
        }
        throw new AssertionError("no correction " + id + " in the queue");
    }

    private JsonNode raise(String token, String materialId, String unitId, String delta,
                           String reason) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/inventory/stock-corrections")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID().toString(), materialId, unitId, delta,
                                reason)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String body(String id, String materialId, String unitId, String delta,
                               String reason) {
        return """
                {"id":"%s","storeId":"%s","materialId":"%s","unitId":"%s",
                 "quantityDelta":%s,"correctionDate":"%s","reason":"%s"}"""
                .formatted(id, STORE_A, materialId, unitId, delta, LocalDate.now(), reason);
    }

    private void assertQuantity(String token, String materialId, String quantity)
            throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/inventory/stock")
                        .param("storeId", STORE_A)
                        .param("materialId", materialId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("rows");
        assertThat(rows).hasSize(1);
        assertThat(new BigDecimal(rows.get(0).get("quantityBase").asText()))
                .isEqualByComparingTo(quantity);
    }

    private void receive(String token, String materialId, String unitId, String quantity,
                         String rate) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/inventory/goods-receipts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MovementEvidence.onReceipt(mockMvc, objectMapper, token,
"""
                                {"id":"%s","storeId":"%s","receiptDate":"%s",
                                 "lines":[{"materialId":"%s","unitId":"%s","quantity":%s,
                                           "rate":%s}]}"""
                                .formatted(UUID.randomUUID(), STORE_A, LocalDate.now(),
                                        materialId, unitId, quantity, rate))))
                .andExpect(status().isCreated())
                .andReturn();
        String grnId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
        mockMvc.perform(post("/api/v1/inventory/goods-receipts/" + grnId + "/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"VERIFY\"}"))
                .andExpect(status().isOk());
    }

    private String createMaterial(String token, String code, String baseUnitId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/materials")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"Correction test material %s",
                                 "baseUnitId":"%s","gstPercent":0,"minStockLevel":0,
                                 "consumable":true}"""
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
        for (JsonNode unit : objectMapper.readTree(result.getResponse().getContentAsString())) {
            if (code.equals(unit.get("code").asText())) {
                return unit.get("id").asText();
            }
        }
        throw new AssertionError("no unit " + code + " in the catalogue");
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

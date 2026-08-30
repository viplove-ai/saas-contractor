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
 * The Phase 4 exit criterion about transfers: <b>a transfer is counted at neither site while
 * it is in transit</b>.
 *
 * <p>That is the whole reason the lifecycle has three steps instead of one. Leave the
 * material at the sender until it arrives and two storekeepers can promise the same forty
 * bags to two different work faces; credit the receiver on dispatch and he can issue
 * material that is still on the road. So it leaves one store, belongs to nobody, and
 * arrives at the other.</p>
 */
// V52 demands a photograph of every movement of stock; the bucket here is a map.
@Import(InMemoryStorageConfig.class)
class StockTransferLifecycleIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String STORE_A = "32000000-0000-0000-0000-000000000001";
    private static final String STORE_B = "32000000-0000-0000-0000-000000000002";
    private static final String TEST_CODE_PREFIX = "TM-TRANSFER-";

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
        jdbc.update("DELETE FROM stock_transfer_items WHERE material_id IN (" + materials + ")",
                TEST_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM goods_receipt_items WHERE material_id IN (" + materials + ")",
                TEST_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM stock_transfers WHERE id NOT IN "
                + "(SELECT transfer_id FROM stock_transfer_items)");
        jdbc.update("DELETE FROM goods_receipts WHERE id NOT IN "
                + "(SELECT grn_id FROM goods_receipt_items)");
        jdbc.update("DELETE FROM materials WHERE code LIKE ?", TEST_CODE_PREFIX + "%");
    }

    @Test
    @DisplayName("material in transit is counted at neither store, and lands only on receipt")
    void inTransitStockBelongsToNobody() throws Exception {
        String uttam = loginToken("uttam");
        String bagUnit = unitId(uttam, "BAG");
        String material = createMaterial(uttam, TEST_CODE_PREFIX + "TRANSIT", bagUnit);
        receiveAndVerify(uttam, STORE_A, material, bagUnit, "100", "400");

        String transferId = createTransfer(uttam, material, bagUnit, "40");

        // Before dispatch: still all at the sender, nothing at the receiver.
        assertQuantity(uttam, STORE_A, material, "100.0000");
        assertMissing(uttam, STORE_B, material);

        dispatch(uttam, transferId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));

        // On the lorry. Gone from the sender, and not yet issuable at the receiver — the
        // quantity there is zero even though the in-transit figure says it is coming.
        assertQuantity(uttam, STORE_A, material, "60.0000");
        assertQuantity(uttam, STORE_B, material, "0.0000");
        assertInTransit(uttam, STORE_B, material, "40.0000");

        // And the receiving store genuinely cannot issue what has not arrived.
        mockMvc.perform(post("/api/v1/inventory/issues")
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MovementEvidence.onIssue(mockMvc, objectMapper, uttam,
"""
                                {"id":"%s","storeId":"%s","issueDate":"%s","purpose":"Too early",
                                 "lines":[{"materialId":"%s","unitId":"%s","quantity":10}]}"""
                                .formatted(UUID.randomUUID(), STORE_B, LocalDate.now(),
                                        material, bagUnit))))
                .andExpect(status().isUnprocessableEntity());

        receive(uttam, transferId, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"));

        // Arrived. The two stores now hold the original hundred between them, and the
        // in-transit figure is back to nothing.
        assertQuantity(uttam, STORE_A, material, "60.0000");
        assertQuantity(uttam, STORE_B, material, "40.0000");
        assertInTransit(uttam, STORE_B, material, "0.0000");
    }

    /**
     * A shortage is a real outcome, not an error: forty bags leave and thirty-eight arrive.
     * The receiving store is credited with what turned up, never with what was promised, and
     * the two missing bags are recorded as the shortage rather than quietly vanishing into
     * the destination's balance.
     */
    @Test
    @DisplayName("a short delivery credits the receiver with what arrived and records the difference")
    void shortageIsRecordedRatherThanAbsorbed() throws Exception {
        String uttam = loginToken("uttam");
        String bagUnit = unitId(uttam, "BAG");
        String material = createMaterial(uttam, TEST_CODE_PREFIX + "SHORT", bagUnit);
        receiveAndVerify(uttam, STORE_A, material, bagUnit, "100", "400");

        String transferId = createTransfer(uttam, material, bagUnit, "40");
        dispatch(uttam, transferId).andExpect(status().isOk());

        String lineId = firstLineId(uttam, transferId);
        receive(uttam, transferId, """
                {"lines":[{"lineId":"%s","receivedQuantityBase":38,
                           "remarks":"Two bags split in the tipper"}]}""".formatted(lineId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].receivedQtyBase").value(38.0000))
                .andExpect(jsonPath("$.lines[0].shortageQtyBase").value(2.0000));

        assertQuantity(uttam, STORE_A, material, "60.0000");
        assertQuantity(uttam, STORE_B, material, "38.0000");
        assertInTransit(uttam, STORE_B, material, "0.0000");
    }

    /** A transfer carries the sending store's cost across; it is not a purchase. */
    @Test
    @DisplayName("a transfer carries the sending store's average across without repricing it")
    void transferDoesNotReprice() throws Exception {
        String uttam = loginToken("uttam");
        String bagUnit = unitId(uttam, "BAG");
        String material = createMaterial(uttam, TEST_CODE_PREFIX + "RATE", bagUnit);
        receiveAndVerify(uttam, STORE_A, material, bagUnit, "100", "400");
        receiveAndVerify(uttam, STORE_A, material, bagUnit, "100", "500");   // average 450

        String transferId = createTransfer(uttam, material, bagUnit, "50");
        dispatch(uttam, transferId).andExpect(status().isOk());
        receive(uttam, transferId, null).andExpect(status().isOk());

        assertRate(uttam, STORE_A, material, "450.0000");
        assertRate(uttam, STORE_B, material, "450.0000");

        // Nothing was created or destroyed: 200 bags at ₹450 before, 200 bags at ₹450 after.
        BigDecimal totalValue = value(uttam, STORE_A, material).add(value(uttam, STORE_B, material));
        assertThat(totalValue).isEqualByComparingTo("90000.00");
    }

    @Test
    @DisplayName("a transfer cannot be received before it has been dispatched")
    void cannotReceiveWhatHasNotLeft() throws Exception {
        String uttam = loginToken("uttam");
        String bagUnit = unitId(uttam, "BAG");
        String material = createMaterial(uttam, TEST_CODE_PREFIX + "ORDER", bagUnit);
        receiveAndVerify(uttam, STORE_A, material, bagUnit, "50", "400");

        String transferId = createTransfer(uttam, material, bagUnit, "10");
        receive(uttam, transferId, null).andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("a transfer of more than the sending store holds is refused")
    void cannotTransferMoreThanIsThere() throws Exception {
        String uttam = loginToken("uttam");
        String bagUnit = unitId(uttam, "BAG");
        String material = createMaterial(uttam, TEST_CODE_PREFIX + "OVER", bagUnit);
        receiveAndVerify(uttam, STORE_A, material, bagUnit, "10", "400");

        mockMvc.perform(post("/api/v1/inventory/transfers")
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","fromStoreId":"%s","toStoreId":"%s","transferDate":"%s",
                                 "lines":[{"materialId":"%s","unitId":"%s","quantity":40}]}"""
                                .formatted(UUID.randomUUID(), STORE_A, STORE_B, LocalDate.now(),
                                        material, bagUnit)))
                .andExpect(status().isUnprocessableEntity());
    }

    // ------------------------------------------------------------------ helpers

    private String createTransfer(String token, String materialId, String unitId, String quantity)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/inventory/transfers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","fromStoreId":"%s","toStoreId":"%s","transferDate":"%s",
                                 "vehicleNumber":"UK-04-1234",
                                 "lines":[{"materialId":"%s","unitId":"%s","quantity":%s}]}"""
                                .formatted(UUID.randomUUID(), STORE_A, STORE_B, LocalDate.now(),
                                        materialId, unitId, quantity)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private org.springframework.test.web.servlet.ResultActions dispatch(String token, String id)
            throws Exception {
        return mockMvc.perform(post("/api/v1/inventory/transfers/" + id + "/dispatch")
                .header("Authorization", "Bearer " + token));
    }

    private org.springframework.test.web.servlet.ResultActions receive(String token, String id,
                                                                       String body)
            throws Exception {
        return mockMvc.perform(post("/api/v1/inventory/transfers/" + id + "/receive")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "{}" : body));
    }

    private String firstLineId(String token, String transferId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/inventory/transfers/" + transferId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("lines").get(0).get("id").asText();
    }

    private void receiveAndVerify(String token, String storeId, String materialId, String unitId,
                                  String quantity, String rate) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/inventory/goods-receipts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MovementEvidence.onReceipt(mockMvc, objectMapper, token,
"""
                                {"id":"%s","storeId":"%s","receiptDate":"%s",
                                 "lines":[{"materialId":"%s","unitId":"%s","quantity":%s,"rate":%s}]}"""
                                .formatted(UUID.randomUUID(), storeId, LocalDate.now(),
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

    private void assertQuantity(String token, String storeId, String materialId, String expected)
            throws Exception {
        assertThat(new BigDecimal(row(token, storeId, materialId).get("quantityBase").asText()))
                .isEqualByComparingTo(expected);
    }

    private void assertInTransit(String token, String storeId, String materialId, String expected)
            throws Exception {
        assertThat(new BigDecimal(row(token, storeId, materialId).get("inTransitQtyBase").asText()))
                .isEqualByComparingTo(expected);
    }

    private void assertRate(String token, String storeId, String materialId, String expected)
            throws Exception {
        assertThat(new BigDecimal(row(token, storeId, materialId).get("movingAvgRate").asText()))
                .isEqualByComparingTo(expected);
    }

    private BigDecimal value(String token, String storeId, String materialId) throws Exception {
        return new BigDecimal(row(token, storeId, materialId).get("stockValue").asText());
    }

    /** No balance row at all — the store has never seen this material. */
    private void assertMissing(String token, String storeId, String materialId) throws Exception {
        assertThat(rows(token, storeId, materialId)).isEmpty();
    }

    private JsonNode row(String token, String storeId, String materialId) throws Exception {
        JsonNode rows = rows(token, storeId, materialId);
        assertThat(rows).as("a balance row for the material at that store").hasSize(1);
        return rows.get(0);
    }

    private JsonNode rows(String token, String storeId, String materialId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/inventory/stock")
                        .param("storeId", storeId)
                        .param("materialId", materialId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("rows");
    }

    private String createMaterial(String token, String code, String baseUnitId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/materials")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"Transfer test material %s","baseUnitId":"%s",
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
        for (JsonNode unit : objectMapper.readTree(result.getResponse().getContentAsString())) {
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

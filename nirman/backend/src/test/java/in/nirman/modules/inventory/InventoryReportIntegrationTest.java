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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The five inventory reports, and the one property that makes them worth having: every
 * figure on them comes out of the ledger, so it can be argued back to the movement that
 * produced it.
 *
 * <p>The consumption report's split between issued and wasted is the assertion worth
 * reading. Both leave the store and both are cost, but one went into the work and the other
 * did not — a site losing eight per cent of its cement to breakage needs that as its own
 * number, not buried in a total.</p>
 */
// V52 demands a photograph of every movement of stock; the bucket here is a map.
@Import(InMemoryStorageConfig.class)
class InventoryReportIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";
    private static final String STORE_A = "32000000-0000-0000-0000-000000000001";
    private static final String TEST_CODE_PREFIX = "TM-REPORT-";

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
        jdbc.update("DELETE FROM material_issue_items WHERE material_id IN (" + materials + ")",
                TEST_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM goods_receipt_items WHERE material_id IN (" + materials + ")",
                TEST_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM material_issues WHERE id NOT IN "
                + "(SELECT issue_id FROM material_issue_items)");
        jdbc.update("DELETE FROM goods_receipts WHERE id NOT IN "
                + "(SELECT grn_id FROM goods_receipt_items)");
        jdbc.update("DELETE FROM materials WHERE code LIKE ?", TEST_CODE_PREFIX + "%");
    }

    @Test
    @DisplayName("consumption keeps issued and wasted apart, and both come from the ledger")
    void consumptionSeparatesIssuedFromWasted() throws Exception {
        String uttam = loginToken("uttam");
        String bagUnit = unitId(uttam, "BAG");
        String material = createMaterial(uttam, TEST_CODE_PREFIX + "CONSUME", bagUnit, "0");

        receiveAndVerify(uttam, material, bagUnit, "100", "400");
        issueAndApprove(uttam, material, bagUnit, "60");
        waste(uttam, material, bagUnit, "10", "Bags split in the rain");

        JsonNode row = consumptionRow(uttam, material);
        assertThat(new BigDecimal(row.get("issuedQty").asText())).isEqualByComparingTo("60.0000");
        assertThat(new BigDecimal(row.get("issuedValue").asText())).isEqualByComparingTo("24000.00");
        assertThat(new BigDecimal(row.get("wastedQty").asText())).isEqualByComparingTo("10.0000");
        assertThat(new BigDecimal(row.get("wastedValue").asText())).isEqualByComparingTo("4000.00");
        assertThat(new BigDecimal(row.get("totalConsumedQty").asText()))
                .isEqualByComparingTo("70.0000");
        assertThat(new BigDecimal(row.get("closingQty").asText())).isEqualByComparingTo("30.0000");
    }

    @Test
    @DisplayName("low stock is judged against each material's own reorder level")
    void lowStockUsesThePerMaterialThreshold() throws Exception {
        String uttam = loginToken("uttam");
        String bagUnit = unitId(uttam, "BAG");
        String scarce = createMaterial(uttam, TEST_CODE_PREFIX + "SCARCE", bagUnit, "100");
        String plenty = createMaterial(uttam, TEST_CODE_PREFIX + "PLENTY", bagUnit, "10");

        receiveAndVerify(uttam, scarce, bagUnit, "40", "400");    // under its level of 100
        receiveAndVerify(uttam, plenty, bagUnit, "40", "400");    // over its level of 10

        MvcResult result = mockMvc.perform(get("/api/v1/reports/low-stock")
                        .param("siteId", SITE_A)
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = objectMapper.readTree(result.getResponse().getContentAsString()).get("rows");

        assertThat(materialIds(rows)).contains(scarce).doesNotContain(plenty);
        JsonNode scarceRow = rowFor(rows, scarce);
        assertThat(new BigDecimal(scarceRow.get("shortfall").asText()))
                .isEqualByComparingTo("60.0000");
    }

    @Test
    @DisplayName("the wastage report carries the reason each write-off was given")
    void wastageReportCarriesTheReasons() throws Exception {
        String uttam = loginToken("uttam");
        String bagUnit = unitId(uttam, "BAG");
        String material = createMaterial(uttam, TEST_CODE_PREFIX + "WASTE", bagUnit, "0");
        receiveAndVerify(uttam, material, bagUnit, "50", "400");
        waste(uttam, material, bagUnit, "5", "Set hard in the godown");

        MvcResult result = mockMvc.perform(get("/api/v1/reports/wastage")
                        .param("siteId", SITE_A)
                        .param("from", LocalDate.now().minusDays(1).toString())
                        .param("to", LocalDate.now().toString())
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode row = rowFor(
                objectMapper.readTree(result.getResponse().getContentAsString()).get("rows"),
                material);
        assertThat(row.get("reason").asText()).isEqualTo("Set hard in the godown");
        assertThat(new BigDecimal(row.get("value").asText())).isEqualByComparingTo("2000.00");
    }

    @Test
    @DisplayName("every inventory report exports to xlsx")
    void reportsExportToSpreadsheets() throws Exception {
        String uttam = loginToken("uttam");
        String today = LocalDate.now().toString();
        String weekAgo = LocalDate.now().minusDays(7).toString();

        expectSpreadsheet(uttam, "/api/v1/reports/stock-position", "siteId", SITE_A, null, null);
        expectSpreadsheet(uttam, "/api/v1/reports/low-stock", "siteId", SITE_A, null, null);
        expectSpreadsheet(uttam, "/api/v1/reports/material-consumption", "siteId", SITE_A,
                weekAgo, today);
        expectSpreadsheet(uttam, "/api/v1/reports/wastage", "siteId", SITE_A, weekAgo, today);
        expectSpreadsheet(uttam, "/api/v1/reports/transfer-register", null, null, weekAgo, today);
    }

    /**
     * The permission split the matrix draws. A supervisor holds {@code report:operational}
     * and sees his own site's stock; the wage summary next door needs
     * {@code report:financial} and he does not.
     */
    @Test
    @DisplayName("a supervisor can pull his own site's stock position")
    void supervisorCanReadHisOwnStock() throws Exception {
        String vivek = loginToken("vivek");
        mockMvc.perform(get("/api/v1/reports/stock-position")
                        .param("siteId", SITE_A)
                        .header("Authorization", "Bearer " + vivek))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/reports/stock-position")
                        .param("siteId", "31000000-0000-0000-0000-000000000002")
                        .header("Authorization", "Bearer " + vivek))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private void expectSpreadsheet(String token, String path, String scopeParam, String scopeValue,
                                   String from, String to) throws Exception {
        var request = get(path).param("format", "xlsx")
                .header("Authorization", "Bearer " + token);
        if (scopeParam != null) {
            request = request.param(scopeParam, scopeValue);
        }
        if (from != null) {
            request = request.param("from", from).param("to", to);
        }
        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".xlsx")));
    }

    private JsonNode consumptionRow(String token, String materialId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/reports/material-consumption")
                        .param("siteId", SITE_A)
                        .param("from", LocalDate.now().minusDays(1).toString())
                        .param("to", LocalDate.now().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return rowFor(objectMapper.readTree(result.getResponse().getContentAsString()).get("rows"),
                materialId);
    }

    private static JsonNode rowFor(JsonNode rows, String materialId) {
        for (JsonNode row : rows) {
            if (materialId.equals(row.get("materialId").asText())) {
                return row;
            }
        }
        throw new AssertionError("No report row for material " + materialId);
    }

    private static java.util.List<String> materialIds(JsonNode rows) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        rows.forEach(row -> ids.add(row.get("materialId").asText()));
        return ids;
    }

    private void waste(String token, String materialId, String unitId, String quantity,
                       String reason) throws Exception {
        mockMvc.perform(post("/api/v1/inventory/wastage")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeId":"%s","materialId":"%s","unitId":"%s","quantity":%s,
                                 "wastageDate":"%s","type":"WASTAGE","reason":"%s"}"""
                                .formatted(STORE_A, materialId, unitId, quantity,
                                        LocalDate.now(), reason)))
                .andExpect(status().isOk());
    }

    private void receiveAndVerify(String token, String materialId, String unitId,
                                  String quantity, String rate) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/inventory/goods-receipts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MovementEvidence.onReceipt(mockMvc, objectMapper, token,
"""
                                {"id":"%s","storeId":"%s","receiptDate":"%s",
                                 "lines":[{"materialId":"%s","unitId":"%s","quantity":%s,"rate":%s}]}"""
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

    private void issueAndApprove(String token, String materialId, String unitId, String quantity)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/inventory/issues")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MovementEvidence.onIssue(mockMvc, objectMapper, token,
"""
                                {"id":"%s","storeId":"%s","issueDate":"%s","purpose":"Report test",
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
    }

    private String createMaterial(String token, String code, String baseUnitId, String minStock)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/materials")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"Report test material %s","baseUnitId":"%s",
                                 "gstPercent":0,"minStockLevel":%s,"consumable":true}"""
                                .formatted(code, code, baseUnitId, minStock)))
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

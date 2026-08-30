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
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What came off the lorry and what it cost are two different people's answers.
 *
 * <p>The storekeeper has a challan in his hand, and a challan is a delivery note that often
 * carries no price at all. Asking him for a rate got a guess — and a guessed rate does not
 * stay on the document, it goes into the moving average and mis-values every issue of that
 * material for months afterwards. So the rate is the office's, set against the invoice, and
 * verification refuses a line that still has none: verifying is the moment stock moves, and
 * stock cannot be carried at no value.</p>
 */
// V52 demands a photograph of every movement of stock; the bucket here is a map.
@Import(InMemoryStorageConfig.class)
class ReceiptPricingIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String STORE_A = "32000000-0000-0000-0000-000000000001";
    private static final String TEST_CODE_PREFIX = "TM-PRICE-";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /** Stock is cumulative, so each test works with a material it creates and removes. */
    @AfterEach
    void removeTestMaterials() {
        String materials = "SELECT id FROM materials WHERE code LIKE ?";
        String prefix = TEST_CODE_PREFIX + "%";
        jdbc.update("DELETE FROM stock_transactions WHERE material_id IN (" + materials + ")", prefix);
        jdbc.update("DELETE FROM stock_balances WHERE material_id IN (" + materials + ")", prefix);
        jdbc.update("DELETE FROM goods_receipt_items WHERE material_id IN (" + materials + ")", prefix);
        jdbc.update("DELETE FROM materials WHERE code LIKE ?", prefix);
    }

    @Test
    @DisplayName("a supervisor who sends a rate is refused, not quietly stripped of it")
    void supervisorMayNotPrice() throws Exception {
        String office = login("uttam");
        String material = createMaterial(office, TEST_CODE_PREFIX + "REFUSE",
                unitId(office, "BAG"));

        mockMvc.perform(post("/api/v1/inventory/goods-receipts")
                        .header("Authorization", "Bearer " + login("vivek"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MovementEvidence.onReceipt(mockMvc, objectMapper,
                                login("vivek"),
                                receiptBody(material, unitId(office, "BAG"), "20", "400"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("set by the office")));
    }

    @Test
    @DisplayName("he books the delivery without one, and the line comes back unpriced")
    void supervisorBooksWithoutARate() throws Exception {
        String office = login("uttam");
        String bag = unitId(office, "BAG");
        String material = createMaterial(office, TEST_CODE_PREFIX + "BOOK", bag);

        JsonNode receipt = book(login("vivek"), material, bag, "20", null);
        // Absent rather than null in the body: the API omits nulls, and "no rate" is the
        // field not being there at all rather than a zero the reader has to interpret.
        assertThat(receipt.get("lines").get(0).has("rate")).isFalse();
        // Not zero-with-a-value either: nothing has been claimed about what it cost.
        assertThat(new BigDecimal(receipt.get("totalAmount").asText()))
                .isEqualByComparingTo("0");
    }

    /**
     * The refusal that makes the rest of it hold. Without it the rate would be optional in
     * name only — a receipt could reach the ledger at nothing per bag, which does not read
     * as missing data downstream, it reads as free cement and drags the store's average down.
     */
    @Test
    @DisplayName("verifying an unpriced receipt is refused, and names how many lines are waiting")
    void verifyingRefusesAnUnpricedLine() throws Exception {
        String office = login("uttam");
        String bag = unitId(office, "BAG");
        String material = createMaterial(office, TEST_CODE_PREFIX + "UNPRICED", bag);
        String grnId = book(login("vivek"), material, bag, "20", null).get("id").asText();

        mockMvc.perform(post("/api/v1/inventory/goods-receipts/" + grnId + "/verify")
                        .header("Authorization", "Bearer " + office)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"VERIFY\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("1 line(s) with no rate")));

        Integer posted = jdbc.queryForObject(
                "SELECT count(*) FROM stock_transactions WHERE material_id = ?::uuid",
                Integer.class, material);
        assertThat(posted).isZero();
    }

    @Test
    @DisplayName("the office prices it, and then the same verification puts it into the store")
    void pricingThenVerifyingPostsTheLedgerAtThatRate() throws Exception {
        String office = login("uttam");
        String bag = unitId(office, "BAG");
        String material = createMaterial(office, TEST_CODE_PREFIX + "PRICED", bag);

        JsonNode receipt = book(login("vivek"), material, bag, "20", null);
        String grnId = receipt.get("id").asText();
        String lineId = receipt.get("lines").get(0).get("id").asText();

        JsonNode priced = price(office, grnId, lineId, "400");
        assertThat(new BigDecimal(priced.get("lines").get(0).get("rate").asText()))
                .isEqualByComparingTo("400");
        // The header follows the lines: 20 bags at 400 is what the receipt is now worth.
        assertThat(new BigDecimal(priced.get("totalAmount").asText()))
                .isEqualByComparingTo("8000");

        mockMvc.perform(post("/api/v1/inventory/goods-receipts/" + grnId + "/verify")
                        .header("Authorization", "Bearer " + office)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"VERIFY\"}"))
                .andExpect(status().isOk());

        BigDecimal rate = jdbc.queryForObject(
                "SELECT rate_base FROM stock_transactions WHERE material_id = ?::uuid",
                BigDecimal.class, material);
        assertThat(rate).as("the ledger is valued at the office's rate, not the gate's")
                .isEqualByComparingTo("400");
    }

    /** Zero is a rate somebody decided — free issue from the department happens. */
    @Test
    @DisplayName("a rate of zero is a price, and passes verification")
    void zeroIsAPrice() throws Exception {
        String office = login("uttam");
        String bag = unitId(office, "BAG");
        String material = createMaterial(office, TEST_CODE_PREFIX + "FREE", bag);

        JsonNode receipt = book(login("vivek"), material, bag, "5", null);
        price(office, receipt.get("id").asText(),
                receipt.get("lines").get(0).get("id").asText(), "0");

        mockMvc.perform(post("/api/v1/inventory/goods-receipts/"
                        + receipt.get("id").asText() + "/verify")
                        .header("Authorization", "Bearer " + office)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"VERIFY\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a supervisor cannot reach the pricing call either")
    void supervisorCannotPriceAfterTheFact() throws Exception {
        String office = login("uttam");
        String bag = unitId(office, "BAG");
        String material = createMaterial(office, TEST_CODE_PREFIX + "BACKDOOR", bag);
        JsonNode receipt = book(login("vivek"), material, bag, "20", null);

        mockMvc.perform(put("/api/v1/inventory/goods-receipts/"
                        + receipt.get("id").asText() + "/prices")
                        .header("Authorization", "Bearer " + login("vivek"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"lineId":"%s","rate":400}]}"""
                                .formatted(receipt.get("lines").get(0).get("id").asText())))
                .andExpect(status().isForbidden());
    }

    /**
     * Once the material is in the store its rate is in the moving average, and re-pricing the
     * document would change a valuation with nothing on the record saying so. The way back
     * is an adjustment, which leaves the trail.
     */
    @Test
    @DisplayName("a verified receipt cannot be re-priced")
    void aPostedReceiptRefusesANewRate() throws Exception {
        String office = login("uttam");
        String bag = unitId(office, "BAG");
        String material = createMaterial(office, TEST_CODE_PREFIX + "POSTED", bag);

        JsonNode receipt = book(office, material, bag, "10", "500");
        String grnId = receipt.get("id").asText();
        mockMvc.perform(post("/api/v1/inventory/goods-receipts/" + grnId + "/verify")
                        .header("Authorization", "Bearer " + office)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"VERIFY\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/inventory/goods-receipts/" + grnId + "/prices")
                        .header("Authorization", "Bearer " + office)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"lineId":"%s","rate":900}]}"""
                                .formatted(receipt.get("lines").get(0).get("id").asText())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("stock adjustment")));
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode book(String token, String materialId, String unitId, String quantity,
                          String rate) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/inventory/goods-receipts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MovementEvidence.onReceipt(mockMvc, objectMapper, token,
receiptBody(materialId, unitId, quantity, rate))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode price(String token, String grnId, String lineId, String rate)
            throws Exception {
        MvcResult result = mockMvc.perform(put("/api/v1/inventory/goods-receipts/"
                        + grnId + "/prices")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"lineId":"%s","rate":%s}]}"""
                                .formatted(lineId, rate)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** The rate is left out of the body entirely when nobody has priced the line. */
    private static String receiptBody(String materialId, String unitId, String quantity,
                                      String rate) {
        String priced = rate == null ? "" : ",\"rate\":" + rate;
        return """
                {"id":"%s","storeId":"%s","receiptDate":"%s","challanNumber":"CH-PRICE",
                 "lines":[{"materialId":"%s","unitId":"%s","quantity":%s%s}]}"""
                .formatted(UUID.randomUUID(), STORE_A, LocalDate.now(), materialId, unitId,
                        quantity, priced);
    }

    private String createMaterial(String token, String code, String baseUnitId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/materials")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"Pricing test material %s","baseUnitId":"%s",
                                 "gstPercent":0,"minStockLevel":0,"consumable":true}"""
                                .formatted(code, code, baseUnitId)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String unitId(String token, String code) throws Exception {
        MvcResult result = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/units")
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode unit : objectMapper.readTree(result.getResponse().getContentAsString())) {
            if (code.equals(unit.get("code").asText())) {
                return unit.get("id").asText();
            }
        }
        throw new IllegalStateException("No unit " + code + " in the seeded master data");
    }

    private String login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}

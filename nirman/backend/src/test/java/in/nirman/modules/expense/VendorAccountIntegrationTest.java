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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A supplier's account, and the one act the system could not record.
 *
 * <p>Money paid to a dealer before any bill of his exists is how a lorry of steel gets
 * loaded. {@code PaymentService} already told the accountant to "record the excess as a
 * separate advance to the vendor" and gave him nowhere to record it, so it went in a second
 * book — which is where a supplier's account and the system's account of him part company.</p>
 */
class VendorAccountIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String TEST_CODE_PREFIX = "VEN-ACC-";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /** Payments are counted by other classes' cash reports, so the rows go when the test does. */
    @AfterEach
    void removeTestVendors() {
        String vendors = "SELECT id FROM vendors WHERE code LIKE ?";
        String prefix = TEST_CODE_PREFIX + "%";
        jdbc.update("DELETE FROM payments WHERE vendor_id IN (" + vendors + ")", prefix);
        jdbc.update("DELETE FROM vendors WHERE code LIKE ?", prefix);
    }

    @Test
    @DisplayName("an accountant onboards a dealer with the details an employer has to keep")
    void accountantOnboardsADealer() throws Exception {
        String accountant = login("uttam");
        JsonNode vendor = createVendor(accountant, TEST_CODE_PREFIX + "STEEL");

        assertThat(vendor.get("name").asText()).isEqualTo("Kausani Steel Traders");
        assertThat(vendor.get("gstin").asText()).isEqualTo("05AABCU9603R1ZM");
        assertThat(vendor.get("mobile").asText()).isEqualTo("9812345678");
        assertThat(vendor.get("creditDays").asInt()).isEqualTo(30);
    }

    /**
     * The supervisor is not refused because he is careless. Onboarding a dealer means
     * settling credit terms and typing bank details, and that is the office's work.
     */
    @Test
    @DisplayName("a supervisor cannot onboard a dealer")
    void supervisorCannotOnboardADealer() throws Exception {
        mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", "Bearer " + login("vivek"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vendorBody(TEST_CODE_PREFIX + "REFUSED")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an advance is recorded against the supplier and against no bill of his")
    void advanceIsRecordedOnAccount() throws Exception {
        String accountant = login("uttam");
        String vendorId = createVendor(accountant, TEST_CODE_PREFIX + "ADV").get("id").asText();

        MvcResult result = mockMvc.perform(post("/api/v1/vendors/" + vendorId + "/advances")
                        .header("Authorization", "Bearer " + accountant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentDate":"%s","amount":50000,"paymentMode":"BANK",
                                 "referenceNumber":"NEFT/9931",
                                 "remarks":"Against the steel order placed on the 3rd"}"""
                                .formatted(LocalDate.now())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode payment = objectMapper.readTree(result.getResponse().getContentAsString());

        // No bill on it, and that absence is the fact being recorded.
        assertThat(payment.has("expenseId")).isFalse();
        assertThat(payment.get("vendorId").asText()).isEqualTo(vendorId);
        assertThat(new BigDecimal(payment.get("amount").asText())).isEqualByComparingTo("50000");
    }

    /**
     * Five figures rather than one balance. "He is owed 50,000" is the answer nobody can
     * settle an argument with: the advance sitting against no bill is exactly the number
     * both sides forget, and a net balance is the one figure that reconciles to nothing the
     * supplier holds.
     */
    @Test
    @DisplayName("the account keeps billed, paid and advanced apart")
    void accountKeepsTheFiguresApart() throws Exception {
        String accountant = login("uttam");
        String vendorId = createVendor(accountant, TEST_CODE_PREFIX + "FIG").get("id").asText();

        advance(accountant, vendorId, "20000");
        advance(accountant, vendorId, "5000");

        MvcResult result = mockMvc.perform(get("/api/v1/vendors/" + vendorId + "/account")
                        .header("Authorization", "Bearer " + accountant))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode account = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(new BigDecimal(account.get("advancePaid").asText()))
                .isEqualByComparingTo("25000");
        assertThat(new BigDecimal(account.get("billedAmount").asText()))
                .isEqualByComparingTo("0");
        assertThat(new BigDecimal(account.get("outstanding").asText()))
                .isEqualByComparingTo("0");
        // Nothing billed and money already gone: we are ahead of him, and the sign says so.
        assertThat(new BigDecimal(account.get("netPosition").asText()))
                .isEqualByComparingTo("-25000");
    }

    /** A supplier nobody deals with any more should not quietly take another payment. */
    @Test
    @DisplayName("an inactive supplier refuses an advance")
    void inactiveSupplierRefusesAnAdvance() throws Exception {
        String accountant = login("uttam");
        JsonNode vendor = createVendor(accountant, TEST_CODE_PREFIX + "GONE");
        String vendorId = vendor.get("id").asText();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/vendors/" + vendorId)
                        .header("Authorization", "Bearer " + accountant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Kausani Steel Traders","vendorType":"MATERIAL",
                                 "creditDays":30,"active":false,"version":%d}"""
                                .formatted(vendor.get("version").asLong())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/vendors/" + vendorId + "/advances")
                        .header("Authorization", "Bearer " + accountant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentDate":"%s","amount":1000,"paymentMode":"CASH",
                                 "remarks":"Should not go out"}""".formatted(LocalDate.now())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("not an active supplier")));
    }

    /**
     * What he sent is read off the deliveries, which is where it is already written down.
     * The seeded vendor has receipts against him; a supplier with none reads as an empty
     * history rather than as an error.
     */
    @Test
    @DisplayName("a supplier who has sent nothing has an empty purchase history, not an error")
    void purchaseHistoryOfANewSupplier() throws Exception {
        String accountant = login("uttam");
        String vendorId = createVendor(accountant, TEST_CODE_PREFIX + "NEW").get("id").asText();

        mockMvc.perform(get("/api/v1/vendors/" + vendorId + "/purchases")
                        .header("Authorization", "Bearer " + accountant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    /** A supervisor has no business reading what the company owes a supplier. */
    @Test
    @DisplayName("a supervisor cannot read a supplier's account")
    void supervisorCannotReadTheAccount() throws Exception {
        String vendorId = createVendor(login("uttam"), TEST_CODE_PREFIX + "PRIV")
                .get("id").asText();

        mockMvc.perform(get("/api/v1/vendors/" + vendorId + "/account")
                        .header("Authorization", "Bearer " + login("vivek")))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private void advance(String token, String vendorId, String amount) throws Exception {
        mockMvc.perform(post("/api/v1/vendors/" + vendorId + "/advances")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentDate":"%s","amount":%s,"paymentMode":"BANK",
                                 "remarks":"Advance against the running order"}"""
                                .formatted(LocalDate.now(), amount)))
                .andExpect(status().isCreated());
    }

    private JsonNode createVendor(String token, String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vendorBody(code)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String vendorBody(String code) {
        return """
                {"code":"%s","name":"Kausani Steel Traders","vendorType":"MATERIAL",
                 "contactPerson":"Harish Bisht","mobile":"9812345678",
                 "address":"Main Bazaar, Kausani","gstin":"05AABCU9603R1ZM",
                 "pan":"AABCU9603R","bankAccountNo":"5011000123456","bankIfsc":"HDFC0001234",
                 "creditDays":30,"openingBalance":0}""".formatted(code);
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

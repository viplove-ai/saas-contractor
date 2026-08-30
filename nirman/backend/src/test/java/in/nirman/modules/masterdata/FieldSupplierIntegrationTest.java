package in.nirman.modules.masterdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A supplier named at the gate (V50).
 *
 * <p>The third turn of the screw the material and the expense head already made: the field
 * may say what a thing is called and never what it is worth. Here the field is the man
 * watching the lorry unload, and what he may say is the firm's name, what it supplies and how
 * to reach it. The GSTIN, the bank account and the credit period are the accountant's, and
 * this test is mostly the list of things the supervisor is refused.</p>
 */
class FieldSupplierIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("a supervisor names a supplier and gets a usable, unvetted row")
    void namesASupplierFromTheGate() throws Exception {
        JsonNode created = nameSupplier(loginToken("vivek"), """
                {"name":"Kosi Sand Suppliers","vendorType":"MATERIAL",
                 "contactPerson":"Harish Bisht","mobile":"9812345678"}""");

        assertThat(created.get("name").asText()).isEqualTo("Kosi Sand Suppliers");
        assertThat(created.get("contactPerson").asText()).isEqualTo("Harish Bisht");
        assertThat(created.get("provisional").asBoolean()).isTrue();
        // Active, because a supplier being named is a supplier being used — there is nothing
        // to switch off yet, and a picker that will not offer him is a lorry sent away.
        assertThat(created.get("active").asBoolean()).isTrue();
        assertThat(created.get("code").asText()).isNotBlank();
        // Nothing the office decides came out of the gate with him. Absent rather than
        // empty: the response omits a null, which is what "nobody has said" looks like.
        assertThat(created.has("gstin")).isFalse();
        assertThat(created.has("bankAccountNo")).isFalse();
        assertThat(created.get("creditDays").asInt()).isZero();
    }

    /** Two rows for one firm split his account in half, and neither half is what he is owed. */
    @Test
    @DisplayName("the same firm typed again is the same supplier, whatever the case and spacing")
    void doesNotDuplicateASupplierSomebodyAlreadyNamed() throws Exception {
        String token = loginToken("vivek");

        JsonNode first = nameSupplier(token,
                "{\"name\":\"Ranikhet Hardware\",\"vendorType\":\"MATERIAL\"}");
        JsonNode again = nameSupplier(token,
                "{\"name\":\"  ranikhet   HARDWARE \",\"vendorType\":\"TRANSPORT\"}");

        assertThat(again.get("id").asText()).isEqualTo(first.get("id").asText());
    }

    /**
     * The correction is the other half of naming: without it the way round a mistyped firm is
     * a second row, which is the split account the naming rule exists to prevent.
     */
    @Test
    @DisplayName("the field corrects what it said, and the row goes back in front of the office")
    void correctsWhatTheFieldSaid() throws Exception {
        String token = loginToken("vivek");
        JsonNode created = nameSupplier(token,
                "{\"name\":\"Almora Sand Suppliars\",\"vendorType\":\"MATERIAL\"}");

        mockMvc.perform(put("/api/v1/vendors/" + created.get("id").asText() + "/field")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Almora Sand Suppliers","vendorType":"MATERIAL",
                                 "contactPerson":"Naveen Bisht","mobile":"9811111111",
                                 "version":%d}""".formatted(created.get("version").asLong())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Almora Sand Suppliers"))
                .andExpect(jsonPath("$.contactPerson").value("Naveen Bisht"))
                .andExpect(jsonPath("$.provisional").value(true));
    }

    /**
     * Naming the firm and onboarding it are different acts. If the supervisor could do both,
     * the provisional flag and the whole split would be decoration.
     */
    @Test
    @DisplayName("a supervisor still may not onboard a supplier the ordinary way")
    void namingIsNotTheSameAsOnboarding() throws Exception {
        mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", "Bearer " + loginToken("vivek"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Bank Details By Another Name","vendorType":"MATERIAL",
                                 "gstin":"05AABCU9603R1ZM","bankAccountNo":"000111222333",
                                 "creditDays":45}"""))
                .andExpect(status().isForbidden());
    }

    /** And he may not reach the office's columns through the office's own endpoint either. */
    @Test
    @DisplayName("a supervisor may not edit a supplier the office's way")
    void mayNotEditTheOfficesHalf() throws Exception {
        String token = loginToken("vivek");
        JsonNode created = nameSupplier(token,
                "{\"name\":\"Bageshwar Transport\",\"vendorType\":\"TRANSPORT\"}");

        mockMvc.perform(put("/api/v1/vendors/" + created.get("id").asText())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Bageshwar Transport","vendorType":"TRANSPORT",
                                 "gstin":"05AABCU9603R1ZM","creditDays":60,"active":false,
                                 "version":%d}""".formatted(created.get("version").asLong())))
                .andExpect(status().isForbidden());
    }

    /** Editing the row is the act of vetting it: the office has now put its numbers on him. */
    @Test
    @DisplayName("the office editing the row clears the flag")
    void theOfficeVettingHimClearsTheFlag() throws Exception {
        JsonNode created = nameSupplier(loginToken("vivek"),
                "{\"name\":\"Someshwar Steel\",\"vendorType\":\"MATERIAL\"}");

        mockMvc.perform(put("/api/v1/vendors/" + created.get("id").asText())
                        .header("Authorization", "Bearer " + loginToken("viplove"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Someshwar Steel","vendorType":"MATERIAL",
                                 "gstin":"05AABCU9603R1ZM","creditDays":30,"active":true,
                                 "version":%d}""".formatted(created.get("version").asLong())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provisional").value(false))
                .andExpect(jsonPath("$.gstin").value("05AABCU9603R1ZM"));
    }

    @Test
    @DisplayName("a supplier needs a name, and blank spaces are not one")
    void refusesABlankName() throws Exception {
        mockMvc.perform(post("/api/v1/vendors/field")
                        .header("Authorization", "Bearer " + loginToken("vivek"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \",\"vendorType\":\"MATERIAL\"}"))
                .andExpect(status().isBadRequest());
    }

    private JsonNode nameSupplier(String token, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/vendors/field")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
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

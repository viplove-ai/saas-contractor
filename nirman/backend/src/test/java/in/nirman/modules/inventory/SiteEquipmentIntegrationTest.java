package in.nirman.modules.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The plant register, and the split it exists for.
 *
 * <p>Anybody standing at the site may say a machine is there — a supervisor who cannot record
 * the mixer in front of him will not record it at all, and the register becomes a list of
 * what the office remembered. But an entry nobody checked is a claim, so it waits as PENDING
 * until an administrator agrees, and correcting or removing a row is the office's alone.</p>
 */
class SiteEquipmentIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String STORE_A = "32000000-0000-0000-0000-000000000001";
    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("a supervisor enters a machine and it waits for the office")
    void theSiteEntersAndTheOfficeDecides() throws Exception {
        String supervisor = loginToken("vivek");

        JsonNode entered = enter(supervisor, body(UUID.randomUUID(), "Concrete Mixer 10/7", null));

        assertThat(entered.get("status").asText()).isEqualTo("PENDING");
        assertThat(entered.get("quantity").asInt()).isEqualTo(1);
        // Nothing about the ledger moved: plant is held, not consumed.
        mockMvc.perform(get("/api/v1/inventory/stock")
                        .param("storeId", STORE_A)
                        .header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.materialName == 'Concrete Mixer 10/7')]")
                        .isEmpty());
    }

    @Test
    @DisplayName("an administrator accepts it, and the register says so")
    void theOfficeAcceptsIt() throws Exception {
        String id = UUID.randomUUID().toString();
        enter(loginToken("vivek"), body(UUID.fromString(id), "Needle Vibrator", "VIB-114"));

        mockMvc.perform(post("/api/v1/inventory/equipment/" + id + "/decision")
                        .header("Authorization", "Bearer " + loginToken("viplove"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"ACCEPT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.decidedAt").isNotEmpty());
    }

    /**
     * The rule the whole feature turns on. A supervisor who could accept his own entry would
     * make the workflow decoration.
     */
    @Test
    @DisplayName("the site may not accept its own entry")
    void theSiteMayNotAcceptItsOwnEntry() throws Exception {
        String supervisor = loginToken("vivek");
        String id = UUID.randomUUID().toString();
        enter(supervisor, body(UUID.fromString(id), "Bar Bending Machine", null));

        mockMvc.perform(post("/api/v1/inventory/equipment/" + id + "/decision")
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"ACCEPT\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the site may not correct or remove a row, whoever typed it")
    void theSiteMayNotEditOrDelete() throws Exception {
        String supervisor = loginToken("vivek");
        String id = UUID.randomUUID().toString();
        JsonNode entered = enter(supervisor, body(UUID.fromString(id), "Plate Compactor", null));

        mockMvc.perform(put("/api/v1/inventory/equipment/" + id)
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeId":"%s","name":"Plate Compactor (mine)","quantity":9,
                                 "ownership":"OWNED","condition":"WORKING","version":%d}"""
                                .formatted(STORE_A, entered.get("version").asLong())))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/inventory/equipment/" + id)
                        .header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isForbidden());
    }

    /**
     * Nobody checks his own work here either — but there is nobody left to check the person
     * who does the checking, and a queue full of his own entries is one he learns to click
     * through without reading.
     */
    @Test
    @DisplayName("an administrator's own entry is on the register the moment he makes it")
    void anAdministratorsOwnEntryNeedsNoApproval() throws Exception {
        JsonNode entered = enter(loginToken("viplove"),
                body(UUID.randomUUID(), "Tower Light", null));

        assertThat(entered.get("status").asText()).isEqualTo("ACCEPTED");
    }

    /** Two rows carrying one registration are two machines that are one machine. */
    @Test
    @DisplayName("a number already on the register is refused, and named")
    void refusesADuplicateAssetNumber() throws Exception {
        String token = loginToken("viplove");
        enter(token, body(UUID.randomUUID(), "Hired JCB", "UK-04-CA-1120"));

        mockMvc.perform(post("/api/v1/inventory/equipment")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), "Second JCB", " uk-04-ca-1120 ")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Hired JCB")));
    }

    /** A hired machine costs money every day it stands there, and somebody is owed it. */
    @Test
    @DisplayName("a hired machine has to say who it is hired from")
    void hiredPlantNeedsASupplier() throws Exception {
        mockMvc.perform(post("/api/v1/inventory/equipment")
                        .header("Authorization", "Bearer " + loginToken("vivek"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","storeId":"%s","name":"Hired Breaker",
                                 "ownership":"HIRED","condition":"WORKING"}"""
                                .formatted(UUID.randomUUID(), STORE_A)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("the register lists what is at the site, pending entries and all")
    void listsTheRegisterForASite() throws Exception {
        String token = loginToken("viplove");
        enter(token, body(UUID.randomUUID(), "Site Register Mixer", null));

        MvcResult result = mockMvc.perform(get("/api/v1/inventory/equipment")
                        .param("siteId", SITE_A)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode rows = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(rows.isArray()).isTrue();
        assertThat(rows.toString()).contains("Site Register Mixer");
        // The store's name rather than its id: nobody knows a yard by uuid.
        assertThat(rows.get(0).hasNonNull("storeName")).isTrue();
    }

    /** Re-sending the same entry from a phone that lost the answer is one machine, not two. */
    @Test
    @DisplayName("the same entry sent twice is one machine")
    void isIdempotentOnTheClientId() throws Exception {
        String token = loginToken("vivek");
        UUID id = UUID.randomUUID();

        JsonNode first = enter(token, body(id, "Winch", null));
        JsonNode again = enter(token, body(id, "Winch", null));

        assertThat(again.get("id").asText()).isEqualTo(first.get("id").asText());
    }

    /** Deleted is off the register but not out of the history: the row is kept. */
    @Test
    @DisplayName("an administrator removes a row and it leaves the register")
    void theOfficeRemovesARow() throws Exception {
        String token = loginToken("viplove");
        String id = UUID.randomUUID().toString();
        enter(token, body(UUID.fromString(id), "Mistaken Entry", null));

        mockMvc.perform(delete("/api/v1/inventory/equipment/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/inventory/equipment/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    private String body(UUID id, String name, String assetCode) {
        return """
                {"id":"%s","storeId":"%s","name":"%s"%s,
                 "ownership":"OWNED","condition":"WORKING"}"""
                .formatted(id, STORE_A, name,
                        assetCode == null ? "" : ",\"assetCode\":\"" + assetCode + "\"");
    }

    private JsonNode enter(String token, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/inventory/equipment")
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

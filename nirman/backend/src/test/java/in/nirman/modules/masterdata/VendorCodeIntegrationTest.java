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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The supplier's short code, which nobody types any more.
 *
 * <p>It was the first thing the onboarding form asked for and it was a filing decision nobody
 * was really making: one person typed the firm's initials, the next typed the town it
 * delivers from, and the register sorted into no order and told you nothing. It is derived
 * now — from what he supplies and what he is called — and derived on the server, because that
 * is the only place uniqueness can be promised while two people onboard dealers at once.</p>
 */
class VendorCodeIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("a supplier onboarded with no code gets one made of what he supplies and his name")
    void derivesTheCode() throws Exception {
        JsonNode created = onboard("Shiv Shakti Traders", "MATERIAL");

        assertThat(created.get("code").asText()).isEqualTo("MAT-SHIVSHAKTI");
    }

    @Test
    @DisplayName("what he supplies is what the code opens with")
    void theTypeLeadsTheCode() throws Exception {
        // Whole words, up to fourteen characters: KUMAONCARRIERS is recognisable and
        // KUMAONCARRIE is a typo somebody will try to correct.
        assertThat(onboard("Kumaon Carriers", "TRANSPORT").get("code").asText())
                .isEqualTo("TRN-KUMAONCARRIERS");
        assertThat(onboard("Pahar Labour Gang", "SUBCONTRACTOR").get("code").asText())
                .startsWith("SUB-");
    }

    /** A dealer and his brother's yard, both called the same thing. The second one is -2. */
    @Test
    @DisplayName("a second firm of the same name gets a code of its own")
    void doesNotCollide() throws Exception {
        String first = onboard("Nainital Hardware", "MATERIAL").get("code").asText();
        String second = onboard("Nainital Hardware", "MATERIAL").get("code").asText();

        assertThat(first).isEqualTo("MAT-NAINITAL");
        assertThat(second).isEqualTo("MAT-NAINITAL-2");
    }

    /**
     * A name with nothing usable in it still has to produce a supplier. A lorry is at the
     * gate; refusing to onboard the man who sent it because his firm is written in Devanagari
     * would be the software deciding whose name counts.
     */
    @Test
    @DisplayName("a name that yields no code still onboards the supplier")
    void fallsBackForAnUnusableName() throws Exception {
        JsonNode created = onboard("शिव शक्ति", "MATERIAL");

        assertThat(created.get("code").asText()).isEqualTo("MAT-SUPPLIER");
    }

    /**
     * Still accepted when it is sent. An organisation migrating its own register has codes
     * that already mean something to the people reading them.
     */
    @Test
    @DisplayName("a code the office does send is kept as it is")
    void keepsACodeThatWasGiven() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", "Bearer " + loginToken("viplove"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"LEGACY-77","name":"Almora Cement Agency",
                                 "vendorType":"MATERIAL","creditDays":0}"""))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("code").asText()).isEqualTo("LEGACY-77");
    }

    private JsonNode onboard(String name, String type) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", "Bearer " + loginToken("viplove"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new NewVendor(name, type, 0))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private record NewVendor(String name, String vendorType, int creditDays) {
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

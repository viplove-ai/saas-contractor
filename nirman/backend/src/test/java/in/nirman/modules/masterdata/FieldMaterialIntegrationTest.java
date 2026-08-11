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

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A material named at the gate.
 *
 * <p>The storekeeper meets a lorry carrying something the catalogue has never heard of, and
 * the office is not going to add it while the driver waits. The alternative to letting him
 * name it is a delivery that never gets booked — so the picker has a "not in the list" answer
 * and this is what stands behind it.</p>
 *
 * <p>Two things have to be true at once, and they pull against each other. The name has to
 * become a real material, because a stock transaction keys on a material id and there is
 * nowhere else for a free-text name to live. And the catalogue has to survive it: a second
 * row for cement splits its stock into two balances, neither of which is the amount in the
 * shed. So the same name comes back as the same material, and what a supervisor may do here
 * stops well short of what the office may do to the master.</p>
 */
class FieldMaterialIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String STORE_A = "32000000-0000-0000-0000-000000000001";
    /** BAG, from the starter catalogue — the storekeeper picks a unit, he does not invent one. */
    private static final String BAG = "BAG";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("a supervisor names a material at the gate and gets a usable, unvetted row")
    void namesAMaterialFromTheField() throws Exception {
        String token = loginToken("vivek");

        JsonNode created = addFieldMaterial(token, "White Cement Grade A", unitId(token, BAG));

        assertThat(created.get("name").asText()).isEqualTo("White Cement Grade A");
        // Marked as named rather than decided: the office's cue that nothing behind it is set.
        assertThat(created.get("provisional").asBoolean()).isTrue();
        assertThat(created.get("code").asText()).startsWith("MAT-");
        // Nothing is guessed on his behalf. A rate typed at a gate becomes a figure somebody
        // later costs work against.
        assertThat(created.hasNonNull("standardRate")).isFalse();
        assertThat(created.hasNonNull("hsnCode")).isFalse();
    }

    /**
     * The check that makes this endpoint different from "create a material with fewer fields".
     * Two rows for one material is not an untidy catalogue, it is a stock figure that is wrong.
     */
    @Test
    @DisplayName("the same name typed again is the same material, whatever the case and spacing")
    void doesNotDuplicateAMaterialSomebodyAlreadyNamed() throws Exception {
        String token = loginToken("vivek");
        String bag = unitId(token, BAG);

        JsonNode first = addFieldMaterial(token, "Waterproofing Compound", bag);
        JsonNode again = addFieldMaterial(token, "  waterproofing   compound ", bag);

        assertThat(again.get("id").asText()).isEqualTo(first.get("id").asText());
    }

    /** The catalogue already holds cement. Typing its name is not a second cement. */
    @Test
    @DisplayName("a name the catalogue already holds returns the vetted row, not a new one")
    void returnsTheEstablishedRowRatherThanShadowingIt() throws Exception {
        String token = loginToken("vivek");

        JsonNode answer = addFieldMaterial(token, "Cement OPC 43 Grade", unitId(token, BAG));

        assertThat(answer.get("code").asText()).isEqualTo("CEM-OPC43");
        assertThat(answer.get("provisional").asBoolean()).isFalse();
    }

    /**
     * The point of the whole exercise: what he named can carry a delivery. A material that
     * cannot be received is a name in a table and nothing else.
     */
    @Test
    @DisplayName("the material he named can take a delivery straight away")
    void bookASameDayDeliveryAgainstIt() throws Exception {
        String token = loginToken("vivek");
        String bag = unitId(token, BAG);
        String materialId = addFieldMaterial(token, "Tile Adhesive 20kg", bag).get("id").asText();

        mockMvc.perform(post("/api/v1/inventory/goods-receipts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","storeId":"%s","receiptDate":"%s",
                                 "challanNumber":"CH-FIELD-1",
                                 "lines":[{"materialId":"%s","unitId":"%s","quantity":10,"rate":450}]}"""
                                .formatted(UUID.randomUUID(), STORE_A, LocalDate.now(),
                                        materialId, bag)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].materialName").value("Tile Adhesive 20kg"));
    }

    /**
     * Naming a bag of something on a challan and re-pricing the master are different acts, and
     * the permission split is what keeps them apart. A supervisor may do the first and not the
     * second — if he could do both, the flag would be decoration.
     */
    @Test
    @DisplayName("a supervisor still may not create a material the ordinary way")
    void namingIsNotTheSameAsOwningTheCatalogue() throws Exception {
        mockMvc.perform(post("/api/v1/materials")
                        .header("Authorization", "Bearer " + loginToken("vivek"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"SNEAK-1","name":"Cement at my price","baseUnitId":"%s",
                                 "gstPercent":0,"standardRate":9999,"consumable":true}"""
                                .formatted(unitId(loginToken("vivek"), BAG))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a material needs a name, and blank spaces are not one")
    void refusesABlankName() throws Exception {
        String token = loginToken("vivek");

        mockMvc.perform(post("/api/v1/materials/field")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \",\"baseUnitId\":\"%s\"}"
                                .formatted(unitId(token, BAG))))
                .andExpect(status().isBadRequest());
    }

    private JsonNode addFieldMaterial(String token, String name, String unitId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/materials/field")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new FieldMaterial(name, unitId))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private record FieldMaterial(String name, String baseUnitId) {
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

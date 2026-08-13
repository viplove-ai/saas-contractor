package in.nirman.modules.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The starter catalogue of planning norms, and what may be done to it.
 *
 * <p>Two properties are worth defending. The first is that the catalogue is <b>there</b> — an
 * organisation that has entered nothing still gets a full set, because a planner that needed
 * norms nobody had entered would be a feature with an empty screen behind it. The second is that
 * a correction is a correction: the figure moves, the identity of the norm does not, and the row
 * stops claiming a source it no longer has.</p>
 */
class PlanningNormIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("every organisation starts with a usable catalogue of norms")
    void starterCatalogueIsPresent() throws Exception {
        String token = loginToken("viplove");

        JsonNode profiles = getJson(token, "/api/v1/planning/norms/work-type-profiles");
        assertThat(profiles).hasSizeGreaterThanOrEqualTo(7);
        assertThat(codes(profiles, "code")).contains("BUILDING_NEW", "ROAD", "COMPOSITE");

        // A road is not a school: the phase basis is what stops one sequencing rule being
        // applied to both.
        JsonNode road = findBy(profiles, "code", "ROAD");
        assertThat(road.get("phaseBasis").asText()).isEqualTo("CHAINAGE");
        assertThat(road.get("monsoonSensitive").asBoolean()).isTrue();

        JsonNode productivity = getJson(token, "/api/v1/planning/norms/productivity");
        assertThat(productivity).isNotEmpty();
        // Every norm names a trade and a unit, or the figure means nothing.
        productivity.forEach(norm -> {
            assertThat(norm.get("skillCode").asText()).isNotBlank();
            assertThat(norm.get("workUnitCode").asText()).isNotBlank();
            assertThat(norm.get("manDaysPerWorkUnit").asDouble()).isGreaterThan(0);
        });

        // The categories are the classifier's own vocabulary, which is what lets a norm find
        // its BOQ line without anybody mapping rows by hand.
        assertThat(codes(productivity, "workCategory"))
                .contains("Masonry", "Concrete & RCC", "Earthwork");

        JsonNode sequence = getJson(token, "/api/v1/planning/norms/sequence");
        List<String> ranked = codes(sequence, "workCategory");
        assertThat(ranked).isNotEmpty();
        // Returned in the order the work is done, which is the only order this list is useful in.
        assertThat(ranked.indexOf("Earthwork")).isLessThan(ranked.indexOf("Masonry"));
        assertThat(ranked.indexOf("Masonry")).isLessThan(ranked.indexOf("Flooring & Finishes"));

        JsonNode leadTimes = getJson(token, "/api/v1/planning/norms/lead-times");
        assertThat(leadTimes).isNotEmpty();
        JsonNode cement = findBy(leadTimes, "materialCode", "CEM-OPC43");
        assertThat(cement.get("orderAheadDays").asInt())
                .isEqualTo(cement.get("leadDays").asInt() + cement.get("bufferDays").asInt());
        // Cement keeps about three months, which is what stops a plan buying the whole job's
        // cement in month one.
        assertThat(cement.get("shelfLifeDays").asInt()).isEqualTo(90);
        // Steel is rolled to order and is the longest ordinary lead on a building.
        assertThat(findBy(leadTimes, "materialCode", "STL-FE500D").get("leadDays").asInt())
                .isGreaterThan(cement.get("leadDays").asInt());
    }

    @Test
    @DisplayName("a corrected figure becomes the organisation's own, not the catalogue's")
    void revisingAProductivityNormClaimsIt() throws Exception {
        String token = loginToken("viplove");
        JsonNode norm = findBy(getJson(token, "/api/v1/planning/norms/productivity"),
                "workCategory", "Masonry");
        assertThat(norm.get("source").asText()).isEqualTo("INTERNAL");

        mockMvc.perform(patch("/api/v1/planning/norms/productivity/" + norm.get("id").asText())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"manDaysPerWorkUnit\":0.75,\"version\":"
                                + norm.get("version").asLong() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manDaysPerWorkUnit").value(0.75))
                // The identity of the norm is untouched: a norm whose category or trade could
                // move would silently become a different norm some earlier plan already used.
                .andExpect(jsonPath("$.workCategory").value("Masonry"))
                .andExpect(jsonPath("$.skillCategoryId").value(norm.get("skillCategoryId").asText()))
                .andExpect(jsonPath("$.source").value("INTERNAL"));
    }

    @Test
    @DisplayName("a stale version is refused rather than silently overwriting")
    void staleVersionConflicts() throws Exception {
        String token = loginToken("viplove");
        JsonNode norm = getJson(token, "/api/v1/planning/norms/productivity").get(0);

        mockMvc.perform(patch("/api/v1/planning/norms/productivity/" + norm.get("id").asText())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"manDaysPerWorkUnit\":0.5,\"version\":"
                                + (norm.get("version").asLong() + 7) + "}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a norm cannot be set to zero man-days")
    void zeroIsRefused() throws Exception {
        String token = loginToken("viplove");
        JsonNode norm = getJson(token, "/api/v1/planning/norms/productivity").get(0);

        mockMvc.perform(patch("/api/v1/planning/norms/productivity/" + norm.get("id").asText())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"manDaysPerWorkUnit\":0,\"version\":"
                                + norm.get("version").asLong() + "}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * What the seeded users can actually prove.
     *
     * <p>Only {@code vivek} holds one role. {@code viplove} and {@code uttam} are both
     * administrators on top of everything else they are, so neither can demonstrate what an
     * engineer alone may do — a run of this test asserting "the engineer cannot write" passes
     * for the wrong reason or fails for one. The engineer's read grant is in {@code V30} and is
     * covered by the permission matrix, not from here.</p>
     */
    @Test
    @DisplayName("a supervisor neither reads the norms nor writes them")
    void supervisorIsShutOut() throws Exception {
        String supervisor = loginToken("vivek");
        JsonNode norm = getJson(loginToken("viplove"), "/api/v1/planning/norms/productivity").get(0);

        mockMvc.perform(get("/api/v1/planning/norms/productivity")
                        .header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/planning/norms/productivity/" + norm.get("id").asText())
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"manDaysPerWorkUnit\":0.5,\"version\":"
                                + norm.get("version").asLong() + "}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode getJson(String token, String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> codes(JsonNode array, String field) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(node -> node.get(field).asText())
                .toList();
    }

    private static JsonNode findBy(JsonNode array, String field, String value) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .filter(node -> value.equals(node.get(field).asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no row with " + field + " = " + value + " in " + array));
    }

    private String loginToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Nirman@123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}

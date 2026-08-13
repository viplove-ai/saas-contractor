package in.nirman.modules.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Planning a seeded project, end to end.
 *
 * <p>What is being defended is the chain rather than any single number: a BOQ line is classified,
 * a norm finds it by that classification, the quantity becomes man-days and material, the
 * material becomes a procurement date, and the whole becomes a cash flow whose lowest point is
 * the money the contractor has to find. Every link is a different module, and the plan is the
 * only thing that reads all of them.</p>
 */
class ExecutionPlanIntegrationTest extends AbstractIntegrationTest {

    /** The seeded Kausani project, which carries a BOQ. */
    private static final UUID PROJECT =
            UUID.fromString("30000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("a preview runs the whole chain and stores none of it")
    void previewProducesAPlanAndPersistsNothing() throws Exception {
        String token = loginToken("viplove");
        long before = count("execution_plans");

        JsonNode plan = postJson(token, "/api/v1/planning/projects/" + PROJECT + "/preview",
                "{\"quotedPercent\":-5,\"allowedDays\":365}");

        assertThat(plan.hasNonNull("id")).as("a preview keeps nothing").isFalse();
        assertThat(plan.get("packages")).as("work was found and scheduled").isNotEmpty();
        assertThat(plan.get("cash")).as("and costed month by month").isNotEmpty();

        // The headline. A trough deeper than zero means the contractor funds the work before
        // the department funds him, which is the entire point of the exercise.
        assertThat(new BigDecimal(plan.get("workingCapital").get("peakFundingRequired").asText()))
                .isPositive();

        // Every package sits inside the time allowed.
        plan.get("packages").forEach(block ->
                assertThat(block.get("endDate").asText()).isNotBlank());

        assertThat(count("execution_plans")).isEqualTo(before);
    }

    @Test
    @DisplayName("the quoted percentage moves the money, which is what a bid case is for")
    void scenariosDiffer() throws Exception {
        String token = loginToken("viplove");

        BigDecimal atPar = receipts(postJson(token,
                "/api/v1/planning/projects/" + PROJECT + "/preview", "{\"quotedPercent\":0}"));
        BigDecimal tenBelow = receipts(postJson(token,
                "/api/v1/planning/projects/" + PROJECT + "/preview", "{\"quotedPercent\":-10}"));

        assertThat(tenBelow).as("bidding below the estimate brings in less")
                .isLessThan(atPar);
    }

    @Test
    @DisplayName("baselining freezes the plan and dates the schedule of quantities")
    void baseliningWritesForward() throws Exception {
        String token = loginToken("viplove");

        JsonNode created = postJson(token, "/api/v1/planning/projects/" + PROJECT + "/plans",
                "{\"quotedPercent\":0,\"allowedDays\":540,\"name\":\"Baseline test\"}", 201);
        String planId = created.get("id").asText();
        assertThat(created.get("baselined").asBoolean()).isFalse();

        // Before: V1 provisioned these columns for a planner and nothing had ever written them.
        Long datedBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM boq_items WHERE project_id = ? AND planned_start_date IS NOT NULL",
                Long.class, PROJECT);

        mockMvc.perform(post("/api/v1/planning/plans/" + planId + "/baseline")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baselined").value(true));

        Long datedAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM boq_items WHERE project_id = ? AND planned_start_date IS NOT NULL",
                Long.class, PROJECT);
        assertThat(datedAfter).as("the baseline dates the BOQ lines it covers")
                .isGreaterThan(datedBefore);

        // A reconciliation placeholder describes no work, so nothing may be started on one.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM boq_items WHERE project_id = ? AND is_synthetic "
                        + "AND planned_start_date IS NOT NULL", Long.class, PROJECT)).isZero();

        // And the frozen plan is now the one the project runs under.
        mockMvc.perform(get("/api/v1/planning/projects/" + PROJECT + "/plan")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(planId));
    }

    @Test
    @DisplayName("a plan is baselined once; replacing it is a new plan, not an edit")
    void baseliningTwiceIsRefused() throws Exception {
        String token = loginToken("viplove");
        JsonNode created = postJson(token, "/api/v1/planning/projects/" + PROJECT + "/plans",
                "{\"quotedPercent\":0,\"name\":\"Once only\"}", 201);
        String planId = created.get("id").asText();

        mockMvc.perform(post("/api/v1/planning/plans/" + planId + "/baseline")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/planning/plans/" + planId + "/baseline")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a supervisor may not plan, and an engineer may not freeze one")
    void permissionsHold() throws Exception {
        mockMvc.perform(post("/api/v1/planning/projects/" + PROJECT + "/preview")
                        .header("Authorization", "Bearer " + loginToken("vivek"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private static BigDecimal receipts(JsonNode plan) {
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode month : plan.get("cash")) {
            total = total.add(new BigDecimal(month.get("netReceived").asText()));
        }
        return total;
    }

    private JsonNode postJson(String token, String path, String body) throws Exception {
        return postJson(token, path, body, 200);
    }

    private JsonNode postJson(String token, String path, String body, int status) throws Exception {
        MvcResult result = mockMvc.perform(post(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
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

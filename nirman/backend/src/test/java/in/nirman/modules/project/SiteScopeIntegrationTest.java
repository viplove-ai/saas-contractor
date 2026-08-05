package in.nirman.modules.project;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 exit criterion: a supervisor is provably blocked from an unassigned site.
 *
 * <p>Seeded fixture: Vivek Aggarwal holds SUPERVISOR and nothing else, and is posted to
 * KSN-A only. He is the one seeded login whose token is genuinely site-scoped — Viplove and
 * Uttam both hold ADMIN, so their tokens carry the ALL-sites claim regardless of where they
 * are posted.</p>
 */
class SiteScopeIntegrationTest extends AbstractIntegrationTest {

    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";
    private static final String SITE_B = "31000000-0000-0000-0000-000000000002";
    private static final String WORKER_AT_SITE_A = "60000000-0000-0000-0000-000000000101";
    private static final String WORKER_AT_SITE_B = "60000000-0000-0000-0000-000000000105";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("a supervisor reads an assigned site but is 403-blocked from an unassigned one")
    void supervisorBlockedFromUnassignedSite() throws Exception {
        String token = loginToken("vivek");

        mockMvc.perform(get("/api/v1/sites/" + SITE_A).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("KSN-A"));

        mockMvc.perform(get("/api/v1/sites/" + SITE_B).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        // The scope also covers nested resources, not just the site row itself.
        mockMvc.perform(get("/api/v1/sites/" + SITE_B + "/stores")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("site listing quietly narrows to the assigned sites")
    void siteListIsNarrowed() throws Exception {
        String token = loginToken("vivek");
        MvcResult result = mockMvc.perform(get("/api/v1/sites")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode sites = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(sites).hasSize(1);
        assertThat(sites.get(0).get("code").asText()).isEqualTo("KSN-A");
    }

    @Test
    @DisplayName("the ALL-sites roles see every site, whatever they are posted to")
    void allSiteRolesSeeEverything() throws Exception {
        // Viplove is posted to KSN-B and Uttam to both, yet each sees the whole company.
        for (String username : new String[]{"viplove", "uttam"}) {
            String token = loginToken(username);
            MvcResult result = mockMvc.perform(get("/api/v1/sites")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode sites = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(sites).as(username + " site visibility").hasSize(2);
        }
    }

    /**
     * The unfiltered case of every searchable list, which is how each screen opens and was
     * therefore the one case no test covered. A search term bound as null has no type
     * PostgreSQL can infer inside {@code lower()}, so these 500ed with {@code lower(bytea)}
     * while every filtered call passed.
     */
    @Test
    @DisplayName("the searchable lists open with no filters applied")
    void listsOpenUnfiltered() throws Exception {
        String token = loginToken("viplove");
        for (String path : new String[]{"/api/v1/projects", "/api/v1/workers", "/api/v1/users"}) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
        // And still work when a term is actually given.
        mockMvc.perform(get("/api/v1/projects").param("q", "kausani")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    /**
     * The leak an unfiltered list makes. Asking for a site the caller cannot reach has
     * always been a 403; asking for <em>no</em> site has to mean "mine", not "everyone's",
     * or the fence is one omitted query parameter wide.
     */
    @Test
    @DisplayName("an unfiltered list shows a supervisor only his own site's data")
    void unfilteredListsStayWithinTheAssignedSites() throws Exception {
        String token = loginToken("vivek");   // supervisor, KSN-A only

        // KSN-A has five men on the seed and KSN-B has two; the company has seven.
        MvcResult result = mockMvc.perform(get("/api/v1/workers")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode workers = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(workers.get("totalElements").asInt()).isEqualTo(5);
        assertThat(workers.get("content")).allSatisfy(worker ->
                assertThat(worker.get("workerCode").asText()).isNotEqualTo("W-105"));

        // Naming the other site is refused outright rather than quietly returning nothing.
        mockMvc.perform(get("/api/v1/workers").param("siteId", SITE_B)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        // And the id of a man posted elsewhere is not a way round the list.
        mockMvc.perform(get("/api/v1/workers/" + WORKER_AT_SITE_B)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/workers/" + WORKER_AT_SITE_A)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerCode").value("W-101"));

        mockMvc.perform(get("/api/v1/worker-advances").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/attendance").param("from", "2025-01-01").param("to", "2025-12-31")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("permission matrix holds: supervisor cannot write projects or master data")
    void permissionMatrixEnforced() throws Exception {
        String token = loginToken("vivek");

        mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"HACK1\",\"name\":\"Not allowed\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/units")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"XX\",\"name\":\"Nope\",\"decimalPlaces\":0}"))
                .andExpect(status().isForbidden());

        // But reading master data is allowed to every role.
        mockMvc.perform(get("/api/v1/units").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
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

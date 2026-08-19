package in.nirman.modules.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Which contract's work items a site screen is offered.
 *
 * <p>A site belongs to one project, and most BOQ lines carry no site at all — the contract
 * is written against the work rather than against the block it is built on. So asking by
 * site has to return the project's untagged lines, and that is where it went wrong: it
 * returned <em>every</em> organisation's untagged lines, so the engineer signing a report
 * picked a line off another contract and the server refused it with "belongs to a different
 * project" after he had typed the quantity.</p>
 */
class BoqScopeIntegrationTest extends AbstractIntegrationTest {

    /** Kausani Main Block, on the one seeded project. */
    private static final String SEEDED_SITE_A = "31000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The second contract exists only for the length of a test, and the suite shares one
     * database — a project left standing turns up in another test's counts.
     */
    private final List<String> created = new ArrayList<>();

    @AfterEach
    void removeTheSecondContract() throws Exception {
        String token = adminToken();
        for (String projectId : created) {
            mockMvc.perform(delete("/api/v1/projects/" + projectId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"test fixture cleanup\"}"));
        }
        created.clear();
    }

    @Test
    @DisplayName("asking by site offers that project's items, its untagged lines included, and no other contract's")
    void listBySiteStaysOnTheSitesProject() throws Exception {
        String token = adminToken();
        String otherProject = createProject(token, "BQS01", "Another contract entirely");
        createBoqItem(token, otherProject, "Z-001", "Somebody else's brickwork");

        MvcResult result = mockMvc.perform(get("/api/v1/boq-items")
                        .param("siteId", SEEDED_SITE_A)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        List<String> numbers = new ArrayList<>();
        for (JsonNode item : objectMapper.readTree(result.getResponse().getContentAsString())) {
            numbers.add(item.get("itemNumber").asText());
        }
        // The site's own lines, and the project's project-wide line beside them: both are
        // work this site can be charged with, and dropping the second would empty the picker
        // on every contract whose BOQ is not written site by site.
        assertThat(numbers).contains("B-001", "B-UNALLOC");
        // The other contract's line is untagged too, and that is exactly what used to let it
        // through.
        assertThat(numbers).doesNotContain("Z-001");
    }

    @Test
    @DisplayName("a site and a project that contradict each other are refused, not quietly reconciled")
    void siteAndProjectMustAgree() throws Exception {
        String token = adminToken();
        String otherProject = createProject(token, "BQS02", "Not this site's contract");

        mockMvc.perform(get("/api/v1/boq-items")
                        .param("siteId", SEEDED_SITE_A)
                        .param("projectId", otherProject)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("not on the project")));
    }

    // ---------------------------------------------------------------- fixture

    private String createProject(String token, String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String id = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
        created.add(id);
        return id;
    }

    private void createBoqItem(String token, String projectId, String itemNumber,
                               String description) throws Exception {
        String body = """
                {"projectId":"%s","itemNumber":"%s","description":"%s","unitId":"%s",
                 "contractQuantity":10.000,"contractRate":100.0000}
                """.formatted(projectId, itemNumber, description, anyUnitId(token));
        mockMvc.perform(post("/api/v1/boq-items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    private String anyUnitId(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/units")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get(0).get("id").asText();
    }

    /** Viplove holds ADMIN, so he sees every site and every project's contract. */
    private String adminToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"viplove\",\"password\":\"Nirman@123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}

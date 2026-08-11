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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Taking a project or a site off the books.
 *
 * <p>The rule being defended is that deletion is for mistakes and never for finished work.
 * The seeded Kausani site carries attendance, a stock ledger and expenses, so it stands in
 * for everything that must refuse to go; anything created inside a test has nothing recorded
 * against it and stands in for the contract entered with the wrong code on a Monday.</p>
 */
class ProjectDeletionIntegrationTest extends AbstractIntegrationTest {

    private static final String SEEDED_SITE_A = "31000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Every test here creates projects, and two of them deliberately leave one standing —
     * the restore case would prove nothing otherwise. The suite shares one database, so a
     * project left behind turns up in another test's site count. This puts the fixture back
     * the way it was found: anything named DEL* goes, and its sites go with it.
     */
    @AfterEach
    void removeWhatTheTestLeftStanding() throws Exception {
        String token = adminToken();
        for (JsonNode project : liveProjects(token)) {
            if (project.get("code").asText().startsWith("DEL")) {
                deleteProject(token, project.get("id").asText(), "test fixture cleanup");
            }
        }
    }

    @Test
    @DisplayName("a site carrying real records refuses to be deleted, and says what is on it")
    void siteWithHistoryCannotBeDeleted() throws Exception {
        String token = adminToken();

        MvcResult result = mockMvc.perform(delete("/api/v1/sites/" + SEEDED_SITE_A)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"tidying up\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();

        String detail = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("detail").asText();
        // The counts are the useful part: "close it instead" alone would not tell the admin
        // which site it is or why the system thinks it is in use.
        assertThat(detail).contains("attendance record").contains("Closed");

        // And it is still there afterwards.
        mockMvc.perform(get("/api/v1/sites/" + SEEDED_SITE_A)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a project entered by mistake is deleted with its sites and leaves the list")
    void unusedProjectIsDeletedWithItsSites() throws Exception {
        String token = adminToken();
        String projectId = createProject(token, "DEL01", "Typed in by mistake");
        String siteId = createSite(token, projectId, "DEL01-A", "Mistaken site");

        mockMvc.perform(delete("/api/v1/projects/" + projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"duplicate of KSN01\"}"))
                .andExpect(status().isOk());

        // Gone from the ordinary list, which is what the dashboards and the pickers read.
        assertThat(projectCodes(token, false)).doesNotContain("DEL01");
        assertThat(siteCodes(token, false)).doesNotContain("DEL01-A");

        // And present, with its reason, behind the filter. Found by code rather than by
        // position: earlier tests leave their own rows in the deleted register.
        JsonNode row = deletedProject(token, "DEL01");
        assertThat(row.get("deletedReason").asText()).isEqualTo("duplicate of KSN01");
        assertThat(row.get("deletedAt").isNull()).isFalse();

        assertThat(siteCodes(token, true)).contains("DEL01-A");

        // The site went down with the project, so its id is no longer a way in.
        mockMvc.perform(get("/api/v1/sites/" + siteId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the code stays reserved while deleted, and the refusal says where it went")
    void deletedCodeIsNotHandedOut() throws Exception {
        String token = adminToken();
        String projectId = createProject(token, "DEL02", "Wrong client");
        deleteProject(token, projectId, "wrong client department");

        mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"DEL02\",\"name\":\"Second attempt\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("deleted project")));
    }

    @Test
    @DisplayName("restoring a project brings back only the sites that went down with it")
    void restoreBringsBackTheCascadeAndNothingElse() throws Exception {
        String token = adminToken();
        String projectId = createProject(token, "DEL03", "Restored later");
        createSite(token, projectId, "DEL03-A", "Goes down with the project");
        String earlier = createSite(token, projectId, "DEL03-B", "Deleted on its own first");

        // Removed on its own, before and separately from the project.
        mockMvc.perform(delete("/api/v1/sites/" + earlier)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"never opened\"}"))
                .andExpect(status().isOk());

        deleteProject(token, projectId, "on hold indefinitely");

        mockMvc.perform(post("/api/v1/projects/" + projectId + "/restore")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedAt").doesNotExist());

        // DEL03-A came back with it. DEL03-B was deleted for its own reasons a moment
        // earlier, and restoring the project has no business overruling that.
        assertThat(siteCodes(token, false)).contains("DEL03-A").doesNotContain("DEL03-B");
        assertThat(siteCodes(token, true)).contains("DEL03-B");
    }

    @Test
    @DisplayName("a deleted site stops granting access, even to an administrator")
    void deletedSiteIsClosedToEveryone() throws Exception {
        String token = adminToken();
        String projectId = createProject(token, "DEL04", "Access check");
        String siteId = createSite(token, projectId, "DEL04-A", "About to go");

        mockMvc.perform(delete("/api/v1/sites/" + siteId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"opened in error\"}"))
                .andExpect(status().isOk());

        // Not just absent from the list — unreachable by id, and closed to the write paths
        // in other modules that reach their own repositories through SiteAccessGuard.
        mockMvc.perform(get("/api/v1/sites/" + siteId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/sites/" + siteId + "/stores")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/dashboard/site/" + siteId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("a reason is required, and the deleted list is closed to those who cannot restore")
    void reasonIsRequiredAndTheListIsPrivileged() throws Exception {
        String adminToken = adminToken();
        String projectId = createProject(adminToken, "DEL05", "Needs a reason");

        mockMvc.perform(delete("/api/v1/projects/" + projectId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest());

        // Vivek is a supervisor: he can read projects, and cannot delete or see deleted ones.
        String supervisorToken = loginToken("vivek");
        mockMvc.perform(get("/api/v1/projects").header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/projects").param("deleted", "true")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/projects/" + projectId)
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"not mine to delete\"}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private String createProject(String token, String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createSite(String token, String projectId, String code, String name)
            throws Exception {
        String body = """
                {"projectId":"%s","code":"%s","name":"%s",
                 "standardShiftHours":8.00,"monthlyWageDays":26}
                """.formatted(projectId, code, name);
        MvcResult result = mockMvc.perform(post("/api/v1/sites")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void deleteProject(String token, String projectId, String reason) throws Exception {
        mockMvc.perform(delete("/api/v1/projects/" + projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode liveProjects(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/projects")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
    }

    private JsonNode deletedProject(String token, String code) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/projects").param("deleted", "true")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("content");
        for (JsonNode row : content) {
            if (row.get("code").asText().equals(code)) {
                return row;
            }
        }
        throw new AssertionError(code + " is not in the deleted list: " + codesOf(content));
    }

    private java.util.List<String> projectCodes(String token, boolean deleted) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/projects")
                        .param("deleted", String.valueOf(deleted))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("content");
        return codesOf(content);
    }

    private java.util.List<String> siteCodes(String token, boolean deleted) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/sites")
                        .param("deleted", String.valueOf(deleted))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return codesOf(objectMapper.readTree(result.getResponse().getContentAsString()));
    }

    private static java.util.List<String> codesOf(JsonNode array) {
        java.util.List<String> codes = new java.util.ArrayList<>();
        array.forEach(node -> codes.add(node.get("code").asText()));
        return codes;
    }

    /** Viplove holds ADMIN, which is the only role granted project:delete and site:delete. */
    private String adminToken() throws Exception {
        return loginToken("viplove");
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

package in.nirman.modules.identity;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin module end to end: onboarding a member, handing over a first-time password,
 * resetting it, and — the part that actually fences the data — staffing a site.
 *
 * <p>The site tests are the ones worth reading. {@code sites.supervisor_id} is a label and
 * {@code user_site_assignments} is the permission; these prove the admin screen keeps the
 * two in step, in both directions, and that a fresh token reflects it.</p>
 */
class AdminOnboardingIntegrationTest extends AbstractIntegrationTest {

    private static final String SEEDED_PASSWORD = "Nirman@123";
    private static final String PROJECT_ID = "30000000-0000-0000-0000-000000000001";
    private static final String SITE_KSN_A = "31000000-0000-0000-0000-000000000001";

    /** Every site this class creates. Named so the cleanup can find them without bookkeeping. */
    private static final String TEST_SITE_PREFIX = "TST-";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * The suite shares one database and MockMvc calls commit, so a site left behind here
     * would show up in another class's count of what a company-wide role can see. The users
     * stay: nothing counts them, and deleting them would mean unpicking the audit trail
     * they now appear in.
     */
    @AfterEach
    void removeCreatedSites() {
        jdbc.update("""
                DELETE FROM user_site_assignments WHERE site_id IN
                    (SELECT id FROM sites WHERE code LIKE ?)""", TEST_SITE_PREFIX + "%");
        // A site now arrives with its own store, and the store holds the site down. Nothing
        // was ever received into it — these sites exist for one assertion apiece — so it goes
        // with the site rather than being kept.
        jdbc.update("""
                DELETE FROM stores WHERE site_id IN
                    (SELECT id FROM sites WHERE code LIKE ?)""", TEST_SITE_PREFIX + "%");
        jdbc.update("DELETE FROM sites WHERE code LIKE ?", TEST_SITE_PREFIX + "%");
    }

    @Test
    @DisplayName("an onboarded member signs in with the handed-over password and must replace it")
    void onboardedMemberMustChangePassword() throws Exception {
        String admin = loginToken("viplove", SEEDED_PASSWORD);
        createUser(admin, "onboard.one", "Onboard One", "SUPERVISOR", "Handover@1");

        JsonNode firstLogin = login("onboard.one", "Handover@1");
        assertThat(firstLogin.get("user").get("mustChangePassword").asBoolean()).isTrue();
        assertThat(firstLogin.get("user").get("roles").get(0).asText()).isEqualTo("SUPERVISOR");

        mockMvc.perform(post("/api/v1/auth/password/change")
                        .header("Authorization", "Bearer " + firstLogin.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"Handover@1","newPassword":"ChosenByMe@9"}"""))
                .andExpect(status().isNoContent());

        // The handover password is dead the moment the member replaces it, which is the
        // whole point of forcing the change: it was known to two people.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"onboard.one\",\"password\":\"Handover@1\"}"))
                .andExpect(status().isUnauthorized());

        JsonNode secondLogin = login("onboard.one", "ChosenByMe@9");
        assertThat(secondLogin.get("user").get("mustChangePassword").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("an admin reset invalidates the member's own password and forces another change")
    void adminResetForcesAnotherChange() throws Exception {
        String admin = loginToken("viplove", SEEDED_PASSWORD);
        String userId = createUser(admin, "onboard.two", "Onboard Two", "SUPERVISOR", "Handover@2");

        String memberToken = loginToken("onboard.two", "Handover@2");
        mockMvc.perform(post("/api/v1/auth/password/change")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"Handover@2","newPassword":"ChosenByMe@8"}"""))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/users/" + userId + "/password/reset")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"temporaryPassword\":\"SecondHandover@3\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"onboard.two\",\"password\":\"ChosenByMe@8\"}"))
                .andExpect(status().isUnauthorized());

        JsonNode afterReset = login("onboard.two", "SecondHandover@3");
        assertThat(afterReset.get("user").get("mustChangePassword").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("naming an engineer on a site is what grants them the site, and only that site")
    void siteStaffingGrantsAccessToThatSiteOnly() throws Exception {
        String admin = loginToken("viplove", SEEDED_PASSWORD);
        String engineerId = createUser(admin, "posted.eng", "Posted Engineer", "ENGINEER",
                "Handover@4");

        // Before any posting the engineer has nothing to look at.
        assertThat(sitesVisibleTo("posted.eng", "Handover@4")).isEmpty();

        String siteId = createSite(admin, "TST-A", "Test Block A", engineerId);

        // A token is issued with the sites claim baked in, so the grant shows up on the
        // next sign-in rather than inside the old fifteen-minute token.
        JsonNode visible = sitesVisibleTo("posted.eng", "Handover@4");
        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).get("id").asText()).isEqualTo(siteId);

        String engineerToken = loginToken("posted.eng", "Handover@4");
        mockMvc.perform(get("/api/v1/sites/" + siteId)
                        .header("Authorization", "Bearer " + engineerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/sites/" + SITE_KSN_A)
                        .header("Authorization", "Bearer " + engineerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("dropping someone from a site takes their access to it away")
    void removingStaffRevokesAccess() throws Exception {
        String admin = loginToken("viplove", SEEDED_PASSWORD);
        String engineerId = createUser(admin, "dropped.eng", "Dropped Engineer", "ENGINEER",
                "Handover@5");
        String siteId = createSite(admin, "TST-B", "Test Block B", engineerId);
        assertThat(sitesVisibleTo("dropped.eng", "Handover@5")).hasSize(1);

        JsonNode site = objectMapper.readTree(mockMvc.perform(get("/api/v1/sites/" + siteId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(put("/api/v1/sites/" + siteId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Test Block B","status":"ACTIVE","standardShiftHours":8.00,
                                 "monthlyWageDays":26,"version":%d}"""
                                .formatted(site.get("version").asLong())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.siteEngineerId").doesNotExist());

        assertThat(sitesVisibleTo("dropped.eng", "Handover@5")).isEmpty();
        String engineerToken = loginToken("dropped.eng", "Handover@5");
        mockMvc.perform(get("/api/v1/sites/" + siteId)
                        .header("Authorization", "Bearer " + engineerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a site refuses staff who do not hold the role it is naming them for")
    void siteRejectsStaffWithoutTheRole() throws Exception {
        String admin = loginToken("viplove", SEEDED_PASSWORD);
        String supervisorId = createUser(admin, "wrong.role", "Wrong Role", "SUPERVISOR",
                "Handover@6");

        mockMvc.perform(post("/api/v1/sites")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"%s","code":"TST-C","name":"Test Block C",
                                 "siteEngineerId":"%s","standardShiftHours":8.00,
                                 "monthlyWageDays":26}"""
                                .formatted(PROJECT_ID, supervisorId)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("a member without a mobile number is refused: it is how they are reached")
    void mobileIsRequired() throws Exception {
        String admin = loginToken("viplove", SEEDED_PASSWORD);
        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"no.mobile","fullName":"No Mobile",
                                 "temporaryPassword":"Handover@9","roleCodes":["SUPERVISOR"],
                                 "siteIds":[]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'mobile')]").exists());
    }

    @Test
    @DisplayName("the member list works unfiltered, by search text and by role")
    void memberListFilters() throws Exception {
        String admin = loginToken("viplove", SEEDED_PASSWORD);
        createUser(admin, "listed.eng", "Listed Engineer", "ENGINEER", "Handover@7");

        // Unfiltered is the case worth naming: with no search text and no role, every
        // optional filter binds null, which is where an untyped parameter would blow up.
        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.username == 'viplove')]").exists())
                .andExpect(jsonPath("$.content[?(@.username == 'listed.eng')]").exists());

        JsonNode engineers = objectMapper.readTree(mockMvc.perform(get("/api/v1/users")
                        .param("role", "ENGINEER")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(engineers.get("content")).allSatisfy(user ->
                assertThat(user.get("roles").toString()).contains("ENGINEER"));
        assertThat(engineers.get("totalElements").asInt())
                .isEqualTo(engineers.get("content").size());   // one row per user, not per role

        mockMvc.perform(get("/api/v1/users")
                        .param("q", "listed")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].username").value("listed.eng"));

        mockMvc.perform(get("/api/v1/users")
                        .param("active", "false")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("only the identity permissions reach user administration")
    void supervisorCannotAdministerUsers() throws Exception {
        String supervisor = loginToken("vivek", SEEDED_PASSWORD);

        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/users/20000000-0000-0000-0000-000000000001/password/reset")
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"temporaryPassword\":\"Hijack@1234\"}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private String createUser(String adminToken, String username, String fullName,
                              String roleCode, String temporaryPassword) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","fullName":"%s","mobile":"+91-9800000000",
                                 "temporaryPassword":"%s","roleCodes":["%s"],"siteIds":[]}"""
                                .formatted(username, fullName, temporaryPassword, roleCode)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createSite(String adminToken, String code, String name, String engineerId)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/sites")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"%s","code":"%s","name":"%s","siteEngineerId":"%s",
                                 "standardShiftHours":8.00,"monthlyWageDays":26}"""
                                .formatted(PROJECT_ID, code, name, engineerId)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    /** Signs in fresh, because the sites claim is fixed at the moment a token is issued. */
    private JsonNode sitesVisibleTo(String username, String password) throws Exception {
        String token = loginToken(username, password);
        MvcResult result = mockMvc.perform(get("/api/v1/sites")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}"
                                .formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String loginToken(String username, String password) throws Exception {
        return login(username, password).get("accessToken").asText();
    }
}

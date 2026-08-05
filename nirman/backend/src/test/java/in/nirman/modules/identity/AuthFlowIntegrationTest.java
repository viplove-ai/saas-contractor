package in.nirman.modules.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 exit criterion: login works end to end — and the refresh rotation contract
 * holds, including the family revocation that turns a stolen-token replay into a dead end.
 */
class AuthFlowIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("login returns tokens, profile, roles, permissions and site scope")
    void loginEndToEnd() throws Exception {
        JsonNode body = login("viplove");

        assertThat(body.get("accessToken").asText()).isNotBlank();
        assertThat(body.get("refreshToken").asText()).isNotBlank();
        assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.get("user").get("username").asText()).isEqualTo("viplove");
        assertThat(body.get("user").get("fullName").asText()).isEqualTo("Viplove Chaudhary");
        assertThat(body.get("user").get("allSites").asBoolean()).isTrue();
        assertThat(body.get("user").get("roles").toString()).contains("ADMIN");
        assertThat(body.get("user").get("permissions").size()).isGreaterThan(30);

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + body.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("viplove"));
    }

    /**
     * One person, several hats: the seeded logins are people rather than roles, so the
     * claims have to be the union of every role held, not the first one found.
     */
    @Test
    @DisplayName("a multi-role user gets the union of the permissions of all their roles")
    void multiRoleUserGetsUnionOfPermissions() throws Exception {
        JsonNode user = login("uttam").get("user");

        assertThat(toStrings(user.get("roles")))
                .containsExactlyInAnyOrder("ADMIN", "ENGINEER", "ACCOUNTANT");

        List<String> permissions = toStrings(user.get("permissions"));
        assertThat(permissions).contains(
                "user:write",            // ADMIN only
                "attendance:verify",     // ENGINEER
                "payment:record");       // ACCOUNTANT
        assertThat(permissions).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a supervisor's token carries only the assigned site")
    void supervisorTokenIsSiteScoped() throws Exception {
        JsonNode body = login("vivek");
        assertThat(body.get("user").get("fullName").asText()).isEqualTo("Vivek Aggarwal");
        assertThat(body.get("user").get("allSites").asBoolean()).isFalse();
        assertThat(body.get("user").get("siteIds")).hasSize(1);
        assertThat(body.get("user").get("siteIds").get(0).asText())
                .isEqualTo("31000000-0000-0000-0000-000000000001");
    }

    /**
     * Viplove holds SUPERVISOR alongside ADMIN. The site claim must follow the widest role
     * he holds, not the narrowest — otherwise a posting to one site would quietly cost an
     * administrator access to every other one.
     */
    @Test
    @DisplayName("holding SUPERVISOR alongside ADMIN still yields the ALL-sites claim")
    void adminWhoAlsoSupervisesStillSeesEverything() throws Exception {
        JsonNode user = login("viplove").get("user");
        assertThat(toStrings(user.get("roles"))).contains("SUPERVISOR", "ADMIN");
        assertThat(user.get("allSites").asBoolean()).isTrue();
        assertThat(user.get("siteIds")).isEmpty();
    }

    @Test
    @DisplayName("wrong password is a generic 401 and reveals nothing")
    void wrongPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"uttam\",\"password\":\"WrongPassword1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("The username or password is incorrect."));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"no.such.user\",\"password\":\"WrongPassword1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("The username or password is incorrect."));
    }

    @Test
    @DisplayName("refresh rotates the token, and reusing the rotated one kills the family")
    void refreshRotationAndReuseDetection() throws Exception {
        JsonNode loginBody = login("vivek");
        String firstRefresh = loginBody.get("refreshToken").asText();

        // First refresh: succeeds and hands back a different token.
        JsonNode rotated = refresh(firstRefresh, 200);
        String secondRefresh = rotated.get("refreshToken").asText();
        assertThat(secondRefresh).isNotEqualTo(firstRefresh);

        // Replaying the rotated token is the theft signature: 401 and family revoked.
        refresh(firstRefresh, 401);

        // The revocation includes the newest token — the whole family is dead.
        refresh(secondRefresh, 401);
    }

    @Test
    @DisplayName("logout revokes the refresh family")
    void logoutRevokesFamily() throws Exception {
        JsonNode body = login("uttam");
        String refreshToken = body.get("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + body.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isNoContent());

        refresh(refreshToken, 401);
    }

    @Test
    @DisplayName("an unauthenticated request to a protected endpoint gets the uniform 401 body")
    void unauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/sites"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://nirman/errors/unauthenticated"));
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> toStrings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private JsonNode refresh(String refreshToken, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}

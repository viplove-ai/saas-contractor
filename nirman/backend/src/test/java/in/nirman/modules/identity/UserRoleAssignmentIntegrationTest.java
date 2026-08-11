package in.nirman.modules.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Roles are a set a member holds, not a single job title. These cover the administration
 * end of that: several roles granted at onboarding, a set replaced wholesale afterwards,
 * and the one change refused — an administrator taking the role-assigning power off their
 * own account, which nobody but another administrator could then give back.
 *
 * <p>Every test onboards its own member. Nothing here rolls back, so touching a seeded
 * login would change what the other identity tests find.</p>
 */
class UserRoleAssignmentIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String VIPLOVE = "20000000-0000-0000-0000-000000000001";   // ADMIN

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("a member can be onboarded holding several roles at once")
    void severalRolesAtOnboarding() throws Exception {
        String token = login("viplove");
        String id = onboard(token, "\"ENGINEER\",\"ACCOUNTANT\"");

        mockMvc.perform(get("/api/v1/users/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(2))
                .andExpect(jsonPath("$.roles").value(containsInAnyOrder(
                        "ENGINEER", "ACCOUNTANT")));
    }

    @Test
    @DisplayName("assigning roles replaces the whole set, adding and removing in one call")
    void setIsReplacedWholesale() throws Exception {
        String token = login("viplove");
        String id = onboard(token, "\"SUPERVISOR\"");

        mockMvc.perform(putRoles(id, token, "\"SUPERVISOR\",\"ENGINEER\",\"ACCOUNTANT\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(3));

        mockMvc.perform(putRoles(id, token, "\"ENGINEER\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0]").value("ENGINEER"));
    }

    /**
     * The union is what the member ends up with, so a role added at 10:00 has to reach the
     * token — which it does at the next login or refresh, not before.
     */
    @Test
    @DisplayName("a second role widens what the member may do at their next sign-in")
    void secondRoleWidensThePrincipal() throws Exception {
        String token = login("viplove");
        String username = "multi." + UUID.randomUUID().toString().substring(0, 8);
        String id = onboard(token, "\"SUPERVISOR\"", username);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.permissions")
                        .value(not(hasItem("attendance:verify"))))
                .andExpect(jsonPath("$.user.allSites").value(false));

        mockMvc.perform(putRoles(id, token, "\"SUPERVISOR\",\"ENGINEER\",\"ACCOUNTANT\""))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.permissions")
                        .value(hasItem("attendance:verify")))
                .andExpect(jsonPath("$.user.permissions")
                        .value(hasItem("payment:record")))
                // ACCOUNTANT is company-wide: the widest role held decides the site claim.
                .andExpect(jsonPath("$.user.allSites").value(true));
    }

    @Test
    @DisplayName("an administrator cannot untick the role that lets them assign roles")
    void cannotTakeRoleAssignmentOffYourself() throws Exception {
        String token = login("viplove");

        mockMvc.perform(putRoles(VIPLOVE, token, "\"SUPERVISOR\",\"ACCOUNTANT\""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://nirman/errors/user.self-role-lockout"));

        // Refused before the set was replaced, so he still holds what he came in with.
        mockMvc.perform(get("/api/v1/users/" + VIPLOVE).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").value(hasItem("ADMIN")));
    }

    @Test
    @DisplayName("an unknown role code is refused and nothing is written")
    void unknownRoleCode() throws Exception {
        String token = login("viplove");
        String id = onboard(token, "\"SUPERVISOR\"");

        mockMvc.perform(putRoles(id, token, "\"SUPERVISOR\",\"FOREMAN\""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://nirman/errors/role.unknown"));

        mockMvc.perform(get("/api/v1/users/" + id).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0]").value("SUPERVISOR"));
    }

    @Test
    @DisplayName("a member with no role at all is refused")
    void atLeastOneRole() throws Exception {
        String token = login("viplove");
        String id = onboard(token, "\"SUPERVISOR\"");

        mockMvc.perform(putRoles(id, token, ""))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ helpers

    private String onboard(String token, String roleCodesJson) throws Exception {
        return onboard(token, roleCodesJson, "multi." + UUID.randomUUID().toString().substring(0, 8));
    }

    private String onboard(String token, String roleCodesJson, String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","fullName":"Test Member","mobile":"+91-9800000999",
                                 "temporaryPassword":"%s","roleCodes":[%s],"siteIds":[]}
                                """.formatted(username, PASSWORD, roleCodesJson)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private static MockHttpServletRequestBuilder putRoles(String userId, String token, String roleCodesJson) {
        return put("/api/v1/users/" + userId + "/roles")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleCodes\":[" + roleCodesJson + "]}");
    }

    private static String credentials(String username) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    private String login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}

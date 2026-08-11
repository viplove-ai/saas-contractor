package in.nirman.modules.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The members screen and the sites register describe two different things, and this is the
 * one place they could contradict each other.
 *
 * <p>Who is <b>named</b> on a site — its engineer, its supervisor — is one of each and is
 * printed on the register. Who may <b>open</b> the site is a set of assignment rows and is
 * what the access guard reads. Naming somebody grants them the site; the members screen can
 * grant and withdraw the rows directly, and until this rule it could withdraw the site from
 * the engineer named on it, leaving the register and the door disagreeing.</p>
 */
class SitePostingIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String UTTAM = "20000000-0000-0000-0000-000000000002";  // engineer on both
    private static final String KSN_A = "31000000-0000-0000-0000-000000000001";
    private static final String KSN_B = "31000000-0000-0000-0000-000000000002";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("a site cannot be withdrawn from the engineer named on it")
    void refusesToUnpostTheNamedEngineer() throws Exception {
        String token = login("viplove");

        mockMvc.perform(putSites(UTTAM, token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://nirman/errors/user.still-posted"))
                // Both sites in one refusal: an admin clearing a member should not learn
                // about the second one only after acting on the first.
                .andExpect(jsonPath("$.detail").value(containsString("KSN-A")))
                .andExpect(jsonPath("$.detail").value(containsString("KSN-B")))
                .andExpect(jsonPath("$.detail").value(containsString("site engineer")));

        // Refused before anything was written: he still reaches both sites.
        mockMvc.perform(get("/api/v1/users/" + UTTAM + "/sites")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("a site nobody named them on is withdrawn as before")
    void withdrawsAnOrdinaryPosting() throws Exception {
        String token = login("viplove");
        String id = onboard(token);

        mockMvc.perform(putSites(id, token, KSN_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // Nobody named him on KSN-A, so taking it back is the members screen's own business.
        mockMvc.perform(putSites(id, token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/" + id + "/sites")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                // The row stays as the explanation for last month's records; it is closed,
                // and a closed row is what "no longer posted" looks like here.
                .andExpect(jsonPath("$[0].assignedTo").isNotEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putSites(
            String userId, String token, String... siteIds) {
        String ids = siteIds.length == 0 ? "" : "\"" + String.join("\",\"", siteIds) + "\"";
        return put("/api/v1/users/" + userId + "/sites")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"siteIds\":[" + ids + "]}");
    }

    private String onboard(String token) throws Exception {
        String username = "posted." + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","fullName":"Test Member","mobile":"+91-9800000998",
                                 "temporaryPassword":"%s","roleCodes":["SUPERVISOR"],"siteIds":[]}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}

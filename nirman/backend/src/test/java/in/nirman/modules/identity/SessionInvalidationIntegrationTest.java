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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ending a session has to end it on the handset that is already holding a token.
 *
 * <p>Revoking the refresh family was always the easy half — it stops the <em>next</em>
 * fifteen minutes. These tests are about this one: a reset is asked for because a phone has
 * gone missing or a number has changed hands, and an answer that takes effect at the next
 * token expiry is not an answer to that.</p>
 *
 * <p>Each test onboards its own member rather than using a seeded login. Resetting
 * {@code vivek}'s password here would leave every other test in the suite unable to sign
 * in as him, and which test noticed first would depend on the order they happened to run
 * in.</p>
 */
class SessionInvalidationIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String NEW_PASSWORD = "Nirman@456";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("an admin reset refuses the access token already on the handset")
    void resetEndsLiveAccessTokens() throws Exception {
        String adminToken = login("viplove", PASSWORD).get("accessToken").asText();
        String userId = onboard(adminToken, "reset.victim");

        JsonNode session = login("reset.victim", PASSWORD);
        String accessToken = session.get("accessToken").asText();
        String refreshToken = session.get("refreshToken").asText();
        assertProfileReadable(accessToken);

        mockMvc.perform(post("/api/v1/users/" + userId + "/password/reset")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"temporaryPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        // Not at the next expiry — on the next request. This is the whole point.
        assertProfileRefused(accessToken);
        // And the refresh family is gone too, so the handset cannot buy a replacement.
        refreshRefused(refreshToken);
    }

    /**
     * Every device, not merely the ones that happen to ask for a new token. Two sessions
     * are opened for one member because "logged out everywhere" is only testable with a
     * somewhere else to be logged out of.
     */
    @Test
    @DisplayName("a reset ends every open session, not only the newest")
    void resetEndsEverySession() throws Exception {
        String adminToken = login("viplove", PASSWORD).get("accessToken").asText();
        String userId = onboard(adminToken, "reset.twohandsets");

        String phone = login("reset.twohandsets", PASSWORD).get("accessToken").asText();
        String tablet = login("reset.twohandsets", PASSWORD).get("accessToken").asText();
        assertProfileReadable(phone);
        assertProfileReadable(tablet);

        mockMvc.perform(post("/api/v1/users/" + userId + "/password/reset")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"temporaryPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        assertProfileRefused(phone);
        assertProfileRefused(tablet);

        // The new password is the only way back in, and it works.
        assertProfileReadable(login("reset.twohandsets", NEW_PASSWORD).get("accessToken").asText());
    }

    /**
     * The same rule when the member does it themselves. Their own tab goes with the rest —
     * nothing here can tell which live session is the one at the keyboard — and the client
     * covers that by spending the new password on a fresh sign-in immediately.
     */
    @Test
    @DisplayName("changing your own password ends the sessions the old one was holding open")
    void selfChangeEndsEverySession() throws Exception {
        String adminToken = login("viplove", PASSWORD).get("accessToken").asText();
        onboard(adminToken, "change.self");

        String desk = login("change.self", PASSWORD).get("accessToken").asText();
        String phone = login("change.self", PASSWORD).get("accessToken").asText();

        mockMvc.perform(post("/api/v1/auth/password/change")
                        .header("Authorization", "Bearer " + desk)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD
                                + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        assertProfileRefused(phone);
        assertProfileRefused(desk);
        assertProfileReadable(login("change.self", NEW_PASSWORD).get("accessToken").asText());
    }

    @Test
    @DisplayName("deactivating a member shuts the handset already signed in as them")
    void deactivationEndsLiveAccessTokens() throws Exception {
        String adminToken = login("viplove", PASSWORD).get("accessToken").asText();
        String userId = onboard(adminToken, "deactivate.victim");

        String accessToken = login("deactivate.victim", PASSWORD).get("accessToken").asText();
        assertProfileReadable(accessToken);

        mockMvc.perform(patch("/api/v1/users/" + userId + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());

        assertProfileRefused(accessToken);
    }

    /** A counter per account, not a global one: one reset must not sign the office out. */
    @Test
    @DisplayName("one member's reset leaves everybody else signed in")
    void resetIsPerAccount() throws Exception {
        String adminToken = login("viplove", PASSWORD).get("accessToken").asText();
        String userId = onboard(adminToken, "reset.neighbour");
        login("reset.neighbour", PASSWORD);

        mockMvc.perform(post("/api/v1/users/" + userId + "/password/reset")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"temporaryPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        assertProfileReadable(adminToken);
    }

    // ------------------------------------------------------------------ helpers

    private String onboard(String adminToken, String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","fullName":"%s","mobile":"9800000000",
                                 "temporaryPassword":"%s","roleCodes":["SUPERVISOR"]}
                                """.formatted(username, username, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private JsonNode login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void assertProfileReadable(String accessToken) throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    /**
     * A stale token leaves the request anonymous rather than producing an error of its own,
     * so what comes back is the ordinary unauthenticated body — the same one an unsigned
     * request gets, which is also the one the client already knows how to act on.
     */
    private void assertProfileRefused(String accessToken) throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://nirman/errors/unauthenticated"));
    }

    private void refreshRefused(String refreshToken) throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }
}

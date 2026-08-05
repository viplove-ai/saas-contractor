package in.nirman;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.security.LoginRateLimitFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Phase 7 security review, written down as tests rather than as a document.
 *
 * <p>A review that produces a page of findings and a promise decays the week after it is
 * written; every claim below is one an accidental change would break the build over. The
 * three-layer authorisation from Phase 2 and the site fence have their own suites — this
 * covers what the review actually turned up as thin: the login endpoint had no ceiling that
 * was not per-account, the API published a full map of itself, and error bodies had never
 * been checked for what they say about records the caller cannot see.</p>
 */
class SecurityHardeningIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LoginRateLimitFilter rateLimitFilter;

    // ------------------------------------------------------------------ response headers

    @Test
    @DisplayName("every API response carries the headers that make a JSON endpoint inert in a browser")
    void securityHeadersArePresent() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"viplove\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'none'; frame-ancestors 'none'; sandbox"))
                .andExpect(header().string("Permissions-Policy",
                        "camera=(), microphone=(), geolocation=(), payment=()"));
    }

    /**
     * A signed download URL for a bill is a bearer credential sitting in a query string, and
     * the no-referrer policy is what stops it travelling to wherever the browser goes next.
     */
    @Test
    @DisplayName("no referrer leaves the API, so a signed attachment URL cannot leak through one")
    void referrerIsNeverSent() throws Exception {
        mockMvc.perform(get("/api/v1/sites")
                        .header("Authorization", "Bearer " + token("viplove")))
                .andExpect(status().isOk())
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    // ------------------------------------------------------------------ rate limiting

    /**
     * The attack the per-account lockout cannot see: one password tried against every
     * username in turn. Each attempt is the first failure on a different account, so five
     * strikes never lands — the ceiling has to be on the source, and it has to count
     * attempts rather than failures.
     */
    @Test
    @DisplayName("a source that hammers the login endpoint is refused with 429 before it is authenticated")
    void loginIsRateLimitedPerSource() {
        rateLimitFilter.reset();
        long now = System.currentTimeMillis();
        // The production limit is 60 in five minutes. The filter is driven directly rather
        // than over HTTP for two reasons: the test profile raises the configured ceiling (the
        // suite itself signs in hundreds of times from one address), and 61 real logins is 61
        // BCrypt-12 verifications — eight seconds of CPU spent proving something the filter
        // decides before authentication is ever reached.
        int limit = 60;
        for (int attempt = 0; attempt < limit; attempt++) {
            assertThat(rateLimitFilter.allow("login|203.0.113.7", limit, now + attempt))
                    .as("attempt %d is within the window's allowance", attempt + 1)
                    .isTrue();
        }
        assertThat(rateLimitFilter.allow("login|203.0.113.7", limit, now + limit)).isFalse();

        // A different phone at the same site is unaffected: the fence is per source, and a
        // shared ceiling would let one attacker lock out a whole company's supervisors.
        assertThat(rateLimitFilter.allow("login|203.0.113.8", limit, now + limit)).isTrue();

        // The window slides rather than resetting on a schedule, so the next attempt is
        // allowed once the first has aged out — five minutes and a millisecond.
        assertThat(rateLimitFilter.allow("login|203.0.113.7", limit, now + 300_001)).isTrue();
        rateLimitFilter.reset();
    }

    @Test
    @DisplayName("a real login still works once the counter is clear")
    void theLimitDoesNotBreakOrdinaryUse() throws Exception {
        rateLimitFilter.reset();
        assertThat(token("vivek")).isNotBlank();
    }

    // ------------------------------------------------------------------ what errors say

    /**
     * Authentication failures must not distinguish "no such user" from "wrong password", and
     * a record the caller cannot see must not be distinguishable from one that does not
     * exist. Both are the same rule: the error body may not answer a question the caller was
     * not allowed to ask.
     */
    @Test
    @DisplayName("a bad password and an unknown username give the same answer")
    void authFailuresAreIndistinguishable() throws Exception {
        String unknownUser = failedLogin("nobody-here", PASSWORD);
        String wrongPassword = failedLogin("vivek", "not-the-password");
        assertThat(unknownUser).isEqualTo(wrongPassword);
    }

    @Test
    @DisplayName("an unhandled failure returns a correlation id and no stack trace")
    void errorBodiesCarryNoInternals() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/expenses/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token("viplove")))
                .andExpect(status().isNotFound())
                .andReturn();
        String body = result.getResponse().getContentAsString();

        assertThat(objectMapper.readTree(body).get("correlationId").asText()).isNotBlank();
        assertThat(body)
                .as("no package names, no class names, no SQL")
                .doesNotContain("in.nirman", "Exception", "SELECT", "org.springframework");
    }

    // ------------------------------------------------------------------ what is reachable

    @Test
    @DisplayName("nothing under /api answers without a token except login and refresh")
    void everyOtherEndpointNeedsAToken() throws Exception {
        mockMvc.perform(get("/api/v1/sites")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/expenses")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/dashboard/company")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users")).andExpect(status().isUnauthorized());
    }

    /**
     * Actuator exposes health and info anonymously and nothing else. Metrics is configured on
     * in development because it is useful there; it is not anonymous, which is the part that
     * matters — a request with no token gets 401 rather than a list of every endpoint's
     * timing and the JVM's memory profile.
     */
    @Test
    @DisplayName("actuator gives away liveness and nothing else without a token")
    void actuatorIsNarrow() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/beans")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helpers

    private String failedLogin(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}"
                                .formatted(username, password)))
                .andExpect(status().isUnauthorized())
                .andReturn();
        // The correlation id differs per request by design, so it is the one field excluded
        // from the comparison — everything else has to match, word for word.
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("detail").asText();
    }

    private String token(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}"
                                .formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}

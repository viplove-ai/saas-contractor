package in.nirman.modules.labour;

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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A man lent between two sites (V64). He stands on both rosters, may be marked a half day at
 * each, and may not be paid for more than a day across the two — the rule the single open
 * posting used to enforce by shape, now enforced as arithmetic.
 *
 * <p>Every test takes on its own man, and the days are in November 2025 where nothing else
 * in the suite marks anybody.</p>
 */
class SharedWorkerIntegrationTest extends AbstractIntegrationTest {

    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";
    private static final String SITE_B = "31000000-0000-0000-0000-000000000002";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /** The suite shares a database, and the site-scope test counts the men on KSN-A. */
    @AfterEach
    void removeCreatedWorkers() {
        String mine = "full_name LIKE 'SW %'";
        for (String table : List.of("worker_site_allocations", "attendance_records", "wage_rates")) {
            jdbc.update("DELETE FROM " + table + " WHERE worker_id IN (SELECT id FROM workers WHERE " + mine + ")");
        }
        jdbc.update("DELETE FROM workers WHERE " + mine);
    }

    @Test
    @DisplayName("a shared man stands on both rosters and reads as posted to both")
    void sharedManIsOnBothRosters() throws Exception {
        String admin = loginToken("viplove");
        String worker = takeOn(admin, "SW Dono Jagah", SITE_A);
        LocalDate day = LocalDate.of(2025, 11, 10);

        share(admin, worker, SITE_B, day).andExpect(status().isCreated())
                .andExpect(jsonPath("$.siteId").value(SITE_B))
                .andExpect(jsonPath("$.effectiveTo").doesNotExist());

        assertThat(rosterNames(admin, SITE_A, day)).contains("SW Dono Jagah");
        assertThat(rosterNames(admin, SITE_B, day)).contains("SW Dono Jagah");
        // The day before the share the annexe's roll reaches back for him as a man posted
        // later, labelled with the day the share begins — the same as any late posting.
        JsonNode eve = roster(admin, SITE_B, day.minusDays(1));
        JsonNode him = null;
        for (JsonNode entry : eve.get("entries")) {
            if (entry.get("workerName").asText().equals("SW Dono Jagah")) {
                him = entry;
            }
        }
        assertThat(him).isNotNull();
        assertThat(him.get("postedFrom").asText()).isEqualTo(day.toString());

        JsonNode read = objectMapper.readTree(mockMvc.perform(get("/api/v1/workers/" + worker)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<String> sites = new ArrayList<>();
        read.get("currentSiteIds").forEach(node -> sites.add(node.asText()));
        assertThat(sites).containsExactly(SITE_A, SITE_B);
        assertThat(read.get("currentSiteId").asText())
                .as("the site he has stood on longest is still the one a single answer names")
                .isEqualTo(SITE_A);

        // Sharing him with a site he already stands on is a 409, not a second posting.
        share(admin, worker, SITE_B, day).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a day may be split between two sites as two half days, and no more than that")
    void aDayIsSplitAsHalvesAndNeverOverclaimed() throws Exception {
        String admin = loginToken("viplove");
        String worker = takeOn(admin, "SW Aadha Aadha", SITE_A);
        share(admin, worker, SITE_B, LocalDate.of(2025, 11, 1)).andExpect(status().isCreated());

        // Morning at the main block, afternoon at the annexe.
        LocalDate split = LocalDate.of(2025, 11, 12);
        mark(admin, SITE_A, split, worker, "HALF_DAY").andExpect(jsonPath("$.accepted").value(1));
        mark(admin, SITE_B, split, worker, "HALF_DAY").andExpect(jsonPath("$.accepted").value(1));

        // A whole day at one site and a half at the other is a day and a half.
        LocalDate over = LocalDate.of(2025, 11, 13);
        mark(admin, SITE_A, over, worker, "HALF_DAY").andExpect(jsonPath("$.accepted").value(1));
        mark(admin, SITE_B, over, worker, "PRESENT")
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.outcomes[0].reason")
                        .value(org.hamcrest.Matchers.containsString("already marked a half day at another site")));

        // Present at one site and absent at the other is one day, said twice.
        LocalDate whole = LocalDate.of(2025, 11, 14);
        mark(admin, SITE_A, whole, worker, "PRESENT").andExpect(jsonPath("$.accepted").value(1));
        mark(admin, SITE_B, whole, worker, "ABSENT").andExpect(jsonPath("$.accepted").value(1));
        // And present at both is the double count the old posting caught.
        LocalDate twice = LocalDate.of(2025, 11, 15);
        mark(admin, SITE_A, twice, worker, "PRESENT").andExpect(jsonPath("$.accepted").value(1));
        mark(admin, SITE_B, twice, worker, "PRESENT")
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.outcomes[0].reason")
                        .value(org.hamcrest.Matchers.containsString("already marked present at another site")));

        // Editing a half day up to a whole one claims the half the other site already has.
        UUID edited = UUID.randomUUID();
        mark(admin, SITE_A, LocalDate.of(2025, 11, 17), worker, "HALF_DAY", edited)
                .andExpect(jsonPath("$.accepted").value(1));
        mark(admin, SITE_B, LocalDate.of(2025, 11, 17), worker, "HALF_DAY")
                .andExpect(jsonPath("$.accepted").value(1));
        mark(admin, SITE_A, LocalDate.of(2025, 11, 17), worker, "PRESENT", edited)
                .andExpect(jsonPath("$.rejected").value(1));
    }

    @Test
    @DisplayName("a share is ended on a last day, never his last posting")
    void endingAShare() throws Exception {
        String admin = loginToken("viplove");
        String worker = takeOn(admin, "SW Wapas", SITE_A);
        LocalDate from = LocalDate.of(2025, 11, 3);
        String shareId = objectMapper.readTree(share(admin, worker, SITE_B, from)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asText();

        // Before it began is not a last day.
        end(admin, worker, shareId, from.minusDays(1)).andExpect(status().isUnprocessableEntity());

        LocalDate last = LocalDate.of(2025, 11, 20);
        end(admin, worker, shareId, last).andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveTo").value(last.toString()));
        assertThat(rosterNames(admin, SITE_B, last)).contains("SW Wapas");
        assertThat(rosterNames(admin, SITE_B, last.plusDays(1))).doesNotContain("SW Wapas");
        assertThat(rosterNames(admin, SITE_A, last.plusDays(1))).contains("SW Wapas");

        // The main block is now the only site he stands on, and that posting cannot be ended.
        String home = objectMapper.readTree(mockMvc.perform(get("/api/v1/workers/" + worker + "/allocations")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .findValuesAsText("id").stream().filter(id -> !id.equals(shareId)).findFirst().orElseThrow();
        end(admin, worker, home, last).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("allocation.last-posting")));
        end(admin, worker, shareId, last).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a transfer ends every posting a shared man holds")
    void transferClosesEveryPosting() throws Exception {
        String admin = loginToken("viplove");
        String worker = takeOn(admin, "SW Chala Gaya", SITE_A);
        share(admin, worker, SITE_B, LocalDate.of(2025, 11, 3)).andExpect(status().isCreated());

        // Sent to the annexe outright: the main block's posting ends with the share's.
        mockMvc.perform(post("/api/v1/workers/" + worker + "/allocations")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"siteId\":\"%s\",\"effectiveFrom\":\"2025-11-24\"}".formatted(SITE_B)))
                .andExpect(status().isCreated());

        assertThat(rosterNames(admin, SITE_A, LocalDate.of(2025, 11, 24))).doesNotContain("SW Chala Gaya");
        assertThat(rosterNames(admin, SITE_B, LocalDate.of(2025, 11, 24))).contains("SW Chala Gaya");
        JsonNode read = objectMapper.readTree(mockMvc.perform(get("/api/v1/workers/" + worker)
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString());
        assertThat(read.get("currentSiteIds").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("a supervisor shares a man among his own sites and nowhere else")
    void supervisorSharesWithinHisSites() throws Exception {
        String admin = loginToken("viplove");
        String both = supervisor(admin, "share.both", List.of(SITE_A, SITE_B));
        String onlyA = supervisor(admin, "share.one", List.of(SITE_A));
        String worker = takeOn(admin, "SW Udhaar", SITE_A);
        LocalDate day = LocalDate.of(2025, 11, 5);

        // A supervisor of the main block alone may move him to the annexe but not lend him
        // there: a handover is to somebody else's site by nature, a share keeps him on yours.
        share(onlyA, worker, SITE_B, day).andExpect(status().isForbidden());
        share(both, worker, SITE_B, day).andExpect(status().isCreated());

        // And a man who is not his at all is not his to lend.
        String annexeMan = takeOn(admin, "SW Paraya", SITE_B);
        share(onlyA, annexeMan, SITE_A, day).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private String takeOn(String token, String name, String siteId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/workers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"%s","mobile":"+91-9800000441","siteId":"%s",
                                 "joiningDate":"2025-10-01","normalRate":600}"""
                                .formatted(name, siteId)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private org.springframework.test.web.servlet.ResultActions share(String token, String worker,
                                                                     String siteId, LocalDate from)
            throws Exception {
        return mockMvc.perform(post("/api/v1/workers/" + worker + "/allocations/share")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"siteId\":\"%s\",\"effectiveFrom\":\"%s\"}".formatted(siteId, from)));
    }

    private org.springframework.test.web.servlet.ResultActions end(String token, String worker,
                                                                   String allocationId, LocalDate lastDay)
            throws Exception {
        return mockMvc.perform(post("/api/v1/workers/" + worker + "/allocations/" + allocationId + "/end")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lastDay\":\"%s\"}".formatted(lastDay)));
    }

    private org.springframework.test.web.servlet.ResultActions mark(String token, String siteId,
                                                                    LocalDate day, String worker,
                                                                    String status) throws Exception {
        return mark(token, siteId, day, worker, status, UUID.randomUUID());
    }

    private org.springframework.test.web.servlet.ResultActions mark(String token, String siteId,
                                                                    LocalDate day, String worker,
                                                                    String status, UUID id) throws Exception {
        String hours = status.equals("HALF_DAY") ? "3.5" : "7";
        return mockMvc.perform(post("/api/v1/attendance/bulk")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"siteId":"%s","date":"%s","entries":[
                                  {"id":"%s","workerId":"%s","status":"%s","breakMinutes":0,"enteredHours":%s}]}"""
                                .formatted(siteId, day, id, worker, status, hours)))
                .andExpect(status().isOk());
    }

    private JsonNode roster(String token, String siteId, LocalDate day) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/attendance/roster")
                        .param("siteId", siteId)
                        .param("date", day.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** The men on the roll that morning — not the ones the roll reaches back for. */
    private List<String> rosterNames(String token, String siteId, LocalDate day) throws Exception {
        List<String> names = new ArrayList<>();
        for (JsonNode entry : roster(token, siteId, day).get("entries")) {
            if (!entry.has("postedFrom")) {
                names.add(entry.get("workerName").asText());
            }
        }
        return names;
    }

    /** A supervisor posted to exactly these sites, signed in on his temporary password. */
    private String supervisor(String adminToken, String username, List<String> siteIds) throws Exception {
        String sites = String.join(",", siteIds.stream().map(id -> "\"" + id + "\"").toList());
        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","fullName":"%s","mobile":"+91-9800000442",
                                 "temporaryPassword":"Handover@9","roleCodes":["SUPERVISOR"],
                                 "siteIds":[%s]}""".formatted(username, username, sites)))
                .andExpect(status().isCreated());
        return loginToken(username, "Handover@9");
    }

    private String loginToken(String username) throws Exception {
        return loginToken(username, "Nirman@123");
    }

    private String loginToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
}

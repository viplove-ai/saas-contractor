package in.nirman.modules.project;

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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A site is run by however many people run it.
 *
 * <p>One engineer and one supervisor was never a fact about a site, it was a rule about how
 * many people may work there — and because naming somebody on a site is what grants them
 * access to it, the second supervisor was not merely unlisted, he was locked out. These
 * tests are the two halves of removing that rule: the register carries as many names as it
 * is given, and each of them reaches the site.</p>
 */
class SiteStaffingIntegrationTest extends AbstractIntegrationTest {

    private static final String SEEDED_PASSWORD = "Nirman@123";
    private static final String PROJECT_ID = "30000000-0000-0000-0000-000000000001";
    private static final String KSN_A = "31000000-0000-0000-0000-000000000001";
    private static final String TEST_SITE_PREFIX = "MSS-";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * The suite shares one database and MockMvc calls commit, so a site left here would turn
     * up in another class's count of what a company-wide role can see. The members stay:
     * nothing counts them, and they are in the audit trail now.
     */
    @AfterEach
    void removeCreatedSites() {
        jdbc.update("""
                DELETE FROM user_site_assignments WHERE site_id IN
                    (SELECT id FROM sites WHERE code LIKE ?)""", TEST_SITE_PREFIX + "%");
        jdbc.update("""
                DELETE FROM site_staff WHERE site_id IN
                    (SELECT id FROM sites WHERE code LIKE ?)""", TEST_SITE_PREFIX + "%");
        jdbc.update("""
                DELETE FROM stores WHERE site_id IN
                    (SELECT id FROM sites WHERE code LIKE ?)""", TEST_SITE_PREFIX + "%");
        jdbc.update("DELETE FROM sites WHERE code LIKE ?", TEST_SITE_PREFIX + "%");
    }

    @Test
    @DisplayName("a site takes two supervisors, and both of them reach it")
    void twoSupervisorsOnOneSite() throws Exception {
        String admin = login("viplove", SEEDED_PASSWORD);
        String day = onboard(admin, "shift.day", "SUPERVISOR");
        String night = onboard(admin, "shift.night", "SUPERVISOR");

        JsonNode site = createSite(admin, "MSS-A", "Two Shift Block", List.of(), List.of(day, night));
        assertThat(ids(site.get("supervisorIds"))).containsExactlyInAnyOrder(day, night);

        // The register naming them is what opens the door; neither of them was ever posted
        // through the members screen.
        assertThat(siteIdsVisibleTo("shift.day")).containsExactly(site.get("id").asText());
        assertThat(siteIdsVisibleTo("shift.night")).containsExactly(site.get("id").asText());
    }

    @Test
    @DisplayName("a site takes two engineers as readily as two supervisors")
    void twoEngineersOnOneSite() throws Exception {
        String admin = login("viplove", SEEDED_PASSWORD);
        String civil = onboard(admin, "eng.civil", "ENGINEER");
        String electrical = onboard(admin, "eng.electrical", "ENGINEER");

        JsonNode site = createSite(admin, "MSS-B", "Two Discipline Block",
                List.of(civil, electrical), List.of());
        assertThat(ids(site.get("siteEngineerIds")))
                .containsExactlyInAnyOrder(civil, electrical);
        assertThat(siteIdsVisibleTo("eng.civil")).containsExactly(site.get("id").asText());
        assertThat(siteIdsVisibleTo("eng.electrical")).containsExactly(site.get("id").asText());
    }

    /**
     * The lists are sent whole rather than as a delta, so this is also the test that a name
     * left out of the next save is a name taken off the site.
     */
    @Test
    @DisplayName("saving the register without a name takes that name's access away")
    void droppingOneOfTwoLeavesTheOther() throws Exception {
        String admin = login("viplove", SEEDED_PASSWORD);
        String staying = onboard(admin, "stays.on", "SUPERVISOR");
        String leaving = onboard(admin, "moves.off", "SUPERVISOR");

        JsonNode site = createSite(admin, "MSS-C", "Handover Block", List.of(),
                List.of(staying, leaving));
        String siteId = site.get("id").asText();

        JsonNode saved = updateSite(admin, siteId, "Handover Block", List.of(), List.of(staying),
                site.get("version").asLong());
        assertThat(ids(saved.get("supervisorIds"))).containsExactly(staying);

        assertThat(siteIdsVisibleTo("stays.on")).containsExactly(siteId);
        assertThat(siteIdsVisibleTo("moves.off")).isEmpty();
    }

    /**
     * The small job where the engineer supervises his own site. Both posts, one man, and
     * one grant of access — which is the case the delta arithmetic in {@code replaceStaff}
     * has to get right, because he appears in both the old and the new set every time.
     */
    @Test
    @DisplayName("one man may hold both posts, and a re-save does not shut him out")
    void oneManBothPosts() throws Exception {
        String admin = login("viplove", SEEDED_PASSWORD);
        String bothHats = onboard(admin, "both.hats", "ENGINEER", "SUPERVISOR");

        JsonNode site = createSite(admin, "MSS-D", "One Man Block", List.of(bothHats),
                List.of(bothHats));
        String siteId = site.get("id").asText();
        assertThat(ids(site.get("siteEngineerIds"))).containsExactly(bothHats);
        assertThat(ids(site.get("supervisorIds"))).containsExactly(bothHats);
        assertThat(siteIdsVisibleTo("both.hats")).containsExactly(siteId);

        // An edit that changes nothing about the staffing must not withdraw and re-grant.
        updateSite(admin, siteId, "One Man Block Renamed", List.of(bothHats), List.of(bothHats),
                site.get("version").asLong());
        assertThat(siteIdsVisibleTo("both.hats")).containsExactly(siteId);
    }

    /** Every name is checked, not merely the first: the second one is where a slip hides. */
    @Test
    @DisplayName("a site refuses the second name as readily as the first when the role is wrong")
    void everyNameIsChecked() throws Exception {
        String admin = login("viplove", SEEDED_PASSWORD);
        String realEngineer = onboard(admin, "eng.real", "ENGINEER");
        String notAnEngineer = onboard(admin, "eng.impostor", "SUPERVISOR");

        mockMvc.perform(post("/api/v1/sites")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"%s","code":"MSS-E","name":"Refused Block",
                                 "siteEngineerIds":["%s","%s"],
                                 "standardShiftHours":8.00,"monthlyWageDays":26}"""
                                .formatted(PROJECT_ID, realEngineer, notAnEngineer)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("ENGINEER")));
    }

    /**
     * The refusal that keeps the register and the door from disagreeing has to survive the
     * change: with two supervisors on a site, either of them is still named on it.
     */
    @Test
    @DisplayName("the members screen still cannot unpost one of two supervisors")
    void postingGuardHoldsForTheSecondSupervisor() throws Exception {
        String admin = login("viplove", SEEDED_PASSWORD);
        String first = onboard(admin, "guard.first", "SUPERVISOR");
        String second = onboard(admin, "guard.second", "SUPERVISOR");
        createSite(admin, "MSS-F", "Guarded Block", List.of(), List.of(first, second));

        mockMvc.perform(put("/api/v1/users/" + second + "/sites")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"siteIds\":[]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("MSS-F")))
                .andExpect(jsonPath("$.detail").value(containsString("supervisor")));
    }

    /**
     * The engineer's pickers, which is the same question the supervisor's answered long ago.
     *
     * <p>Every site dropdown in the app — attendance, receive, issue, expenses, the DPR — is
     * drawn from {@code GET /sites}, so this one assertion is what narrows all of them. It
     * had no test because the only engineer in the sample data also holds ADMIN, and an
     * ADMIN sees the whole company: the narrowing was there and nothing proved it.</p>
     */
    @Test
    @DisplayName("an engineer's site list is narrowed to his postings, as a supervisor's is")
    void engineerSeesOnlyTheSitesHeIsPostedTo() throws Exception {
        String admin = login("viplove", SEEDED_PASSWORD);
        String engineerId = onboard(admin, "narrow.eng", "ENGINEER");
        JsonNode site = createSite(admin, "MSS-G", "Narrowed Block", List.of(engineerId),
                List.of());

        // His own site, and neither of the two he has nothing to do with.
        assertThat(siteIdsVisibleTo("narrow.eng")).containsExactly(site.get("id").asText());

        // And the list is not merely hiding them: reaching for one by id is a hard refusal.
        mockMvc.perform(get("/api/v1/sites/" + KSN_A)
                        .header("Authorization", "Bearer " + login("narrow.eng", SEEDED_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    /** The seeded register survives the migration that turned its two columns into rows. */
    @Test
    @DisplayName("the sites carried over from the columns still name who runs them")
    void migratedPostingsSurvive() throws Exception {
        String admin = login("viplove", SEEDED_PASSWORD);
        MvcResult result = mockMvc.perform(get("/api/v1/sites/" + KSN_A)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode site = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(ids(site.get("siteEngineerIds")))
                .containsExactly("20000000-0000-0000-0000-000000000002");
        assertThat(ids(site.get("supervisorIds")))
                .containsExactly("20000000-0000-0000-0000-000000000003");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode createSite(String adminToken, String code, String name,
                                List<String> engineerIds, List<String> supervisorIds)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/sites")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"%s","code":"%s","name":"%s",
                                 "siteEngineerIds":%s,"supervisorIds":%s,
                                 "standardShiftHours":8.00,"monthlyWageDays":26}"""
                                .formatted(PROJECT_ID, code, name,
                                        jsonArray(engineerIds), jsonArray(supervisorIds))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode updateSite(String adminToken, String siteId, String name,
                                List<String> engineerIds, List<String> supervisorIds,
                                long version) throws Exception {
        MvcResult result = mockMvc.perform(put("/api/v1/sites/" + siteId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","status":"ACTIVE","siteEngineerIds":%s,
                                 "supervisorIds":%s,"standardShiftHours":8.00,
                                 "monthlyWageDays":26,"version":%d}"""
                                .formatted(name, jsonArray(engineerIds),
                                        jsonArray(supervisorIds), version)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String onboard(String adminToken, String username, String... roleCodes)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","fullName":"%s","mobile":"9800000000",
                                 "temporaryPassword":"%s","roleCodes":%s}"""
                                .formatted(username, username, SEEDED_PASSWORD,
                                        jsonArray(List.of(roleCodes)))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    /** Signs in fresh: the sites claim is fixed at the moment a token is issued. */
    private List<String> siteIdsVisibleTo(String username) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/sites")
                        .header("Authorization", "Bearer " + login(username, SEEDED_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        List<String> found = new ArrayList<>();
        objectMapper.readTree(result.getResponse().getContentAsString())
                .forEach(site -> found.add(site.get("id").asText()));
        return found;
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private static List<String> ids(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static String jsonArray(List<String> values) {
        return values.stream().map(value -> '"' + value + '"')
                .reduce((a, b) -> a + "," + b)
                .map(joined -> "[" + joined + "]")
                .orElse("[]");
    }
}

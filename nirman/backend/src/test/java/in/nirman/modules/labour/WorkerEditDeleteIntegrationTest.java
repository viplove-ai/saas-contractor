package in.nirman.modules.labour;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Correcting the roll, and the line drawn through the middle of it.
 *
 * <p>Two acts that look alike and are not. A supervisor <b>edits</b>: he took the man on at
 * the gate, and a name or a number he typed wrongly is his own to fix. Only the engineer and
 * the office <b>delete</b>, and only a row that should never have existed — the same man
 * entered twice, a name typed into the wrong site. A man who actually worked is marked Left,
 * because his days carry wages that have already been reported.</p>
 *
 * <p>Vivek is a supervisor at KSN-A and nothing else, which is what makes his refusals worth
 * asserting. Viplove holds ADMIN. Uttam holds ENGINEER — and ADMIN as well, so his success
 * proves nothing about the engineer's own grant; that is checked against the catalogue.</p>
 */
class WorkerEditDeleteIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";
    /** Every worker this class creates is named "WD …", so the sweep can find them all. */
    private static final String TEST_NAME_PREFIX = "WD ";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /** The suite shares a database and these men would show up in other classes' counts. */
    @AfterEach
    void removeCreatedWorkers() {
        String mine = "full_name LIKE ?";
        Object[] args = {TEST_NAME_PREFIX + "%"};
        for (String table : new String[]{"attendance_records", "worker_site_allocations",
                "wage_rates"}) {
            jdbc.update("DELETE FROM " + table + " WHERE worker_id IN "
                    + "(SELECT id FROM workers WHERE " + mine + ")", args);
        }
        jdbc.update("DELETE FROM workers WHERE " + mine, args);
    }

    @Test
    @DisplayName("a supervisor corrects the man he took on, and the correction sticks")
    void supervisorEditsHisOwnWorker() throws Exception {
        String vivek = loginToken("vivek");
        String workerId = onboard(vivek, "WD Galat Naam", SITE_A);

        mockMvc.perform(put("/api/v1/workers/" + workerId)
                        .header("Authorization", "Bearer " + vivek)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"WD Sahi Naam","mobile":"+91-9800000222",
                                 "employmentType":"CASUAL","wageType":"DAILY",
                                 "bankAccountNo":"11223344","active":true,"version":0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("WD Sahi Naam"))
                .andExpect(jsonPath("$.employmentType").value("CASUAL"))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(get("/api/v1/workers/" + workerId)
                        .header("Authorization", "Bearer " + vivek))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("WD Sahi Naam"))
                .andExpect(jsonPath("$.bankAccountNo").value("11223344"));
    }

    /** Two people correcting the same man is a 409, not the second one winning silently. */
    @Test
    @DisplayName("an edit against a stale version is refused")
    void staleEditIsRefused() throws Exception {
        String vivek = loginToken("vivek");
        String workerId = onboard(vivek, "WD Do Baar", SITE_A);
        String body = """
                {"fullName":"WD Do Baar","mobile":"+91-9800000223","employmentType":"CONTRACT",
                 "wageType":"DAILY","active":true,"version":0}""";

        mockMvc.perform(put("/api/v1/workers/" + workerId)
                        .header("Authorization", "Bearer " + vivek)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/workers/" + workerId)
                        .header("Authorization", "Bearer " + vivek)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a supervisor cannot take a man off the books")
    void supervisorCannotDelete() throws Exception {
        String vivek = loginToken("vivek");
        String workerId = onboard(vivek, "WD Nahin Hatega", SITE_A);

        mockMvc.perform(delete("/api/v1/workers/" + workerId)
                        .header("Authorization", "Bearer " + vivek)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Duplicate\"}"))
                .andExpect(status().isForbidden());

        // And he is still there, which is the half of a refusal worth checking.
        mockMvc.perform(get("/api/v1/workers/" + workerId)
                        .header("Authorization", "Bearer " + vivek))
                .andExpect(status().isOk());
    }

    /**
     * Uttam holds ADMIN as well as ENGINEER, so no request he makes can prove the engineer's
     * own grant. The grant is a row, so the row is what gets asserted.
     */
    @Test
    @DisplayName("the engineer holds worker:delete and the supervisor does not")
    void engineerHoldsTheGrant() throws Exception {
        assertThat(rolesHolding("worker:delete")).containsExactlyInAnyOrder("ADMIN", "ENGINEER");
        // The other half of the pair: editing stays with the field, as V6 arranged.
        assertThat(rolesHolding("worker:write"))
                .containsExactlyInAnyOrder("ADMIN", "ENGINEER", "SUPERVISOR");
    }

    @Test
    @DisplayName("a man nobody ever marked comes off the roll for good, with a reason on the row")
    void deleteRemovesAnUnusedWorker() throws Exception {
        String viplove = loginToken("viplove");
        String vivek = loginToken("vivek");
        String workerId = onboard(vivek, "WD Do Baar Likha", SITE_A);
        assertThat(rosterNames(vivek)).contains("WD Do Baar Likha");

        mockMvc.perform(delete("/api/v1/workers/" + workerId)
                        .header("Authorization", "Bearer " + viplove)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Entered twice at the gate on 3 March\"}"))
                .andExpect(status().isOk())
                // Off the roll as well as off the register: he is nobody's man now.
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/v1/workers/" + workerId)
                        .header("Authorization", "Bearer " + viplove))
                .andExpect(status().isNotFound());
        assertThat(rosterNames(vivek)).doesNotContain("WD Do Baar Likha");

        // The row stays, and says why — audit_logs cannot be joined to the register the
        // question will be asked from.
        assertThat(jdbc.queryForObject(
                "SELECT deleted_reason FROM workers WHERE id = ?", String.class,
                UUID.fromString(workerId)))
                .isEqualTo("Entered twice at the gate on 3 March");
    }

    /** A deletion nobody can explain six months later is indistinguishable from data loss. */
    @Test
    @DisplayName("a deletion without a reason is refused")
    void reasonIsRequired() throws Exception {
        String vivek = loginToken("vivek");
        String workerId = onboard(vivek, "WD Bina Wajah", SITE_A);

        mockMvc.perform(delete("/api/v1/workers/" + workerId)
                        .header("Authorization", "Bearer " + loginToken("viplove"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * The line between the two acts. A day marked is a wage frozen into what the site cost,
     * and hiding the man would change a figure that has already been reported — so he is
     * refused, and told what to do instead.
     */
    @Test
    @DisplayName("a man who has been marked cannot be deleted, and the refusal says what holds him")
    void aWorkerWithHistoryIsRefused() throws Exception {
        String vivek = loginToken("vivek");
        String workerId = onboard(vivek, "WD Kaam Kiya", SITE_A);
        mockMvc.perform(post("/api/v1/attendance/bulk")
                        .header("Authorization", "Bearer " + vivek)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"siteId":"%s","date":"%s","entries":[
                                    {"id":"%s","workerId":"%s","status":"PRESENT",
                                     "breakMinutes":0,"enteredHours":7}]}"""
                                .formatted(SITE_A, LocalDate.now(), UUID.randomUUID(), workerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1));

        mockMvc.perform(delete("/api/v1/workers/" + workerId)
                        .header("Authorization", "Bearer " + loginToken("viplove"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Duplicate\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("1 attendance record")))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("mark him Left instead")));

        // Still on the register, and still markable tomorrow.
        mockMvc.perform(get("/api/v1/workers/" + workerId)
                        .header("Authorization", "Bearer " + vivek))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    // ------------------------------------------------------------------ helpers

    private String onboard(String token, String name, String siteId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/workers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"%s","mobile":"+91-9800000221","siteId":"%s",
                                 "joiningDate":"%s"}"""
                                .formatted(name, siteId, LocalDate.now().minusDays(7))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private java.util.List<String> rolesHolding(String permission) {
        return jdbc.queryForList("""
                SELECT r.code FROM roles r
                JOIN role_permissions rp ON rp.role_id = r.id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE p.code = ? AND r.is_system""", String.class, permission);
    }

    /** Who the supervisor would see on the roll at KSN-A today. */
    private java.util.List<String> rosterNames(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/attendance/roster")
                        .param("siteId", SITE_A)
                        .param("date", LocalDate.now().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        java.util.List<String> names = new java.util.ArrayList<>();
        objectMapper.readTree(result.getResponse().getContentAsString()).get("entries")
                .forEach(entry -> names.add(entry.get("workerName").asText()));
        return names;
    }

    private String loginToken(String username) throws Exception {
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

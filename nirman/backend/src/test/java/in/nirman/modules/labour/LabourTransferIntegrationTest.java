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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The handover: a supervisor takes on a man at the gate, later lends him to another site,
 * and from that day the other site's supervisor is the one who marks him.
 *
 * <p>Vivek supervises KSN-A and Viplove KSN-B. Viplove also holds ADMIN, so his end of the
 * handover is not proof of a supervisor's rights — the assertions that matter are Vivek's:
 * what he may do to a man who is his, and what happens to him once he is not.</p>
 */
class LabourTransferIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";
    private static final String SITE_B = "31000000-0000-0000-0000-000000000002";
    private static final String TEST_CODE_PREFIX = "TW-";
    /**
     * A worker the server numbered carries no code this class chose, so the sweep below has
     * to be able to find him by name instead. Every worker created here is named "TW …".
     */
    private static final String TEST_NAME_PREFIX = "TW ";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /** The suite shares a database and these workers would show up in other classes' counts. */
    @AfterEach
    void removeCreatedWorkers() {
        String mine = "worker_code LIKE ? OR full_name LIKE ?";
        Object[] args = {TEST_CODE_PREFIX + "%", TEST_NAME_PREFIX + "%"};
        jdbc.update("""
                DELETE FROM worker_site_allocations WHERE worker_id IN
                    (SELECT id FROM workers WHERE %s)""".formatted(mine), args);
        jdbc.update("""
                DELETE FROM attendance_records WHERE worker_id IN
                    (SELECT id FROM workers WHERE %s)""".formatted(mine), args);
        jdbc.update("""
                DELETE FROM wage_rates WHERE worker_id IN
                    (SELECT id FROM workers WHERE %s)""".formatted(mine), args);
        jdbc.update("DELETE FROM workers WHERE " + mine, args);
    }

    @Test
    @DisplayName("a supervisor onboards a man at his own site and sees him on his roster")
    void supervisorOnboardsLabour() throws Exception {
        String vivek = loginToken("vivek");
        String workerId = onboard(vivek, "TW-001", "Naya Mazdoor", SITE_A);

        mockMvc.perform(get("/api/v1/workers/" + workerId)
                        .header("Authorization", "Bearer " + vivek))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentSiteId").value(SITE_A));

        assertThat(rosterNames(vivek, SITE_A)).contains("Naya Mazdoor");
    }

    @Test
    @DisplayName("a supervisor cannot onboard someone straight onto a site that is not his")
    void supervisorCannotOnboardOntoAnotherSite() throws Exception {
        String vivek = loginToken("vivek");
        mockMvc.perform(post("/api/v1/workers")
                        .header("Authorization", "Bearer " + vivek)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerCode":"TW-002","fullName":"Wrong Site","mobile":"+91-98000",
                                 "employmentType":"CONTRACT","wageType":"DAILY","siteId":"%s"}"""
                                .formatted(SITE_B)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("setting pay stays with the office, even at onboarding")
    void supervisorCannotSetTheRate() throws Exception {
        String vivek = loginToken("vivek");
        mockMvc.perform(post("/api/v1/workers")
                        .header("Authorization", "Bearer " + vivek)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerCode":"TW-003","fullName":"Rich Mazdoor","mobile":"+91-98000",
                                 "employmentType":"CONTRACT","wageType":"DAILY","siteId":"%s",
                                 "normalRate":5000,"overtimeRate":700}"""
                                .formatted(SITE_A)))
                .andExpect(status().isForbidden());

        // The admin sets it afterwards, on the man the supervisor added.
        String workerId = onboard(vivek, "TW-004", "Paid Later", SITE_A);
        mockMvc.perform(post("/api/v1/workers/" + workerId + "/wage-rates")
                        .header("Authorization", "Bearer " + loginToken("viplove"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"normalRate":650,"overtimeRate":92.85,"effectiveFrom":"%s"}"""
                                .formatted(LocalDate.now())))
                .andExpect(status().isCreated());
    }

    /**
     * The gate asks for a name, a mobile and a site. Everything else the roll needs has an
     * answer that does not have to be typed while a man waits — and a number typed at a gate
     * is the one that goes wrong, because the other gate has already used it.
     */
    @Test
    @DisplayName("a worker given no number is assigned the next one in the org's series")
    void serverNumbersTheWorker() throws Exception {
        String vivek = loginToken("vivek");
        MvcResult result = mockMvc.perform(post("/api/v1/workers")
                        .header("Authorization", "Bearer " + vivek)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"TW Bina Number","mobile":"+91-9800000111",
                                 "siteId":"%s"}""".formatted(SITE_A)))
                .andExpect(status().isCreated())
                // Contract labour on a daily wage, joined today: what nearly every man is.
                .andExpect(jsonPath("$.employmentType").value("CONTRACT"))
                .andExpect(jsonPath("$.wageType").value("DAILY"))
                .andExpect(jsonPath("$.joiningDate").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.currentSiteId").value(SITE_A))
                .andReturn();

        String first = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("workerCode").asText();
        assertThat(first).matches("W-\\d{4}-\\d{4}");

        // And the next man is the next number, not the same one again.
        MvcResult next = mockMvc.perform(post("/api/v1/workers")
                        .header("Authorization", "Bearer " + vivek)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"TW Agla Number","mobile":"+91-9800000112",
                                 "siteId":"%s"}""".formatted(SITE_A)))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(objectMapper.readTree(next.getResponse().getContentAsString())
                .get("workerCode").asText()).isNotEqualTo(first);
    }

    /** A man nobody can telephone is a man nobody can call back to the site. */
    @Test
    @DisplayName("a worker without a mobile number is refused")
    void mobileIsRequired() throws Exception {
        mockMvc.perform(post("/api/v1/workers")
                        .header("Authorization", "Bearer " + loginToken("vivek"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"TW Bina Mobile","siteId":"%s"}""".formatted(SITE_A)))
                .andExpect(status().isBadRequest());
    }

    /**
     * Nobody types where overtime begins or what it pays. KSN-A runs a seven-hour day, so a
     * wage of 700 is 100 an hour, and the eighth hour is worth exactly that — the field data
     * said overtime carries no premium (assumption 7), and the site already carries the
     * shift length the office would otherwise be dividing by in its head.
     */
    @Test
    @DisplayName("the day's wage and the site's shift price the overtime hour between them")
    void overtimeRateComesFromTheSiteShift() throws Exception {
        String viplove = loginToken("viplove");
        MvcResult created = mockMvc.perform(post("/api/v1/workers")
                        .header("Authorization", "Bearer " + viplove)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"TW Overtime Wala","mobile":"+91-9800000113",
                                 "siteId":"%s","normalRate":700}""".formatted(SITE_A)))
                .andExpect(status().isCreated())
                .andReturn();
        String workerId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asText();

        MvcResult rates = mockMvc.perform(get("/api/v1/workers/" + workerId + "/wage-rates")
                        .header("Authorization", "Bearer " + viplove))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode open = objectMapper.readTree(rates.getResponse().getContentAsString()).get(0);
        assertThat(open.get("normalRate").decimalValue()).isEqualByComparingTo("700");
        assertThat(open.get("overtimeRate").decimalValue()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("a transfer hands the man over: he leaves one roster and joins the other")
    void transferMovesTheManBetweenSupervisors() throws Exception {
        String vivek = loginToken("vivek");
        String viplove = loginToken("viplove");
        String workerId = onboard(vivek, "TW-005", "Lent Mazdoor", SITE_A);
        LocalDate today = LocalDate.now();

        assertThat(rosterNames(vivek, SITE_A)).contains("Lent Mazdoor");
        assertThat(rosterNames(viplove, SITE_B)).doesNotContain("Lent Mazdoor");

        // Vivek sends him to KSN-B — a site he himself cannot open.
        mockMvc.perform(get("/api/v1/sites/" + SITE_B).header("Authorization", "Bearer " + vivek))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/workers/" + workerId + "/allocations")
                        .header("Authorization", "Bearer " + vivek)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"siteId":"%s","effectiveFrom":"%s"}"""
                                .formatted(SITE_B, today)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.siteId").value(SITE_B));

        // From today he is the other site's man, and marking him there is what proves it.
        assertThat(rosterNames(viplove, SITE_B)).contains("Lent Mazdoor");
        assertThat(rosterNames(vivek, SITE_A)).doesNotContain("Lent Mazdoor");

        mockMvc.perform(post("/api/v1/attendance/bulk")
                        .header("Authorization", "Bearer " + viplove)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"siteId":"%s","date":"%s","entries":[
                                    {"id":"%s","workerId":"%s","status":"PRESENT",
                                     "breakMinutes":0,"enteredHours":7}]}"""
                                .formatted(SITE_B, today, java.util.UUID.randomUUID(), workerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1));
    }

    @Test
    @DisplayName("a man who is not yours cannot be moved by you")
    void onlyTheHoldingSupervisorCanTransfer() throws Exception {
        String vivek = loginToken("vivek");
        String viplove = loginToken("viplove");
        // Onboarded at KSN-B by the admin, so he is Viplove's man and never Vivek's.
        String workerId = onboard(viplove, "TW-006", "Not Yours", SITE_B);

        mockMvc.perform(post("/api/v1/workers/" + workerId + "/allocations")
                        .header("Authorization", "Bearer " + vivek)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"siteId":"%s","effectiveFrom":"%s"}"""
                                .formatted(SITE_A, LocalDate.now())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the destination has to be a real site, named from the company directory")
    void transferDestinationMustExist() throws Exception {
        String vivek = loginToken("vivek");
        String workerId = onboard(vivek, "TW-007", "Nowhere Bound", SITE_A);

        mockMvc.perform(post("/api/v1/workers/" + workerId + "/allocations")
                        .header("Authorization", "Bearer " + vivek)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"siteId":"31000000-0000-0000-0000-0000000000ff","effectiveFrom":"%s"}"""
                                .formatted(LocalDate.now())))
                .andExpect(status().isNotFound());

        // The directory is how he finds KSN-B: every site, though he works at one of them.
        MvcResult result = mockMvc.perform(get("/api/v1/sites/directory")
                        .header("Authorization", "Bearer " + vivek))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode directory = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(directory).hasSize(2);
        // And it carries a destination only — nothing about how the other site is run.
        assertThat(directory.get(0).has("supervisorIds")).isFalse();
        assertThat(directory.get(0).has("standardShiftHours")).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Joined a week ago, because a posting has to start before it can be moved on from:
     * closing today's posting yesterday would end it before it began, and the service says
     * so. Onboarding and transferring the same man on the same morning is a correction, not
     * a transfer.
     */
    private String onboard(String token, String code, String name, String siteId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/workers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerCode":"%s","fullName":"%s","mobile":"+91-9800000999",
                                 "employmentType":"CONTRACT","wageType":"DAILY","siteId":"%s",
                                 "joiningDate":"%s"}"""
                                .formatted(code, name, siteId, LocalDate.now().minusDays(7))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    /** Who a supervisor would see on the roll at that site today. */
    private java.util.List<String> rosterNames(String token, String siteId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/attendance/roster")
                        .param("siteId", siteId)
                        .param("date", LocalDate.now().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode entries = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("entries");
        java.util.List<String> names = new java.util.ArrayList<>();
        entries.forEach(entry -> names.add(entry.get("workerName").asText()));
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

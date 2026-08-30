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

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The people on the payroll, as an employer has to hold them.
 *
 * <p>A user row is a login: a name, a mobile and a password hash. It carries no address to
 * send a letter to, no next of kin to telephone, no bank account to pay into and no record
 * of what was agreed about pay — all of which lived in a diary.</p>
 *
 * <p>Two facts, kept apart: the terms that were agreed, edited in place, and what is
 * actually paid from when, which is appended and never edited.</p>
 */
class StaffRecordIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /** The suite shares a database, and the dashboard counts everybody with a record. */
    @AfterEach
    void removeTestRecords() {
        String staff = "SELECT id FROM users WHERE username LIKE 'staff.%'";
        jdbc.update("DELETE FROM staff_salary_revisions WHERE user_id IN (" + staff + ")");
        jdbc.update("DELETE FROM staff_profiles WHERE user_id IN (" + staff + ")");
    }

    @Test
    @DisplayName("a member with no record yet reads as blanks, not as a 404")
    void aMemberWithNoRecordIsBlankRatherThanMissing() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "staff.blank");

        MvcResult result = mockMvc.perform(get("/api/v1/staff/" + userId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode profile = objectMapper.readTree(result.getResponse().getContentAsString());

        // The login's own fields are there; the employer's are not, and the absent version
        // is what tells the screen this is the first save rather than an edit.
        assertThat(profile.get("username").asText()).isEqualTo("staff.blank");
        assertThat(profile.has("version")).isFalse();
        assertThat(profile.has("currentAddress")).isFalse();
    }

    @Test
    @DisplayName("the whole record saves, and probation's end date is derived not stored")
    void savesTheRecordAndDerivesTheProbationEnd() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "staff.probation");

        JsonNode saved = save(admin, userId, """
                {"employmentType":"PROBATION","joinedOn":"2026-08-01","probationDays":90,
                 "probationMonthlySalary":18000,"confirmedMonthlySalary":24000,
                 "alternateMobile":"9876500011","aadhaarLast4":"4417","pan":"ABCPD1234K",
                 "currentAddress":"Room 4, Site colony, Kausani",
                 "permanentAddress":"Village Someshwar, Almora",
                 "emergencyContactName":"Kamla Devi","emergencyContactMobile":"9876500022",
                 "emergencyContactRelation":"Mother","bankAccountName":"Deepak Joshi",
                 "bankAccountNo":"50100012345678","bankIfsc":"HDFC0001234","bankName":"HDFC"}""");

        assertThat(saved.get("employmentType").asText()).isEqualTo("PROBATION");
        // 1 August plus ninety days, computed on read: a stored end date is the copy that
        // stops matching the day somebody corrects the joining date.
        assertThat(saved.get("probationEndsOn").asText()).isEqualTo("2026-10-30");
        assertThat(saved.get("aadhaarLast4").asText()).isEqualTo("4417");
        assertThat(saved.get("permanentAddress").asText()).isEqualTo("Village Someshwar, Almora");
        assertThat(saved.get("version").asLong()).isZero();
    }

    /**
     * The one refusal the field validation cannot make: a whole Aadhaar is a valid-looking
     * string of digits, and truncating it silently would store part of what somebody typed.
     */
    @Test
    @DisplayName("a full Aadhaar number is refused rather than truncated")
    void aFullAadhaarIsRefused() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "staff.aadhaar");

        mockMvc.perform(put("/api/v1/staff/" + userId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"employmentType":"PERMANENT","aadhaarLast4":"123456789012"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a probation with no length is refused: an indefinite probation is not one")
    void probationNeedsALength() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "staff.indefinite");

        mockMvc.perform(put("/api/v1/staff/" + userId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"employmentType":"PROBATION","joinedOn":"2026-08-01"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("indefinite probation")));
    }

    @Test
    @DisplayName("somebody cannot be confirmed before they joined")
    void confirmedBeforeJoiningIsRefused() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "staff.backwards");

        mockMvc.perform(put("/api/v1/staff/" + userId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"employmentType":"PERMANENT","joinedOn":"2026-08-01",
                                 "confirmedOn":"2026-07-01"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("confirmed before they joined")));
    }

    /**
     * The point of keeping pay as revisions rather than as a column. A raise in April must
     * not restate what March cost, so the old figure stays and the new one starts.
     */
    @Test
    @DisplayName("a raise is appended, and the old figure survives it")
    void aRaiseIsAppended() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "staff.raise");

        recordSalary(admin, userId, "18000", "2026-01-01", "Joined on probation terms");
        recordSalary(admin, userId, "24000", "2026-04-01", "Confirmed off probation");

        MvcResult result = mockMvc.perform(get("/api/v1/staff/" + userId + "/salary")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode history = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(history).hasSize(2);
        // Newest first: the first row is what applies, and the rest is how it got there.
        assertThat(new BigDecimal(history.get(0).get("monthlyAmount").asText()))
                .isEqualByComparingTo("24000");
        assertThat(history.get(1).get("reason").asText())
                .isEqualTo("Joined on probation terms");
    }

    @Test
    @DisplayName("two salaries effective the same day are refused, with a reason")
    void twoSalariesTheSameDayAreRefused() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "staff.sameday");
        recordSalary(admin, userId, "18000", "2026-02-01", "Starting salary");

        mockMvc.perform(post("/api/v1/staff/" + userId + "/salary")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"basic":19000,"effectiveFrom":"2026-02-01",
                                 "reason":"Corrected"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("pick another date")));
    }

    /** A future-dated raise is not paid early: what applies today is today's figure. */
    @Test
    @DisplayName("a raise dated ahead does not become the current salary yet")
    void aFutureRaiseIsNotCurrentYet() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "staff.future");
        recordSalary(admin, userId, "20000", LocalDate.now().minusMonths(1).toString(),
                "Current salary");
        recordSalary(admin, userId, "30000", LocalDate.now().plusMonths(2).toString(),
                "Agreed raise, from the new year");

        MvcResult result = mockMvc.perform(get("/api/v1/staff/" + userId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode profile = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(new BigDecimal(profile.get("currentSalary").asText()))
                .isEqualByComparingTo("20000");
    }

    /**
     * A probation that quietly ran past its end is somebody working on probation terms months
     * after they were meant to stop — the alert the dashboard exists for.
     */
    @Test
    @DisplayName("the dashboard names a probation nobody closed")
    void dashboardNamesAnOverdueProbation() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "staff.overdue");
        save(admin, userId, """
                {"employmentType":"PROBATION","joinedOn":"%s","probationDays":30,
                 "probationMonthlySalary":15000}"""
                .formatted(LocalDate.now().minusDays(200)));

        MvcResult result = mockMvc.perform(get("/api/v1/staff/dashboard")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode dashboard = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(dashboard.get("probationOverdue").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(dashboard.toString()).contains("Probation ended and nobody has confirmed them");
        // And the one who cannot be paid is named too — the payroll total depends on it.
        assertThat(dashboard.toString()).contains("cannot be paid");
    }

    @Test
    @DisplayName("a supervisor cannot read anybody's address or bank account")
    void supervisorCannotReadStaffRecords() throws Exception {
        String userId = onboard(login("viplove"), "staff.private");

        mockMvc.perform(get("/api/v1/staff/" + userId)
                        .header("Authorization", "Bearer " + login("vivek")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/staff/dashboard")
                        .header("Authorization", "Bearer " + login("vivek")))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode save(String token, String userId, String body) throws Exception {
        MvcResult result = mockMvc.perform(put("/api/v1/staff/" + userId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void recordSalary(String token, String userId, String amount, String from,
                              String reason) throws Exception {
        mockMvc.perform(post("/api/v1/staff/" + userId + "/salary")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"basic":%s,"effectiveFrom":"%s","reason":"%s"}"""
                                .formatted(amount, from, reason)))
                .andExpect(status().isCreated());
    }

    private String onboard(String adminToken, String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","fullName":"%s","mobile":"9800000000",
                                 "temporaryPassword":"%s","roleCodes":["SUPERVISOR"]}"""
                                .formatted(username, username, PASSWORD)))
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

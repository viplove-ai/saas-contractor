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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The muster reaching back for a man taken on late: he is offered on the days before his
 * posting, back to the site's start, and refused on a day whose report the office has
 * approved. See the class comment on {@code AttendanceService}.
 */
class BackdatedAttendanceIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String ORG = "10000000-0000-0000-0000-000000000001";
    private static final String PROJECT = "30000000-0000-0000-0000-000000000001";
    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";
    private static final String SITE_B = "31000000-0000-0000-0000-000000000002";
    private static final String VIPLOVE = "20000000-0000-0000-0000-000000000001";
    /** The seed starts KSN-A on this day; nothing can be marked there before it. */
    private static final LocalDate SITE_A_START = LocalDate.of(2024, 12, 1);
    private static final String NAME_PREFIX = "BD ";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<LocalDate> reportDays = new ArrayList<>();

    @AfterEach
    void removeCreatedRows() {
        for (LocalDate day : reportDays) {
            jdbc.update("DELETE FROM daily_progress_reports WHERE site_id = ?::uuid AND report_date = ?",
                    SITE_A, day);
        }
        String mine = "full_name LIKE ?";
        Object[] args = {NAME_PREFIX + "%"};
        jdbc.update("""
                DELETE FROM worker_ledger_entries WHERE worker_id IN
                    (SELECT id FROM workers WHERE %s)""".formatted(mine), args);
        jdbc.update("""
                DELETE FROM worker_balances WHERE worker_id IN
                    (SELECT id FROM workers WHERE %s)""".formatted(mine), args);
        jdbc.update("""
                DELETE FROM attendance_records WHERE worker_id IN
                    (SELECT id FROM workers WHERE %s)""".formatted(mine), args);
        jdbc.update("""
                DELETE FROM worker_site_allocations WHERE worker_id IN
                    (SELECT id FROM workers WHERE %s)""".formatted(mine), args);
        jdbc.update("""
                DELETE FROM wage_rates WHERE worker_id IN
                    (SELECT id FROM workers WHERE %s)""".formatted(mine), args);
        jdbc.update("DELETE FROM workers WHERE " + mine, args);
    }

    @Test
    @DisplayName("a man taken on today is offered on last week's muster, labelled with his posting day")
    void lateOnboardingReachesBack() throws Exception {
        String vivek = loginToken("vivek");
        LocalDate today = LocalDate.now();
        LocalDate lastWeek = today.minusDays(7);
        String workerId = onboard(vivek, "BD Naya Mazdoor", SITE_A, today, null);

        JsonNode todayRoll = roster(vivek, SITE_A, today);
        JsonNode todayEntry = entryFor(todayRoll, workerId);
        assertThat(todayEntry).isNotNull();
        assertThat(todayEntry.has("postedFrom")).isFalse();

        JsonNode oldRoll = roster(vivek, SITE_A, lastWeek);
        assertThat(oldRoll.get("reportApproved").asBoolean()).isFalse();
        assertThat(oldRoll.get("markableFrom").asText()).isEqualTo(SITE_A_START.toString());
        JsonNode oldEntry = entryFor(oldRoll, workerId);
        assertThat(oldEntry).isNotNull();
        assertThat(oldEntry.get("postedFrom").asText()).isEqualTo(today.toString());

        mark(vivek, SITE_A, lastWeek, workerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(0));
    }

    @Test
    @DisplayName("the muster does not reach before the site's start, nor forward of today")
    void reachIsBounded() throws Exception {
        String vivek = loginToken("vivek");
        LocalDate today = LocalDate.now();
        String workerId = onboard(vivek, "BD Purana Mazdoor", SITE_A, today, null);

        LocalDate beforeStart = SITE_A_START.minusDays(1);
        assertThat(entryFor(roster(vivek, SITE_A, beforeStart), workerId)).isNull();
        mark(vivek, SITE_A, beforeStart, workerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.outcomes[0].reason").value(
                        org.hamcrest.Matchers.containsString("started on " + SITE_A_START)));

        // Posted from next week: tomorrow's roll is not his yet.
        String nextWeekMan = onboard(vivek, "BD Agla Mazdoor", SITE_A, today.plusDays(7), null);
        assertThat(entryFor(roster(vivek, SITE_A, today.plusDays(1)), nextWeekMan)).isNull();
        mark(vivek, SITE_A, today.plusDays(1), nextWeekMan)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected").value(1));
    }

    @Test
    @DisplayName("a day whose report the office has approved takes no late-posted man")
    void approvedReportClosesTheDay() throws Exception {
        String vivek = loginToken("vivek");
        LocalDate today = LocalDate.now();
        LocalDate approvedDay = today.minusDays(10);
        approveReportOn(approvedDay);
        String workerId = onboard(vivek, "BD Der Se Mazdoor", SITE_A, today, null);

        JsonNode roll = roster(vivek, SITE_A, approvedDay);
        assertThat(roll.get("reportApproved").asBoolean()).isTrue();
        assertThat(entryFor(roll, workerId)).isNull();

        mark(vivek, SITE_A, approvedDay, workerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.outcomes[0].reason").value(
                        org.hamcrest.Matchers.containsString("approved by the office")));

        // The day beside it is still open.
        mark(vivek, SITE_A, approvedDay.minusDays(1), workerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1));
    }

    @Test
    @DisplayName("a man never posted to the site is refused, and a man marked elsewhere that day is refused")
    void oneManOneRollOneMorning() throws Exception {
        String vivek = loginToken("vivek");
        String viplove = loginToken("viplove");
        LocalDate today = LocalDate.now();
        LocalDate lastWeek = today.minusDays(7);

        // Posted only to KSN-B: KSN-A's muster has no claim on him, on any day.
        String strangerId = onboard(viplove, "BD Anjaan Mazdoor", SITE_B, lastWeek, null);
        assertThat(entryFor(roster(vivek, SITE_A, lastWeek), strangerId)).isNull();
        mark(vivek, SITE_A, lastWeek, strangerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.outcomes[0].reason").value(
                        org.hamcrest.Matchers.containsString("was not posted to this site")));

        // At KSN-B last week, marked there, transferred to KSN-A today: KSN-A offers him for
        // last week, but not on the morning KSN-B already has him.
        String movedId = onboard(viplove, "BD Badla Mazdoor", SITE_B, lastWeek.minusDays(7), null);
        mark(viplove, SITE_B, lastWeek, movedId).andExpect(jsonPath("$.accepted").value(1));
        mockMvc.perform(post("/api/v1/workers/" + movedId + "/allocations")
                        .header("Authorization", "Bearer " + viplove)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"siteId":"%s","effectiveFrom":"%s"}""".formatted(SITE_A, today)))
                .andExpect(status().isCreated());

        JsonNode entry = entryFor(roster(vivek, SITE_A, lastWeek), movedId);
        assertThat(entry).isNotNull();
        assertThat(entry.get("postedFrom").asText()).isEqualTo(today.toString());
        mark(vivek, SITE_A, lastWeek, movedId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.outcomes[0].reason").value(
                        org.hamcrest.Matchers.containsString("already marked at another site")));
        // The day before, nobody had him: KSN-A may.
        mark(vivek, SITE_A, lastWeek.minusDays(1), movedId)
                .andExpect(jsonPath("$.accepted").value(1));
    }

    @Test
    @DisplayName("a day before his first rate is priced at that first rate, and frozen there")
    void firstRateReachesBack() throws Exception {
        String viplove = loginToken("viplove");
        String uttam = loginToken("uttam");
        LocalDate today = LocalDate.now();
        LocalDate lastWeek = today.minusDays(7);
        String workerId = onboard(viplove, "BD Daam Mazdoor", SITE_A, today, "625");

        JsonNode entry = entryFor(roster(viplove, SITE_A, lastWeek), workerId);
        assertThat(entry.get("normalRate").decimalValue()).isEqualByComparingTo("625");

        UUID recordId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/attendance/bulk")
                        .header("Authorization", "Bearer " + viplove)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulkBody(SITE_A, lastWeek, recordId, workerId)))
                .andExpect(jsonPath("$.accepted").value(1));
        mockMvc.perform(post("/api/v1/attendance/submit")
                        .header("Authorization", "Bearer " + viplove)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"siteId":"%s","date":"%s"}""".formatted(SITE_A, lastWeek)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/attendance/verify")
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ids":["%s"],"action":"VERIFY"}""".formatted(recordId)))
                .andExpect(status().isOk());

        java.util.Map<String, Object> row = jdbc.queryForMap(
                "SELECT workflow_status, applied_normal_rate FROM attendance_records WHERE id = ?::uuid",
                recordId);
        assertThat(row.get("workflow_status")).isEqualTo("VERIFIED");
        assertThat((java.math.BigDecimal) row.get("applied_normal_rate")).isEqualByComparingTo("625");
    }

    // ------------------------------------------------------------------ helpers

    /** A report the office has countersigned, written straight in: the workflow is not what is under test. */
    private void approveReportOn(LocalDate day) {
        reportDays.add(day);
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("""
                INSERT INTO daily_progress_reports
                    (id, org_id, project_id, site_id, report_date, dpr_number, workflow_status,
                     submitted_at, verified_at, verified_by, approved_at, approved_by)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, 'APPROVED', ?, ?, ?::uuid, ?, ?::uuid)
                """, UUID.randomUUID(), ORG, PROJECT, SITE_A, day, "BD-" + day, now, now, VIPLOVE, now, VIPLOVE);
    }

    private String onboard(String token, String name, String siteId, LocalDate joining,
                           String rate) throws Exception {
        String rateField = rate == null ? "" : ",\"normalRate\":" + rate;
        MvcResult result = mockMvc.perform(post("/api/v1/workers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"%s","mobile":"+91-9800000998",
                                 "employmentType":"CONTRACT","wageType":"DAILY","siteId":"%s",
                                 "joiningDate":"%s"%s}"""
                                .formatted(name, siteId, joining, rateField)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private JsonNode roster(String token, String siteId, LocalDate date) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/attendance/roster")
                        .param("siteId", siteId)
                        .param("date", date.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode entryFor(JsonNode roster, String workerId) {
        for (JsonNode entry : roster.get("entries")) {
            if (entry.get("workerId").asText().equals(workerId)) {
                return entry;
            }
        }
        return null;
    }

    private org.springframework.test.web.servlet.ResultActions mark(String token, String siteId,
                                                                    LocalDate date, String workerId)
            throws Exception {
        return mockMvc.perform(post("/api/v1/attendance/bulk")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bulkBody(siteId, date, UUID.randomUUID(), workerId)));
    }

    private static String bulkBody(String siteId, LocalDate date, UUID recordId, String workerId) {
        return """
                {"siteId":"%s","date":"%s","entries":[
                    {"id":"%s","workerId":"%s","status":"PRESENT","breakMinutes":0,"enteredHours":7}]}"""
                .formatted(siteId, date, recordId, workerId);
    }

    private String loginToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}"
                                .formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
}

package in.nirman.modules.dpr;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deleting a daily report — which is a different act from the wizard's "start fresh".
 *
 * <p>Starting fresh empties a report that ought to exist. This removes one that ought not to:
 * the wrong site, a day opened out of habit. The tests below hold the line between the two.
 * A draft goes and takes its hold on the day with it, so the day can be written properly. A
 * report the engineer has been sent stays, because it is in front of somebody, and a verified
 * one stays because its measured lines are in the measurement book and history does not move.
 * What is deleted is not erased: the row keeps its number and carries the reason.</p>
 */
class DprDeletionIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";
    private static final String VERIFIED_DPR = "82000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("a deleted draft leaves the register and gives its day back")
    void deletingADraftFreesTheDay() throws Exception {
        String supervisor = loginToken("vivek");
        LocalDate day = freeDay();
        String id = draft(supervisor, day);

        mockMvc.perform(deleteReport(id, supervisor, "Opened on the wrong site"))
                .andExpect(status().isOk());

        // Gone from the register, and gone from the one place a supervisor would look for it.
        mockMvc.perform(get("/api/v1/dprs/" + id).header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/dprs/prefill")
                        .header("Authorization", "Bearer " + supervisor)
                        .param("siteId", SITE_A)
                        .param("date", day.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportExists").value(false));

        // And the day is writable again, which is the whole point of deleting rather than
        // emptying: the unique index counts live reports only.
        String second = draft(supervisor, day);
        assertThat(second).isNotEqualTo(id);

        // Not erased. The row, its number and the reason are still there to be answered for.
        assertThat(jdbc.queryForObject("""
                SELECT deleted_reason FROM daily_progress_reports WHERE id = ?::uuid
                """, String.class, id)).isEqualTo("Opened on the wrong site");
    }

    @Test
    @DisplayName("a report already sent for signature cannot be deleted from under the engineer")
    void aSubmittedReportStays() throws Exception {
        String supervisor = loginToken("vivek");
        String id = draft(supervisor, freeDay());
        handOver(supervisor, id);

        mockMvc.perform(deleteReport(id, supervisor, "Changed my mind"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://nirman/errors/dpr.not-deletable"))
                .andExpect(jsonPath("$.detail").value(containsString("send it back")));

        mockMvc.perform(get("/api/v1/dprs/" + id).header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isOk());
    }

    /** The one that would take a claim out of the measurement book with it. */
    @Test
    @DisplayName("a verified report cannot be deleted")
    void averifiedReportStays() throws Exception {
        mockMvc.perform(deleteReport(VERIFIED_DPR, loginToken("uttam"), "Tidying up"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://nirman/errors/dpr.not-deletable"));
    }

    /** A returned report is editable, so it is deletable — it is back in the writer's hands. */
    @Test
    @DisplayName("a report the engineer sent back can be deleted")
    void aReturnedReportGoes() throws Exception {
        String supervisor = loginToken("vivek");
        String engineer = loginToken("uttam");
        String id = draft(supervisor, freeDay());
        handOver(supervisor, id);
        mockMvc.perform(post("/api/v1/dprs/" + id + "/verify")
                        .header("Authorization", "Bearer " + engineer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REJECT\",\"remarks\":\"Wrong day entirely.\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(deleteReport(id, supervisor, "Wrong day — the real one is the 4th"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("deleting without a reason is refused")
    void reasonIsRequired() throws Exception {
        String supervisor = loginToken("vivek");
        String id = draft(supervisor, freeDay());

        mockMvc.perform(deleteReport(id, supervisor, "  "))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/dprs/" + id).header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isOk());
    }

    /**
     * The offline case. A phone that queued a report which was deleted at the office would
     * otherwise re-send it into its own primary key, and the supervisor would see a database
     * error where an explanation belongs.
     */
    @Test
    @DisplayName("re-sending a deleted report is answered rather than resurrected")
    void resendingADeletedReport() throws Exception {
        String supervisor = loginToken("vivek");
        LocalDate day = freeDay();
        String id = draft(supervisor, day);
        mockMvc.perform(deleteReport(id, supervisor, "Duplicate of the 3rd"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/dprs")
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","siteId":"%s","reportDate":"%s",
                                 "workSummary":"Brickwork on the north wall"}"""
                                .formatted(id, SITE_A, day)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://nirman/errors/dpr.deleted"))
                .andExpect(jsonPath("$.detail").value(containsString("Duplicate of the 3rd")));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The handover, photograph and all. A report cannot be handed over without one, so a test
     * about deletion has to do what the wizard does before it can get a submitted report.
     */
    private void handOver(String token, String id) throws Exception {
        String attachmentId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO attachments (id, org_id, owner_entity_type, file_name, content_type,
                                         size_bytes, bucket, object_key, kind)
                VALUES (?::uuid, '10000000-0000-0000-0000-000000000001', 'DPR',
                        'site.jpg', 'image/jpeg', 4096, 'nirman', ?, 'PHOTO')""",
                attachmentId, "test/dpr/" + attachmentId);
        mockMvc.perform(post("/api/v1/dprs/" + id + "/photos")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentId\":\"%s\"}".formatted(attachmentId)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/dprs/" + id + "/submit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder deleteReport(String id, String token, String reason) {
        return delete("/api/v1/dprs/" + id)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}");
    }

    /**
     * A past day at KSN-A with nothing on it. Counts every report ever written there, deleted
     * ones included, so a day this suite has already used and removed is not handed out twice.
     */
    private synchronized LocalDate freeDay() {
        Integer offset = jdbc.queryForObject("""
                SELECT count(*) FROM daily_progress_reports WHERE site_id = ?::uuid
                """, Integer.class, SITE_A);
        return LocalDate.of(2025, 3, 1).plusDays(offset == null ? 0 : offset);
    }

    private String draft(String token, LocalDate day) throws Exception {
        String id = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/dprs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        // The supervisor's half and nothing else: what was built is the
                        // engineer's to write, and this is a token holding dpr:draft alone.
                        .content("""
                                {"id":"%s","siteId":"%s","reportDate":"%s","weather":"CLEAR"}"""
                                .formatted(id, SITE_A, day)))
                .andExpect(status().isCreated());
        return id;
    }

    private String loginToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}

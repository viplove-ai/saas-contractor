package in.nirman.modules.labour;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Head counts for a site whose work is let to a labour contractor.
 *
 * <p>The fixture turns the flag on for Kausani Main Block and turns it off again afterwards,
 * because nothing here rolls back and every other test at that site expects a muster roll.
 * The counts themselves are cleaned up with it.</p>
 */
class SiteLabourCountIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";
    private static final LocalDate DAY = LocalDate.of(2025, 6, 12);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void enableOutsourcedLabour() {
        jdbc.update("UPDATE sites SET uses_outsourced_labour = true WHERE id = ?::uuid", SITE_A);
    }

    @AfterEach
    void restore() {
        jdbc.update("DELETE FROM site_labour_counts WHERE site_id = ?::uuid", SITE_A);
        jdbc.update("UPDATE sites SET uses_outsourced_labour = false WHERE id = ?::uuid", SITE_A);
    }

    @Test
    @DisplayName("a supervisor records the day as counts per trade, and reads them back")
    void supervisorRecordsCounts() throws Exception {
        String token = loginToken("vivek");

        mockMvc.perform(saveCounts(token, """
                        {"skillCategoryId":"%s","headCount":11},
                        {"skillCategoryId":"%s","headCount":6}
                        """.formatted(skill("MASON"), skill("HELPER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.totalHeadCount").value(17))
                .andExpect(jsonPath("$.lines.length()").value(2));

        mockMvc.perform(get("/api/v1/labour-counts")
                        .param("siteId", SITE_A).param("date", DAY.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHeadCount").value(17));
    }

    /**
     * The save is the whole day, so the phone that lost signal and sent it twice leaves one
     * day's counts. This is the property the screen depends on.
     */
    @Test
    @DisplayName("sending the same day twice leaves one day's counts, not two")
    void resendingIsNotASecondDay() throws Exception {
        String token = loginToken("vivek");
        String body = """
                {"skillCategoryId":"%s","headCount":11}
                """.formatted(skill("MASON"));

        mockMvc.perform(saveCounts(token, body)).andExpect(status().isOk());
        mockMvc.perform(saveCounts(token, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHeadCount").value(11))
                .andExpect(jsonPath("$.lines.length()").value(1));

        assertThat(rowCount()).isEqualTo(1);
    }

    /** A trade dropped from the list is a trade the supervisor is saying was not there. */
    @Test
    @DisplayName("a trade left out of the request is removed from the day")
    void omittedTradeIsRemoved() throws Exception {
        String token = loginToken("vivek");
        mockMvc.perform(saveCounts(token, """
                        {"skillCategoryId":"%s","headCount":11},
                        {"skillCategoryId":"%s","headCount":6}
                        """.formatted(skill("MASON"), skill("HELPER"))))
                .andExpect(status().isOk());

        mockMvc.perform(saveCounts(token, """
                        {"skillCategoryId":"%s","headCount":11}
                        """.formatted(skill("MASON"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.totalHeadCount").value(11));

        assertThat(rowCount()).isEqualTo(1);
    }

    /**
     * Zero is a fact — "the bar benders did not come" — and is kept, which is what makes it
     * different from leaving the trade off the list entirely.
     */
    @Test
    @DisplayName("a count of zero is stored rather than dropped")
    void zeroIsAFact() throws Exception {
        String token = loginToken("vivek");
        mockMvc.perform(saveCounts(token, """
                        {"skillCategoryId":"%s","headCount":0,"remarks":"none came today"}
                        """.formatted(skill("BAR_BENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.totalHeadCount").value(0));

        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a site that keeps its own muster roll refuses head counts")
    void refusedWhereTheSiteIsNotSetUpForIt() throws Exception {
        jdbc.update("UPDATE sites SET uses_outsourced_labour = false WHERE id = ?::uuid", SITE_A);
        mockMvc.perform(saveCounts(loginToken("vivek"), """
                        {"skillCategoryId":"%s","headCount":11}
                        """.formatted(skill("MASON"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://nirman/errors/labour.counts-not-enabled"));
    }

    @Test
    @DisplayName("the same trade twice in one request is refused by name, not by constraint")
    void duplicateTradeIsRefused() throws Exception {
        String mason = skill("MASON");
        mockMvc.perform(saveCounts(loginToken("vivek"), """
                        {"skillCategoryId":"%s","headCount":6},
                        {"skillCategoryId":"%s","headCount":5}
                        """.formatted(mason, mason)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://nirman/errors/labour.counts-duplicate-trade"));
    }

    /** Site scope fences the counts like every other write in the module. */
    @Test
    @DisplayName("counts cannot be entered against a site the caller is not posted to")
    void siteScopeApplies() throws Exception {
        mockMvc.perform(put("/api/v1/labour-counts")
                        .header("Authorization", "Bearer " + loginToken("vivek"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"siteId":"31000000-0000-0000-0000-000000000002","date":"%s",
                                 "lines":[{"skillCategoryId":"%s","headCount":4}]}
                                """.formatted(DAY, skill("MASON"))))
                .andExpect(status().isForbidden());
    }

    /**
     * The point of the whole feature: the counts reach the day's report, beside the muster
     * roll and never inside it. Our own men have hours and a wage; the contractor's have a
     * count and nothing else, and a report that added the two would report a wage bill
     * divided among people who are not on our payroll.
     */
    @Test
    @DisplayName("the counts reach the report's prefill without joining the muster's head count")
    void countsReachTheReport() throws Exception {
        String token = loginToken("vivek");
        mockMvc.perform(saveCounts(token, """
                        {"skillCategoryId":"%s","headCount":11}
                        """.formatted(skill("MASON"))))
                .andExpect(status().isOk());

        JsonNode prefill = objectMapper.readTree(mockMvc.perform(get("/api/v1/dprs/prefill")
                        .param("siteId", SITE_A).param("date", DAY.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(prefill.get("outsourcedLabour").get("enabled").asBoolean()).isTrue();
        assertThat(prefill.get("outsourcedLabour").get("headCount").asInt()).isEqualTo(11);
        assertThat(prefill.get("outsourcedLabour").get("lines").get(0).get("skillCategoryName")
                .asText()).isEqualTo("Mason");
        // The muster is untouched by any of it. Checked against the attendance rows rather
        // than against zero: another test in the suite may have marked somebody at this site
        // on this day, and the property under test is that the eleven did not join them.
        assertThat(prefill.get("labour").get("presentCount").asInt()).isEqualTo(markedPresent());
    }

    // ------------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder saveCounts(
            String token, String lines) {
        return put("/api/v1/labour-counts")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"siteId":"%s","date":"%s","lines":[%s]}
                        """.formatted(SITE_A, DAY, lines));
    }

    private String skill(String code) {
        return jdbc.queryForObject(
                "SELECT id::text FROM skill_categories WHERE code = ?", String.class, code);
    }

    /** Men on the muster for the day, which the contractor's eleven must never be added to. */
    private int markedPresent() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM attendance_records
                WHERE site_id = ?::uuid AND attendance_date = ?
                  AND status IN ('PRESENT','HALF_DAY')
                """, Integer.class, SITE_A, DAY);
        return count == null ? 0 : count;
    }

    private int rowCount() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM site_labour_counts WHERE site_id = ?::uuid AND count_date = ?",
                Integer.class, SITE_A, DAY);
        return count == null ? 0 : count;
    }

    private String loginToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\""
                                .formatted(username, PASSWORD) + "}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}

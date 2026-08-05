package in.nirman.modules.dpr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The DPR's life: draft, submit, verify, print — and what each transition is allowed to change.
 *
 * <p>Two rules carry the weight here, and they are the same rule in two places. <b>Verifying is
 * what claims work against the contract</b>, so a measured line reaches the measurement book at
 * verification and never before; and <b>a verified report is the document that was signed</b>, so
 * from then on its figures are its own. A report whose totals were recomputed on every read would
 * quietly rewrite what an engineer put his name to every time an attendance row was corrected
 * behind it — which is the failure that makes a site diary worthless as evidence.</p>
 */
class DprWorkflowIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";
    private static final String SITE_B = "31000000-0000-0000-0000-000000000002";
    private static final String BOQ_BRICKWORK = "70000000-0000-0000-0000-000000000003";

    /** The 9 June report V903 seeds already verified, with its brickwork claimed. */
    private static final String VERIFIED_DPR = "82000000-0000-0000-0000-000000000001";
    /** The 10 June draft. */
    private static final String DRAFT_DPR = "82000000-0000-0000-0000-000000000002";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------------------------------------------------------------- the whole path

    /**
     * Describe, sign, claim — end to end, with the measurement book checked before and after.
     */
    @Test
    @DisplayName("verifying a report posts its measured work to the measurement book")
    void verificationPostsMeasuredWork() throws Exception {
        String supervisor = loginToken("vivek");
        String engineer = loginToken("uttam");
        LocalDate day = freeDay();

        BigDecimal claimedBefore = completedQuantity(BOQ_BRICKWORK);
        String id = draft(supervisor, day, "12.5");

        // Nothing is claimed by drafting, and nothing by submitting either.
        assertThat(completedQuantity(BOQ_BRICKWORK)).isEqualByComparingTo(claimedBefore);
        submit(supervisor, id);
        assertThat(completedQuantity(BOQ_BRICKWORK))
                .as("submission asks for a signature; it does not claim work")
                .isEqualByComparingTo(claimedBefore);

        verify(engineer, id);

        assertThat(completedQuantity(BOQ_BRICKWORK))
                .as("verification is what claims the measured quantity")
                .isEqualByComparingTo(claimedBefore.add(new BigDecimal("12.5")));
        assertThat(entriesFor(id)).isEqualTo(1);
    }

    /**
     * The V9 guard, from the caller's side. A second verification cannot happen through the API
     * at all — the report is no longer submitted — and the unique index behind it means a race
     * that got past the check would still leave one claim rather than two.
     */
    @Test
    @DisplayName("a report cannot be verified twice, so its work cannot be claimed twice")
    void verifyingTwiceDoesNotClaimTwice() throws Exception {
        String supervisor = loginToken("vivek");
        String engineer = loginToken("uttam");
        LocalDate day = freeDay();

        String id = draft(supervisor, day, "8");
        submit(supervisor, id);
        verify(engineer, id);
        BigDecimal afterFirst = completedQuantity(BOQ_BRICKWORK);

        mockMvc.perform(post("/api/v1/dprs/" + id + "/verify")
                        .header("Authorization", "Bearer " + engineer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"VERIFY\"}"))
                .andExpect(status().isUnprocessableEntity());

        assertThat(completedQuantity(BOQ_BRICKWORK)).isEqualByComparingTo(afterFirst);
        assertThat(entriesFor(id)).isEqualTo(1);
    }

    /** The database keeps the same promise the service does, for the race the service cannot see. */
    @Test
    @DisplayName("the measurement book refuses a second entry for the same report and line")
    void theUniqueIndexBacksTheCheck() throws Exception {
        Integer boqEntryId = jdbc.queryForObject("""
                SELECT count(*) FROM boq_progress_entries
                WHERE dpr_id = ?::uuid AND boq_item_id = ?::uuid
                """, Integer.class, VERIFIED_DPR, BOQ_BRICKWORK);
        assertThat(boqEntryId).isEqualTo(1);

        assertThatDuplicateEntryIsRejected();
    }

    /** A row that describes work without measuring it claims nothing. */
    @Test
    @DisplayName("an unmeasured work item is reported but claims nothing against the contract")
    void anUnmeasuredLineClaimsNothing() throws Exception {
        String supervisor = loginToken("vivek");
        String engineer = loginToken("uttam");
        LocalDate day = freeDay();

        BigDecimal before = completedQuantity(BOQ_BRICKWORK);
        String id = draftUnmeasured(supervisor, day);
        submit(supervisor, id);
        verify(engineer, id);

        assertThat(completedQuantity(BOQ_BRICKWORK)).isEqualByComparingTo(before);
        assertThat(entriesFor(id)).isZero();
    }

    // ---------------------------------------------------------------- what freezes when

    /**
     * The figures a report was signed on stay put. This edits the muster behind an already
     * verified report and checks the document did not move — which is the whole difference
     * between a diary and a live query.
     */
    @Test
    @DisplayName("a correction behind a verified report does not rewrite what was signed")
    void averifiedReportKeepsItsFrozenFigures() throws Exception {
        String engineer = loginToken("uttam");
        BigDecimal signedCost = decimal(report(engineer, VERIFIED_DPR), "labourCost");

        // A wage correction on the day behind it. Deliberately crude — this is about whether the
        // document re-reads the records, not about how the correction was made.
        jdbc.update("""
                UPDATE attendance_records SET computed_wage_amount = computed_wage_amount + 100
                WHERE site_id = ?::uuid AND attendance_date = DATE '2025-06-09'
                """, SITE_A);

        assertThat(decimal(report(engineer, VERIFIED_DPR), "labourCost"))
                .as("the report says what it said when it was signed")
                .isEqualByComparingTo(signedCost);
        assertThat(report(engineer, VERIFIED_DPR).get("snapshotFrozen").asBoolean()).isTrue();
    }

    /** A draft has no signature to protect, so it tracks the records until it is submitted. */
    @Test
    @DisplayName("a draft's figures follow the records until the moment it is submitted")
    void aDraftTracksTheRecords() throws Exception {
        String supervisor = loginToken("vivek");
        assertThat(report(supervisor, DRAFT_DPR).get("snapshotFrozen").asBoolean()).isFalse();
    }

    /** Editing a verified report is refused: it is the document that was signed. */
    @Test
    @DisplayName("a verified report cannot be edited")
    void averifiedReportIsNotEditable() throws Exception {
        String engineer = loginToken("uttam");
        long version = report(engineer, VERIFIED_DPR).get("version").asLong();

        mockMvc.perform(put("/api/v1/dprs/" + VERIFIED_DPR)
                        .header("Authorization", "Bearer " + engineer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workSummary":"Rewriting history","version":%d}"""
                                .formatted(version)))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------------------------------------------------------------- refusals

    /** Returning a report needs a reason the preparer can act on. */
    @Test
    @DisplayName("a report sent back without a reason is refused")
    void rejectionNeedsAReason() throws Exception {
        String supervisor = loginToken("vivek");
        String engineer = loginToken("uttam");
        String id = draft(supervisor, freeDay(), "5");
        submit(supervisor, id);

        mockMvc.perform(post("/api/v1/dprs/" + id + "/verify")
                        .header("Authorization", "Bearer " + engineer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REJECT\"}"))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(post("/api/v1/dprs/" + id + "/verify")
                        .header("Authorization", "Bearer " + engineer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"REJECT","remarks":"The brickwork quantity does not \
                                match the measurement sheet."}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("REJECTED"));

        // Sent back editable, with the reason on the record for the man who has to answer it.
        assertThat(report(supervisor, id).get("rejectionReason").asText())
                .contains("measurement sheet");
    }

    /** One report per site per day, and the 409 names the one that already covers it. */
    @Test
    @DisplayName("a second report for a day already covered is refused by name")
    void oneReportPerSitePerDay() throws Exception {
        mockMvc.perform(post("/api/v1/dprs")
                        .header("Authorization", "Bearer " + loginToken("vivek"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","siteId":"%s","reportDate":"2025-06-10",
                                 "workSummary":"A second diary for the same day"}"""
                                .formatted(UUID.randomUUID(), SITE_A)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("DPR-2025-9002")));
    }

    /** A report that says nothing cannot be verified, so it cannot be sent for verification. */
    @Test
    @DisplayName("a report with neither a work line nor a summary cannot be submitted")
    void anEmptyReportCannotBeSubmitted() throws Exception {
        String supervisor = loginToken("vivek");
        String id = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/dprs")
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","siteId":"%s","reportDate":"%s"}"""
                                .formatted(id, SITE_A, freeDay())))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/dprs/" + id + "/submit")
                        .header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isUnprocessableEntity());
    }

    /** Tomorrow's diary cannot be written today. */
    @Test
    @DisplayName("a report cannot be opened for a day that has not happened")
    void noReportForTheFuture() throws Exception {
        mockMvc.perform(post("/api/v1/dprs")
                        .header("Authorization", "Bearer " + loginToken("vivek"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","siteId":"%s","reportDate":"%s",
                                 "workSummary":"Work I intend to do"}"""
                                .formatted(UUID.randomUUID(), SITE_A, LocalDate.now().plusDays(1))))
                .andExpect(status().isUnprocessableEntity());
    }

    /** Signing is the engineer's act. A supervisor holds dpr:draft and not dpr:verify. */
    @Test
    @DisplayName("a supervisor cannot verify his own report")
    void supervisorsDoNotSign() throws Exception {
        String supervisor = loginToken("vivek");
        String id = draft(supervisor, freeDay(), "6");
        submit(supervisor, id);

        mockMvc.perform(post("/api/v1/dprs/" + id + "/verify")
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"VERIFY\"}"))
                .andExpect(status().isForbidden());
    }

    /** A report typed with no signal and synced three times is one report. */
    @Test
    @DisplayName("re-sending the same draft creates one report rather than three")
    void resendingADraftIsIdempotent() throws Exception {
        String supervisor = loginToken("vivek");
        String id = UUID.randomUUID().toString();
        LocalDate day = freeDay();
        String body = """
                {"id":"%s","siteId":"%s","reportDate":"%s",
                 "workSummary":"Curing the ground floor slab"}""".formatted(id, SITE_A, day);

        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(post("/api/v1/dprs")
                            .header("Authorization", "Bearer " + supervisor)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        }

        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM daily_progress_reports
                WHERE site_id = ?::uuid AND report_date = ?
                """, Integer.class, SITE_A, day);
        assertThat(count).isEqualTo(1);
    }

    // ---------------------------------------------------------------- the printed report

    /** The PDF renders, and it renders a PDF rather than an error page with a PDF content type. */
    @Test
    @DisplayName("a verified report prints as a PDF")
    void theReportPrints() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/dprs/" + VERIFIED_DPR + "/pdf")
                        .header("Authorization", "Bearer " + loginToken("uttam")))
                .andExpect(status().isOk())
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body.length).isGreaterThan(1000);
        assertThat(new String(body, 0, 5)).isEqualTo("%PDF-");
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .contains("DPR-2025-9001");
    }

    /** Site scope fences the print route like every other read. */
    @Test
    @DisplayName("a supervisor cannot print a report from a site he is not posted to")
    void printingIsSiteScoped() throws Exception {
        // Written at KSN-B by the administrator, who sees every site. Vivek is posted to KSN-A.
        String otherSiteDpr = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/dprs")
                        .header("Authorization", "Bearer " + loginToken("viplove"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","siteId":"%s","reportDate":"2025-04-20",
                                 "workSummary":"Annexe block: column shuttering"}"""
                                .formatted(otherSiteDpr, SITE_B)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/dprs/" + otherSiteDpr + "/pdf")
                        .header("Authorization", "Bearer " + loginToken("vivek")))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A past day at KSN-A with no report on it yet, so each test opens its own without colliding
     * with V903's two or with another test's.
     *
     * <p>April 2025 on purpose. The suite shares one database, so a test that writes into a month
     * another test reads breaks that test for reasons nobody can find — V903 keeps clear of the
     * report windows for the same reason, and {@code DprPrefillIntegrationTest} holds 4 May as a
     * day with nothing on it.</p>
     */
    private synchronized LocalDate freeDay() {
        Integer offset = jdbc.queryForObject("""
                SELECT count(*) FROM daily_progress_reports WHERE site_id = ?::uuid
                """, Integer.class, SITE_A);
        return LocalDate.of(2025, 4, 1).plusDays(offset == null ? 0 : offset);
    }

    /** A draft with one measured brickwork line on it. */
    private String draft(String token, LocalDate day, String quantity) throws Exception {
        String id = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/dprs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","siteId":"%s","reportDate":"%s","weather":"CLEAR",
                                 "workSummary":"Brickwork on the north wall",
                                 "workItems":[{"boqItemId":"%s","activity":"Brickwork 1:4",
                                   "workLocation":"Ground floor","quantity":%s}]}"""
                                .formatted(id, SITE_A, day, BOQ_BRICKWORK, quantity)))
                .andExpect(status().isCreated());
        return id;
    }

    /** A draft whose only work line describes work without measuring it. */
    private String draftUnmeasured(String token, LocalDate day) throws Exception {
        String id = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/dprs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","siteId":"%s","reportDate":"%s",
                                 "workSummary":"Shuttering struck and stacked",
                                 "workItems":[{"boqItemId":"%s",
                                   "activity":"Shuttering struck","workLocation":"Ground floor"}]}"""
                                .formatted(id, SITE_A, day, BOQ_BRICKWORK)))
                .andExpect(status().isCreated());
        return id;
    }

    private void submit(String token, String id) throws Exception {
        mockMvc.perform(post("/api/v1/dprs/" + id + "/submit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("SUBMITTED"));
    }

    private void verify(String token, String id) throws Exception {
        mockMvc.perform(post("/api/v1/dprs/" + id + "/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"VERIFY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("VERIFIED"));
    }

    private JsonNode report(String token, String id) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/dprs/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private BigDecimal completedQuantity(String boqItemId) {
        return jdbc.queryForObject(
                "SELECT completed_quantity FROM boq_items WHERE id = ?::uuid",
                BigDecimal.class, boqItemId);
    }

    private int entriesFor(String dprId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM boq_progress_entries WHERE dpr_id = ?::uuid",
                Integer.class, dprId);
        return count == null ? 0 : count;
    }

    /** Writes the duplicate the index exists to stop, straight past the service. */
    private void assertThatDuplicateEntryIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO boq_progress_entries
                    (org_id, boq_item_id, site_id, entry_date, quantity, dpr_id, remarks,
                     recorded_by)
                SELECT org_id, boq_item_id, site_id, entry_date, quantity, dpr_id, remarks,
                       recorded_by
                FROM boq_progress_entries
                WHERE dpr_id = ?::uuid AND boq_item_id = ?::uuid
                """, VERIFIED_DPR, BOQ_BRICKWORK))
                .hasMessageContaining("uq_boq_entry_dpr_item");
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asText());
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

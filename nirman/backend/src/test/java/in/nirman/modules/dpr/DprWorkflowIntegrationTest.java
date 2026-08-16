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
 *
 * <p>Two more shape the tests below. The report has <b>two authors</b> — the supervisor records
 * what the day was and hands it over, the engineer records what was built and signs — which is
 * why almost every helper here takes both tokens. And a day the site did not work is <b>a
 * different report, not an empty one</b>: it carries a cause and no work at all, and it is
 * signed like any other.</p>
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
        String id = draft(supervisor, engineer, day, "12.5");

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

        String id = draft(supervisor, engineer, day, "8");
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
        String id = draftUnmeasured(supervisor, engineer, day);
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
        String id = draft(supervisor, engineer, freeDay(), "5");
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

    /**
     * A working day that claims nothing and describes nothing is not a report.
     *
     * <p>Refused at the signature rather than at the handover, and that is the point of where
     * the check sits. Nobody has written the work half when the supervisor hands the day over —
     * it is not his to write — so refusing him would be refusing him for somebody else's
     * omission. The engineer is the one who owns the missing half and the one being asked to
     * sign, so he is the one told.</p>
     */
    @Test
    @DisplayName("a working day with neither a work line nor a summary cannot be signed")
    void anEmptyReportCannotBeVerified() throws Exception {
        String supervisor = loginToken("vivek");
        String engineer = loginToken("uttam");
        String id = openDay(supervisor, freeDay());

        // The handover is fine: the supervisor said what the day was, which is all he owes.
        submit(supervisor, id);

        mockMvc.perform(post("/api/v1/dprs/" + id + "/verify")
                        .header("Authorization", "Bearer " + engineer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"VERIFY\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("nothing to sign for")));
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
        String engineer = loginToken("uttam");
        String id = draft(supervisor, engineer, freeDay(), "6");
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
                {"id":"%s","siteId":"%s","reportDate":"%s","weather":"CLOUDY"}"""
                .formatted(id, SITE_A, day);

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

    // ---------------------------------------------------------------- two halves, two people

    /**
     * The line the whole split exists to draw. A quantity on a daily report becomes a claim
     * against the contract the moment the engineer signs it, so the man who measures it and the
     * man who stands behind it are the same man — and the supervisor, who holds {@code
     * dpr:draft} and not {@code dpr:verify}, is refused before he can write one.
     */
    @Test
    @DisplayName("a supervisor cannot write the work done, only what the day was")
    void supervisorsDoNotClaimWork() throws Exception {
        String supervisor = loginToken("vivek");

        mockMvc.perform(post("/api/v1/dprs")
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","siteId":"%s","reportDate":"%s",
                                 "workItems":[{"boqItemId":"%s","activity":"Brickwork 1:4",
                                   "quantity":40}]}"""
                                .formatted(UUID.randomUUID(), SITE_A, freeDay(), BOQ_BRICKWORK)))
                .andExpect(status().isForbidden());

        // Nor by the back door of the narrative, which is the same half of the report.
        mockMvc.perform(post("/api/v1/dprs")
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","siteId":"%s","reportDate":"%s",
                                 "workSummary":"Brickwork carried up to sill level"}"""
                                .formatted(UUID.randomUUID(), SITE_A, freeDay())))
                .andExpect(status().isForbidden());
    }

    /**
     * The other end of the handover: a submitted report is not finished, it is on the
     * engineer's desk, and he has to be able to write the half only he can write.
     */
    @Test
    @DisplayName("the engineer completes a handed-over report and signs it")
    void theEngineerFinishesWhatWasHandedOver() throws Exception {
        String supervisor = loginToken("vivek");
        String engineer = loginToken("uttam");
        LocalDate day = freeDay();

        BigDecimal before = completedQuantity(BOQ_BRICKWORK);
        String id = openDay(supervisor, day);
        submit(supervisor, id);

        // Written after the handover, onto a report that is already submitted.
        writeWork(engineer, id, """
                {"boqItemId":"%s","activity":"Brickwork 1:4","quantity":9}"""
                .formatted(BOQ_BRICKWORK), "Brickwork on the north wall");
        assertThat(report(engineer, id).get("workflowStatus").asText())
                .as("writing his half does not move the report out of the queue")
                .isEqualTo("SUBMITTED");

        verify(engineer, id);
        assertThat(completedQuantity(BOQ_BRICKWORK))
                .isEqualByComparingTo(before.add(new BigDecimal("9")));
    }

    /**
     * Plant sits on the supervisor's side of the line, and the line runs where claiming does.
     * A quantity is a claim against the contract; a mixer's running hours are a fact about the
     * day, billed to nobody, and the man standing next to it is the one who knows them.
     */
    @Test
    @DisplayName("plant on site is the supervisor's to record, and freezes with his half")
    void plantIsTheSupervisorsHalf() throws Exception {
        String supervisor = loginToken("vivek");
        String engineer = loginToken("uttam");

        String id = openDay(supervisor, freeDay(), """
                "weather":"CLEAR","machinery":[{"machineryName":"Concrete mixer",
                  "count":1,"hoursUsed":6,"idleHours":2}]""");
        assertThat(report(supervisor, id).get("machinery")).hasSize(1);

        submit(supervisor, id);
        long version = report(engineer, id).get("version").asLong();

        // And once handed over it is frozen with the rest of what he said about the day.
        mockMvc.perform(put("/api/v1/dprs/" + id)
                        .header("Authorization", "Bearer " + engineer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"machinery":[{"machineryName":"Excavator","count":1,
                                  "hoursUsed":8,"idleHours":0}],"version":%d}"""
                                .formatted(version)))
                .andExpect(status().isUnprocessableEntity());

        assertThat(report(engineer, id).get("machinery").get(0).get("machineryName").asText())
                .isEqualTo("Concrete mixer");
    }

    /** A supervisor has no business in a report that is with the engineer. */
    @Test
    @DisplayName("a supervisor cannot edit a report he has handed over")
    void aHandedOverReportIsNotTheSupervisorsAnyMore() throws Exception {
        String supervisor = loginToken("vivek");
        String id = openDay(supervisor, freeDay());
        long version = report(supervisor, id).get("version").asLong();
        submit(supervisor, id);

        mockMvc.perform(put("/api/v1/dprs/" + id)
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"weather":"HEAVY_RAIN","version":%d}""".formatted(version + 1)))
                .andExpect(status().isUnprocessableEntity());
    }

    /**
     * What the day was freezes at the handover along with the figures, and the engineer cannot
     * quietly rewrite it — he would be signing his own account of somebody else's day.
     */
    @Test
    @DisplayName("the engineer cannot rewrite the conditions the supervisor recorded")
    void theSupervisorsHalfFreezesAtTheHandover() throws Exception {
        String supervisor = loginToken("vivek");
        String engineer = loginToken("uttam");
        String id = openDay(supervisor, freeDay());
        submit(supervisor, id);
        long version = report(engineer, id).get("version").asLong();

        mockMvc.perform(put("/api/v1/dprs/" + id)
                        .header("Authorization", "Bearer " + engineer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"weather":"HEAVY_RAIN","workSummary":"Slab poured",
                                 "version":%d}""".formatted(version)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("frozen")));

        assertThat(report(engineer, id).get("weather").asText()).isEqualTo("CLEAR");
    }

    // ---------------------------------------------------------------- the day it did not work

    /**
     * The second flow, end to end. A day the site did not work is a report like any other — it
     * is opened, handed over and signed — and it carries a cause instead of work done, because
     * there was none. A missing report says nothing; this one says something a claim for an
     * extension of time can be made on.
     */
    @Test
    @DisplayName("a day the site did not work is signed on its cause, with no work on it")
    void aLostDayIsARealReport() throws Exception {
        String supervisor = loginToken("vivek");
        String engineer = loginToken("uttam");
        LocalDate day = freeDay();

        String id = openDay(supervisor, day, """
                "siteOperational":false,"nonOperationalCause":"WEATHER",
                 "nonOperationalNote":"River road under water from first light\"""");
        submit(supervisor, id);
        verify(engineer, id);

        JsonNode signed = report(engineer, id);
        assertThat(signed.get("siteOperational").asBoolean()).isFalse();
        assertThat(signed.get("nonOperationalCause").asText()).isEqualTo("WEATHER");
        assertThat(signed.get("nonOperationalNote").asText()).contains("River road");
        assertThat(entriesFor(id)).as("a lost day claims nothing").isZero();
    }

    /** A lost day with no cause on it cannot be counted, so it cannot be written either. */
    @Test
    @DisplayName("a day the site did not work has to say why")
    void aLostDayNeedsItsCause() throws Exception {
        mockMvc.perform(post("/api/v1/dprs")
                        .header("Authorization", "Bearer " + loginToken("vivek"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","siteId":"%s","reportDate":"%s",
                                 "siteOperational":false}"""
                                .formatted(UUID.randomUUID(), SITE_A, freeDay())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("has to say why")));
    }

    /** "Other" is not an explanation on its own. */
    @Test
    @DisplayName("the cause \"other\" is refused without the sentence that explains it")
    void otherNeedsItsNote() throws Exception {
        mockMvc.perform(post("/api/v1/dprs")
                        .header("Authorization", "Bearer " + loginToken("vivek"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","siteId":"%s","reportDate":"%s",
                                 "siteOperational":false,"nonOperationalCause":"OTHER"}"""
                                .formatted(UUID.randomUUID(), SITE_A, freeDay())))
                .andExpect(status().isUnprocessableEntity());
    }

    /**
     * A report cannot both say nobody worked and claim a quantity against the contract. It is
     * the work item that is refused rather than the whole report, because that is the row that
     * reaches the measurement book and ends in a bill.
     */
    @Test
    @DisplayName("a lost day cannot also claim work against the contract")
    void aLostDayClaimsNothing() throws Exception {
        String supervisor = loginToken("vivek");
        String engineer = loginToken("uttam");
        String id = openDay(supervisor, freeDay(), """
                "siteOperational":false,"nonOperationalCause":"STRIKE\"""");
        long version = report(engineer, id).get("version").asLong();

        mockMvc.perform(put("/api/v1/dprs/" + id)
                        .header("Authorization", "Bearer " + engineer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workItems":[{"boqItemId":"%s","activity":"Brickwork 1:4",
                                  "quantity":12}],"version":%d}"""
                                .formatted(BOQ_BRICKWORK, version)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("did not work")));
    }

    /**
     * The mirror of the rule above, from the other side. If a quantity has been measured then
     * the site worked, and that is the answer to change — so the day is refused rather than the
     * engineer's lines being deleted to make room for it.
     */
    @Test
    @DisplayName("a day with work already on it cannot be turned into a day nobody worked")
    void workAlreadyRecordedRefusesTheLostDay() throws Exception {
        String supervisor = loginToken("vivek");
        String engineer = loginToken("uttam");
        String id = draft(supervisor, engineer, freeDay(), "15");
        long version = report(supervisor, id).get("version").asLong();

        mockMvc.perform(put("/api/v1/dprs/" + id)
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"siteOperational":false,"nonOperationalCause":"HOLIDAY",
                                 "version":%d}""".formatted(version)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("already has work recorded")));
    }

    /**
     * The flag and the cause are one fact in two columns, and the database keeps them agreeing
     * for the write that never goes through the service.
     */
    @Test
    @DisplayName("the schema refuses a lost day with no cause, and a working day with one")
    void theCheckConstraintBacksTheService() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                UPDATE daily_progress_reports SET site_operational = false
                WHERE id = ?::uuid""", DRAFT_DPR))
                .hasMessageContaining("ck_dpr_operational_cause");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                UPDATE daily_progress_reports SET non_operational_cause = 'WEATHER'
                WHERE id = ?::uuid""", DRAFT_DPR))
                .hasMessageContaining("ck_dpr_operational_cause");
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

    /**
     * The lost day prints too, and it prints a different page: the cause stands above
     * everything, and the sections that would be a column of dashes are not drawn at all.
     */
    @Test
    @DisplayName("a day the site did not work prints its cause instead of empty sections")
    void theLostDayPrints() throws Exception {
        String supervisor = loginToken("vivek");
        String engineer = loginToken("uttam");
        String id = openDay(supervisor, freeDay(), """
                "siteOperational":false,"nonOperationalCause":"DEPARTMENT_INSTRUCTION",
                 "nonOperationalNote":"Stop-work letter of 12 April on the shed wall\"""");
        submit(supervisor, id);
        verify(engineer, id);

        byte[] body = mockMvc.perform(get("/api/v1/dprs/" + id + "/pdf")
                        .header("Authorization", "Bearer " + engineer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(new String(body, 0, 5)).isEqualTo("%PDF-");
        assertThat(body.length).isGreaterThan(1000);
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

    /**
     * The supervisor's half: a day at the site, and what it was like. No work on it, because
     * work done is not his to write.
     */
    private String openDay(String token, LocalDate day) throws Exception {
        return openDay(token, day, "\"weather\":\"CLEAR\"");
    }

    private String openDay(String token, LocalDate day, String conditions) throws Exception {
        String id = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/dprs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","siteId":"%s","reportDate":"%s",%s}"""
                                .formatted(id, SITE_A, day, conditions)))
                .andExpect(status().isCreated());
        return id;
    }

    /** The engineer's half, written onto a report the supervisor opened. */
    private void writeWork(String token, String id, String workItems, String summary)
            throws Exception {
        long version = report(token, id).get("version").asLong();
        mockMvc.perform(put("/api/v1/dprs/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workSummary":"%s","workItems":[%s],"version":%d}"""
                                .formatted(summary, workItems, version)))
                .andExpect(status().isOk());
    }

    /** A draft with one measured brickwork line on it, written by the two people it takes. */
    private String draft(String supervisor, String engineer, LocalDate day, String quantity)
            throws Exception {
        String id = openDay(supervisor, day);
        writeWork(engineer, id, """
                {"boqItemId":"%s","activity":"Brickwork 1:4",
                 "workLocation":"Ground floor","quantity":%s}"""
                .formatted(BOQ_BRICKWORK, quantity), "Brickwork on the north wall");
        return id;
    }

    /** A draft whose only work line describes work without measuring it. */
    private String draftUnmeasured(String supervisor, String engineer, LocalDate day)
            throws Exception {
        String id = openDay(supervisor, day);
        writeWork(engineer, id, """
                {"boqItemId":"%s","activity":"Shuttering struck","workLocation":"Ground floor"}"""
                .formatted(BOQ_BRICKWORK), "Shuttering struck and stacked");
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

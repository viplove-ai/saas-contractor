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
import org.springframework.test.web.servlet.ResultActions;

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
    /**
     * The photograph is the report's only evidence, so it is the one thing the handover
     * refuses to go without.
     *
     * <p>Every other figure on the supervisor's half could in principle be reconstructed from
     * another register — the muster has the men, the store has the lorry. A picture of the
     * work face cannot be produced from a desk, which is the whole of why it is worth
     * demanding: it is the part of the report that says somebody was standing there.</p>
     */
    @Test
    @DisplayName("a day cannot be handed over without a photograph of the site")
    void handoverNeedsAPhotograph() throws Exception {
        String supervisor = loginToken("vivek");
        String engineer = loginToken("uttam");
        String id = draft(supervisor, engineer, freeDay(), "5");

        mockMvc.perform(post("/api/v1/dprs/" + id + "/submit")
                        .header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("no photograph")));

        // And it goes through the moment there is one.
        photograph(supervisor, id);
        mockMvc.perform(post("/api/v1/dprs/" + id + "/submit")
                        .header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("SUBMITTED"));
    }

    /**
     * A day the site did not work needs one too, and this is the case the rule is most for.
     *
     * <p>"Nine days lost to rain in July" is a claim against the department. A flooded site
     * photographed on the ninth is what an extension of time is granted on; the same sentence
     * with nothing behind it is one the department can refuse.</p>
     */
    @Test
    @DisplayName("a day the site did not work needs a photograph as much as one it did")
    void aRainDayNeedsAPhotographToo() throws Exception {
        String supervisor = loginToken("vivek");
        String id = openDay(supervisor, freeDay(), """
                "siteOperational":false,"nonOperationalCause":"WEATHER",
                 "nonOperationalNote":"River road under water from first light\"""");

        mockMvc.perform(post("/api/v1/dprs/" + id + "/submit")
                        .header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("no photograph")));

        photograph(supervisor, id);
        mockMvc.perform(post("/api/v1/dprs/" + id + "/submit")
                        .header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isOk());
    }

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
    @DisplayName("plant on site is the supervisor's to record, and stays his after the handover")
    void plantIsTheSupervisorsHalf() throws Exception {
        String supervisor = loginToken("vivek");
        String engineer = loginToken("uttam");

        String id = openDay(supervisor, freeDay(), """
                "weather":"CLEAR","machinery":[{"machineryName":"Concrete mixer",
                  "count":1,"hoursUsed":6,"idleHours":2}]""");
        assertThat(report(supervisor, id).get("machinery")).hasSize(1);

        submit(supervisor, id);

        // The engineer may not rewrite it, handed over or not: he signs somebody else's
        // account of a day he may not have been standing on.
        mockMvc.perform(put("/api/v1/dprs/" + id)
                        .header("Authorization", "Bearer " + engineer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"machinery":[{"machineryName":"Excavator","count":1,
                                  "hoursUsed":8,"idleHours":0}],"version":%d}"""
                                .formatted(report(engineer, id).get("version").asLong())))
                .andExpect(status().isUnprocessableEntity());

        assertThat(report(engineer, id).get("machinery").get(0).get("machineryName").asText())
                .isEqualTo("Concrete mixer");

        // The site may, because the second mixer that arrived at four is a fact about the day
        // and the man who saw it is the one holding the handset.
        mockMvc.perform(put("/api/v1/dprs/" + id)
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"machinery":[{"machineryName":"Concrete mixer","count":1,
                                  "hoursUsed":6,"idleHours":2},
                                 {"machineryName":"Excavator","count":1,
                                  "hoursUsed":8,"idleHours":0}],"version":%d}"""
                                .formatted(report(supervisor, id).get("version").asLong())))
                .andExpect(status().isOk());

        assertThat(report(engineer, id).get("machinery")).hasSize(2);
    }

    /**
     * The hours are the supervisor's and the rate is not.
     *
     * <p>The same division the rest of the system draws: the field may name a thing and never
     * value it. He watched the mixer run six hours; the hire agreement is not his, and a rate
     * box on his screen is a number guessed at seven in the evening. So the rate is written
     * after the handover by whoever the report went to, and the amount is the server's
     * arithmetic rather than anybody's typing.</p>
     */
    @Test
    @DisplayName("what the plant costs is priced after the handover, and never by the site")
    void plantIsPricedByWhoeverTheReportWentTo() throws Exception {
        String supervisor = loginToken("vivek");
        String engineer = loginToken("uttam");

        String id = openDay(supervisor, freeDay(), """
                "weather":"CLEAR","machinery":[{"machineryName":"Concrete mixer",
                  "count":1,"hoursUsed":6,"idleHours":2}]""");
        String row = report(supervisor, id).get("machinery").get(0).get("id").asText();

        // Not yet. A draft has not been given to anybody, and its plant list is still moving
        // under the man typing it.
        priceThePlant(engineer, id, row, "450", "HOUR").andExpect(status().isUnprocessableEntity());

        submit(supervisor, id);

        // And not by the site, whatever the state of the report.
        priceThePlant(supervisor, id, row, "450", "HOUR").andExpect(status().isForbidden());

        priceThePlant(engineer, id, row, "450", "HOUR").andExpect(status().isOk());

        JsonNode plant = report(engineer, id).get("machinery").get(0);
        assertThat(decimal(plant, "hireRate")).isEqualByComparingTo("450.0000");
        assertThat(plant.get("rateBasis").asText()).isEqualTo("HOUR");
        // Six hours run at 450, and the two idle hours are not charged at the running rate:
        // standby is agreed separately, and charging it here would invent the figure.
        assertThat(decimal(plant, "hireAmount")).isEqualByComparingTo("2700.00");
        assertThat(plant.get("rateSetAt").isNull()).isFalse();
    }

    /**
     * The office's figure must not be deleted by the site's correction.
     *
     * <p>The day's account stays open to the supervisor until the signature, and the update
     * rebuilds the whole plant table — so the second mixer that arrives at four is entered on
     * a report somebody has already priced. The rate travels with the machine's name across
     * that rebuild, and a machine nobody has priced yet stays unpriced.</p>
     */
    @Test
    @DisplayName("a priced machine keeps its rate when the site corrects the day's plant")
    void aRateSurvivesTheSupervisorsCorrection() throws Exception {
        String supervisor = loginToken("vivek");
        String engineer = loginToken("uttam");

        String id = openDay(supervisor, freeDay(), """
                "weather":"CLEAR","machinery":[{"machineryName":"Concrete mixer",
                  "count":1,"hoursUsed":6,"idleHours":2}]""");
        submit(supervisor, id);
        priceThePlant(engineer, id, report(engineer, id).get("machinery").get(0).get("id").asText(),
                "450", "HOUR").andExpect(status().isOk());

        // The excavator that turned up at four, entered by the man who saw it.
        mockMvc.perform(put("/api/v1/dprs/" + id)
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"machinery":[{"machineryName":"Concrete mixer","count":1,
                                  "hoursUsed":7,"idleHours":1},
                                 {"machineryName":"Excavator","count":1,
                                  "hoursUsed":8,"idleHours":0}],"version":%d}"""
                                .formatted(report(supervisor, id).get("version").asLong())))
                .andExpect(status().isOk());

        JsonNode plant = report(engineer, id).get("machinery");
        JsonNode mixer = plant.get(0).get("machineryName").asText().equals("Concrete mixer")
                ? plant.get(0) : plant.get(1);
        JsonNode excavator = mixer == plant.get(0) ? plant.get(1) : plant.get(0);

        assertThat(decimal(mixer, "hireRate")).isEqualByComparingTo("450.0000");
        // The corrected hours, at the rate that was already agreed: the amount is derived per
        // call, so it follows the seventh hour rather than staying at what it was on Tuesday.
        assertThat(decimal(mixer, "hireAmount")).isEqualByComparingTo("3150.00");
        // Absent rather than zero: nobody has priced the excavator, and unpriced is not free.
        assertThat(excavator.hasNonNull("hireRate")).isFalse();
        assertThat(excavator.hasNonNull("hireAmount")).isFalse();
    }

    /** An approved report is finished, and a figure that can still move is not a signed one. */
    @Test
    @DisplayName("plant cannot be repriced once the office has approved the report")
    void anApprovedReportIsNoLongerPriceable() throws Exception {
        String supervisor = loginToken("vivek");
        String engineer = loginToken("uttam");
        String office = loginToken("viplove");

        String id = draft(supervisor, engineer, freeDay(), "12");
        mockMvc.perform(put("/api/v1/dprs/" + id)
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"machinery":[{"machineryName":"Concrete mixer","count":1,
                                  "hoursUsed":6,"idleHours":2}],"version":%d}"""
                                .formatted(report(supervisor, id).get("version").asLong())))
                .andExpect(status().isOk());
        submit(supervisor, id);
        String row = report(engineer, id).get("machinery").get(0).get("id").asText();

        // The office may price a signed report — the engineer signs what was built, and what
        // the hire costs is the office's own fact.
        verify(engineer, id);
        priceThePlant(office, id, row, "500", "DAY").andExpect(status().isOk());
        assertThat(decimal(report(office, id).get("machinery").get(0), "hireAmount"))
                .as("one machine, one day").isEqualByComparingTo("500.00");

        mockMvc.perform(post("/api/v1/dprs/" + id + "/approval")
                        .header("Authorization", "Bearer " + office))
                .andExpect(status().isOk());

        priceThePlant(office, id, row, "900", "DAY").andExpect(status().isUnprocessableEntity());
    }

    /**
     * The day's account stays with the site until the report is signed.
     *
     * <p>It used to stop at the handover, and a supervisor who saw at seven in the evening that
     * he had typed the wrong weather had nowhere to put it. Nothing about authorship is
     * consulted — the rule is the permission and the site posting — so the man who relieved him
     * writes it on the same terms.</p>
     */
    @Test
    @DisplayName("a supervisor still corrects the day's account after handing the report over")
    void aHandedOverReportStillTakesTheDaysAccount() throws Exception {
        String supervisor = loginToken("vivek");
        String id = openDay(supervisor, freeDay());
        submit(supervisor, id);

        long version = report(supervisor, id).get("version").asLong();
        mockMvc.perform(put("/api/v1/dprs/" + id)
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"weather":"HEAVY_RAIN","version":%d}""".formatted(version)))
                .andExpect(status().isOk());

        JsonNode after = report(supervisor, id);
        assertThat(after.get("weather").asText()).isEqualTo("HEAVY_RAIN");
        assertThat(after.get("workflowStatus").asText())
                .as("correcting the day does not take the report out of the engineer's queue")
                .isEqualTo("SUBMITTED");
    }

    /**
     * The figures are a different promise from the day's account, and it did not move.
     *
     * <p>They freeze at the handover because the report is a document: a muster corrected
     * afterwards has to show up as a difference between the report and today's records, not by
     * rewriting what somebody was sent. So a supervisor correcting the weather on a submitted
     * report must not quietly re-run the snapshot along with it.</p>
     */
    @Test
    @DisplayName("correcting the day after handover does not re-open the frozen figures")
    void theFiguresStayFrozenWhenTheDaysAccountIsCorrected() throws Exception {
        String supervisor = loginToken("vivek");
        String id = openDay(supervisor, freeDay());
        submit(supervisor, id);
        JsonNode atHandover = report(supervisor, id);

        mockMvc.perform(put("/api/v1/dprs/" + id)
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"weather":"HEAVY_RAIN","temperatureC":19,"version":%d}"""
                                .formatted(atHandover.get("version").asLong())))
                .andExpect(status().isOk());

        JsonNode after = report(supervisor, id);
        assertThat(after.get("snapshotFrozen").asBoolean()).isTrue();
        assertThat(decimal(after, "labourCost"))
                .isEqualByComparingTo(decimal(atHandover, "labourCost"));
        assertThat(after.get("labourPresentCount").asInt())
                .isEqualTo(atHandover.get("labourPresentCount").asInt());
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
     * One report is three documents. The department gets the work and the conditions; the
     * muster roll carries names and wages and the day's cost is the firm's own business, and
     * the only way to send a shortened copy used to be a pen through the printed page.
     *
     * <p>Asserted by size rather than by reading the PDF: openhtmltopdf compresses its content
     * streams, so the text is not there to grep for. A copy with four sections dropped that is
     * not smaller than the whole form is a {@code sections} parameter that did nothing.</p>
     */
    @Test
    @DisplayName("a printed copy can leave sections out, and is shorter for it")
    void theReportPrintsAsAnExtract() throws Exception {
        String token = loginToken("uttam");
        byte[] whole = mockMvc.perform(get("/api/v1/dprs/" + VERIFIED_DPR + "/pdf")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        byte[] extract = mockMvc.perform(get("/api/v1/dprs/" + VERIFIED_DPR + "/pdf")
                        .param("sections", "WORK")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(new String(extract, 0, 5)).isEqualTo("%PDF-");
        assertThat(extract.length).isLessThan(whole.length);
    }

    /**
     * An empty tick list is somebody who changed their mind, not a request for a letterhead
     * over two signature lines — so it prints the whole form, exactly as saying nothing does.
     */
    @Test
    @DisplayName("asking for no sections prints the whole report")
    void noSectionsPrintsEverything() throws Exception {
        String token = loginToken("uttam");
        byte[] whole = mockMvc.perform(get("/api/v1/dprs/" + VERIFIED_DPR + "/pdf")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        byte[] empty = mockMvc.perform(get("/api/v1/dprs/" + VERIFIED_DPR + "/pdf")
                        .param("sections", "")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(new String(empty, 0, 5)).isEqualTo("%PDF-");
        assertThat(empty.length).isEqualTo(whole.length);
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

    // ---------------------------------------------------------------- the office's approval

    /**
     * The step above the engineer, and the whole of what it changes.
     *
     * <p>A signed report used to be the end of the line, so the office first met a fortnight of
     * figures in a monthly return with no recorded moment of having accepted them. Now it has
     * one — and it claims nothing, which is the assertion that matters here: the brickwork
     * reached the measurement book when the engineer signed, and the approval leaves it exactly
     * where it was.</p>
     */
    @Test
    @DisplayName("the office approves a signed report, and the approval claims nothing")
    void theOfficeApprovesWhatTheEngineerSigned() throws Exception {
        String supervisor = loginToken("vivek");
        String engineer = loginToken("uttam");
        String office = loginToken("viplove");
        LocalDate day = freeDay();

        String id = draft(supervisor, engineer, day, "7");
        submit(supervisor, id);
        verify(engineer, id);

        BigDecimal claimedAtSignature = completedQuantity(BOQ_BRICKWORK);

        mockMvc.perform(post("/api/v1/dprs/" + id + "/approval")
                        .header("Authorization", "Bearer " + office))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("APPROVED"))
                .andExpect(jsonPath("$.approvedAt").isNotEmpty())
                // The signature is still on the document; approving did not replace it.
                .andExpect(jsonPath("$.verifiedAt").isNotEmpty());

        assertThat(completedQuantity(BOQ_BRICKWORK)).isEqualByComparingTo(claimedAtSignature);
        assertThat(entriesFor(id))
                .as("the measurement book moved at the signature and not again")
                .isEqualTo(1);
    }

    /**
     * Approving is its own permission. The engineer signed it; a second signature by the same
     * man is not a second pair of eyes, which is the only reason the step exists.
     */
    @Test
    @DisplayName("neither the site nor the engineer can give the office's approval")
    void approvingIsTheOfficesAlone() throws Exception {
        String supervisor = loginToken("vivek");
        String engineer = loginToken("uttam");
        LocalDate day = freeDay();

        String id = draft(supervisor, engineer, day, "4");
        submit(supervisor, id);
        verify(engineer, id);

        mockMvc.perform(post("/api/v1/dprs/" + id + "/approval")
                        .header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isForbidden());
    }

    /** There is nothing to countersign until somebody has signed. */
    @Test
    @DisplayName("a report still with the engineer cannot be approved, and neither can one twice")
    void approvalNeedsASignatureAndHappensOnce() throws Exception {
        String supervisor = loginToken("vivek");
        String engineer = loginToken("uttam");
        String office = loginToken("viplove");
        LocalDate day = freeDay();

        String id = draft(supervisor, engineer, day, "5");
        submit(supervisor, id);

        mockMvc.perform(post("/api/v1/dprs/" + id + "/approval")
                        .header("Authorization", "Bearer " + office))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("nothing signed")));

        verify(engineer, id);
        mockMvc.perform(post("/api/v1/dprs/" + id + "/approval")
                        .header("Authorization", "Bearer " + office))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/dprs/" + id + "/approval")
                        .header("Authorization", "Bearer " + office))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("already")));
    }

    /** An approved report is a signed document, and nobody writes on it. */
    @Test
    @DisplayName("an approved report takes no more edits, from either author")
    void anApprovedReportIsClosed() throws Exception {
        String supervisor = loginToken("vivek");
        String engineer = loginToken("uttam");
        String office = loginToken("viplove");
        LocalDate day = freeDay();

        String id = draft(supervisor, engineer, day, "6");
        submit(supervisor, id);
        verify(engineer, id);
        long version = report(office, id).get("version").asLong();
        mockMvc.perform(post("/api/v1/dprs/" + id + "/approval")
                        .header("Authorization", "Bearer " + office))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/dprs/" + id)
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"weather":"HEAVY_RAIN","version":%d}""".formatted(version + 1)))
                .andExpect(status().isUnprocessableEntity());
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

    private ResultActions priceThePlant(String token, String id, String rowId, String rate,
                                        String basis) throws Exception {
        return mockMvc.perform(put("/api/v1/dprs/" + id + "/plant-rates")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"rates":[{"machineryId":"%s","rate":%s,"basis":"%s"}]}"""
                        .formatted(rowId, rate, basis)));
    }

    /**
     * The handover, with the photograph the handover requires.
     *
     * <p>Attached here rather than in every caller because it is now part of what a handover
     * <i>is</i>: the report's only piece of evidence, and the only thing on it that cannot be
     * produced from a desk. {@link #handoverNeedsAPhotograph()} is the test that this is
     * enforced rather than merely done by a helper.</p>
     */
    private void submit(String token, String id) throws Exception {
        photograph(token, id);
        mockMvc.perform(post("/api/v1/dprs/" + id + "/submit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("SUBMITTED"));
    }

    /** One picture of the site, linked onto the report the way the wizard links it. */
    private void photograph(String token, String dprId) throws Exception {
        String attachmentId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO attachments (id, org_id, owner_entity_type, file_name, content_type,
                                         size_bytes, bucket, object_key, kind)
                VALUES (?::uuid, '10000000-0000-0000-0000-000000000001', 'DPR',
                        'site.jpg', 'image/jpeg', 4096, 'nirman', ?, 'PHOTO')""",
                attachmentId, "test/dpr/" + attachmentId);
        mockMvc.perform(post("/api/v1/dprs/" + dprId + "/photos")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentId\":\"%s\",\"caption\":\"the work face\"}"
                                .formatted(attachmentId)))
                .andExpect(status().isOk());
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

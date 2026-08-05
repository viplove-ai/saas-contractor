package in.nirman.modules.labour;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Phase 3 exit criteria from docs/08-roadmap.md, each as a named test.
 *
 * <p>Figures come from the Kausani workbooks: a seven-hour shift, ₹625 a day for a mason,
 * and an overtime rate of ₹625 ÷ 7 that carries no premium.</p>
 *
 * <p><b>Isolation.</b> These drive the real HTTP surface against a shared database, so
 * nothing rolls back between tests. Each test therefore owns its own calendar day, and any
 * test that asserts a running balance owns its own worker — otherwise the suite passes or
 * fails depending on the order JUnit happens to pick.</p>
 */
class AttendanceWorkflowIntegrationTest extends AbstractIntegrationTest {

    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";

    // W-101 mason, 625/day, OT 89.2857
    private static final String KARAM = "60000000-0000-0000-0000-000000000101";
    // W-102 helper, 450/day
    private static final String RAMESH = "60000000-0000-0000-0000-000000000102";
    // W-103 carpenter, 700/day, OT exactly 100
    private static final String SURESH = "60000000-0000-0000-0000-000000000103";
    // W-104 bar bender, 700/day
    private static final String DINESH = "60000000-0000-0000-0000-000000000104";
    /**
     * The other four are each pinned to an exact earned total by some test in this class,
     * so anything new that verifies a row uses Naresh and disturbs nobody's arithmetic.
     */
    private static final String NARESH = "60000000-0000-0000-0000-000000000107";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private String supervisorToken;
    private String engineerToken;
    private String adminToken;

    @BeforeEach
    void signIn() throws Exception {
        supervisorToken = token("vivek");     // SUPERVISOR on KSN-A: may enter, may not verify
        engineerToken = token("uttam");       // ENGINEER: may verify
        adminToken = token("viplove");        // ADMIN: may revise wages and lock periods
    }

    // ------------------------------------------------------------------ roster and entry

    @Test
    @DisplayName("the roster lists everyone posted to the site with their rates")
    void rosterListsThePostedWorkers() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/attendance/roster")
                        .param("siteId", SITE_A)
                        .param("date", "2025-06-01")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.standardShiftHours").value(7.00))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("entries")).hasSize(5);
        JsonNode karam = entryFor(body, KARAM);
        assertThat(karam.get("workerName").asText()).isEqualTo("Karam Singh");
        assertThat(karam.get("normalRate").decimalValue()).isEqualByComparingTo("625.0000");
        // Null fields are omitted from every response (jackson non_null), so an unmarked
        // worker has no attendance key at all rather than an explicit null.
        assertThat(karam.has("attendance"))
                .as("nothing marked yet, so the screen renders an empty row")
                .isFalse();
    }

    @Test
    @DisplayName("attendance calculation lands on the schema: hours, overtime and money")
    void calculationIsPersisted() throws Exception {
        LocalDate day = LocalDate.of(2025, 6, 2);
        UUID id = UUID.randomUUID();
        saveBulk(entry(id, KARAM, "PRESENT", "08:00:00", "18:00:00", 0, "Slab pour ran late"), day);

        JsonNode saved = attendanceRow(id);
        assertThat(saved.get("workedHours").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(saved.get("regularHours").decimalValue()).isEqualByComparingTo("7.00");
        assertThat(saved.get("overtimeHours").decimalValue()).isEqualByComparingTo("3.00");
        assertThat(saved.get("computedWageAmount").decimalValue()).isEqualByComparingTo("625.00");
        // 3 x 89.2857, with no 1.5x invented anywhere.
        assertThat(saved.get("computedOtAmount").decimalValue()).isEqualByComparingTo("267.86");
    }

    @Test
    @DisplayName("typed hours split at the shift length: nine hours is seven regular and two over")
    void typedHoursArePersisted() throws Exception {
        LocalDate day = LocalDate.of(2025, 6, 20);
        UUID id = UUID.randomUUID();
        saveBulk(entry(id, KARAM, "PRESENT", null, null, 0, "9", null), day);

        JsonNode saved = attendanceRow(id);
        assertThat(saved.get("enteredHours").decimalValue()).isEqualByComparingTo("9.00");
        assertThat(saved.get("workedHours").decimalValue()).isEqualByComparingTo("9.00");
        assertThat(saved.get("regularHours").decimalValue()).isEqualByComparingTo("7.00");
        assertThat(saved.get("overtimeHours").decimalValue()).isEqualByComparingTo("2.00");
        assertThat(saved.get("computedOtAmount").decimalValue()).isEqualByComparingTo("178.57");
    }

    /**
     * The one that matters. Verification re-runs the calculation from the stored row, so if
     * the typed hours did not survive the save the wage would silently revert to a plain
     * standard shift — and the worker would lose his overtime at the moment he is paid for it.
     */
    @Test
    @DisplayName("typed hours survive to verification and are what the ledger is paid on")
    void typedHoursSurviveVerification() throws Exception {
        LocalDate day = LocalDate.of(2025, 6, 21);
        UUID id = UUID.randomUUID();
        saveBulk(entry(id, NARESH, "PRESENT", null, null, 0, "9", null), day);
        submit(day);
        verify(id);

        JsonNode verified = attendanceRow(id);
        assertThat(verified.get("workflowStatus").asText()).isEqualTo("VERIFIED");
        assertThat(verified.get("enteredHours").decimalValue()).isEqualByComparingTo("9.00");
        assertThat(verified.get("overtimeHours").decimalValue()).isEqualByComparingTo("2.00");
        assertThat(verified.get("computedOtAmount").decimalValue()).isPositive();
    }

    @Test
    @DisplayName("switching a marked day to absent drops the hours rather than paying for them")
    void absentClearsTypedHours() throws Exception {
        LocalDate day = LocalDate.of(2025, 6, 22);
        UUID id = UUID.randomUUID();
        saveBulk(entry(id, KARAM, "PRESENT", null, null, 0, "9", null), day);
        saveBulk(entry(id, KARAM, "ABSENT", null, null, 0, "9", null), day);

        JsonNode saved = attendanceRow(id);
        // Null fields are omitted from the payload rather than serialised as null.
        JsonNode hours = saved.get("enteredHours");
        assertThat(hours == null || hours.isNull())
                .as("the hours are gone from the row, not merely ignored by the calculation")
                .isTrue();
        assertThat(saved.get("workedHours").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(saved.get("computedWageAmount").decimalValue()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a submitted row can still be corrected by the supervisor who sent it")
    void submittedIsStillEditable() throws Exception {
        LocalDate day = LocalDate.of(2025, 6, 23);
        UUID id = UUID.randomUUID();
        saveBulk(entry(id, KARAM, "PRESENT", null, null, 0, "7", null), day);
        submit(day);
        assertThat(attendanceRow(id).get("workflowStatus").asText()).isEqualTo("SUBMITTED");

        saveBulk(entry(id, KARAM, "PRESENT", null, null, 0, "9", null), day);

        JsonNode corrected = attendanceRow(id);
        assertThat(corrected.get("enteredHours").decimalValue()).isEqualByComparingTo("9.00");
        assertThat(corrected.get("overtimeHours").decimalValue()).isEqualByComparingTo("2.00");
        assertThat(corrected.get("workflowStatus").asText())
                .as("correcting it does not pull it back out of the engineer's queue")
                .isEqualTo("SUBMITTED");
    }

    /**
     * The hazard that comes with making submitted rows editable: submit picks its rows by
     * status, so a second submit for the day must not sweep up the ones already sent and
     * stamp a fresh submitted-at over them.
     */
    @Test
    @DisplayName("re-submitting a day leaves the already-submitted rows untouched")
    void resubmittingDoesNotRestampSubmittedRows() throws Exception {
        LocalDate day = LocalDate.of(2025, 6, 24);
        UUID first = UUID.randomUUID();
        saveBulk(entry(first, KARAM, "PRESENT", null, null, 0, "7", null), day);
        submit(day);
        String submittedAt = attendanceRow(first).get("submittedAt") == null
                ? null
                : attendanceRow(first).get("submittedAt").asText();

        // A second man marked later in the day, then a second submit for the same date.
        UUID second = UUID.randomUUID();
        saveBulk(entry(second, RAMESH, "PRESENT", null, null, 0, "7", null), day);
        submit(day);

        assertThat(attendanceRow(second).get("workflowStatus").asText()).isEqualTo("SUBMITTED");
        JsonNode firstAfter = attendanceRow(first);
        assertThat(firstAfter.get("workflowStatus").asText()).isEqualTo("SUBMITTED");
        assertThat(firstAfter.get("submittedAt") == null
                ? null
                : firstAfter.get("submittedAt").asText())
                .as("the first row keeps the moment it was actually submitted")
                .isEqualTo(submittedAt);
    }

    @Test
    @DisplayName("a verified row is closed to the supervisor, hours and all")
    void verifiedIsNoLongerEditable() throws Exception {
        LocalDate day = LocalDate.of(2025, 6, 25);
        UUID id = UUID.randomUUID();
        saveBulk(entry(id, NARESH, "PRESENT", null, null, 0, "7", null), day);
        submit(day);
        verify(id);

        saveBulk(entry(id, NARESH, "PRESENT", null, null, 0, "9", null), day);

        JsonNode after = attendanceRow(id);
        assertThat(after.get("enteredHours").decimalValue())
                .as("the verified row keeps the hours it was verified against")
                .isEqualByComparingTo("7.00");
        assertThat(after.get("workflowStatus").asText()).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("a night shift crossing midnight is eight hours, not minus sixteen")
    void nightShiftRollsOver() throws Exception {
        LocalDate day = LocalDate.of(2025, 6, 3);
        UUID id = UUID.randomUUID();
        saveBulk(entry(id, KARAM, "PRESENT", "22:00:00", "06:00:00", 0, null), day);

        JsonNode saved = attendanceRow(id);
        assertThat(saved.get("workedHours").decimalValue()).isEqualByComparingTo("8.00");
        assertThat(saved.get("overtimeHours").decimalValue()).isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("a half day pays half the rate and books no overtime whatever the clock says")
    void halfDayNeverBooksOvertime() throws Exception {
        LocalDate day = LocalDate.of(2025, 6, 4);
        UUID id = UUID.randomUUID();
        saveBulk(entry(id, KARAM, "HALF_DAY", "06:00:00", "20:00:00", 0, null), day);

        JsonNode saved = attendanceRow(id);
        assertThat(saved.get("workedHours").decimalValue()).isEqualByComparingTo("14.00");
        assertThat(saved.get("overtimeHours").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(saved.get("computedWageAmount").decimalValue()).isEqualByComparingTo("312.50");
    }

    @Test
    @DisplayName("overtime beyond the threshold without a reason is rejected")
    void unexplainedOvertimeIsRejected() throws Exception {
        LocalDate day = LocalDate.of(2025, 6, 5);
        // 08:00-20:00 is five overtime hours, past the seeded four-hour threshold.
        MvcResult result = postBulk(bulkBody(day,
                entry(UUID.randomUUID(), KARAM, "PRESENT", "08:00:00", "20:00:00", 0, null)));

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("rejected").asInt()).isEqualTo(1);
        assertThat(body.get("outcomes").get(0).get("reason").asText()).contains("needs a reason");
    }

    @Test
    @DisplayName("duplicate attendance for the same worker, site and day is rejected")
    void duplicateAttendanceIsRejected() throws Exception {
        LocalDate day = LocalDate.of(2025, 6, 6);
        saveBulk(entry(UUID.randomUUID(), KARAM, "PRESENT", null, null, 0, null), day);

        // A different id for a worker already marked is a genuine duplicate, not a replay.
        MvcResult result = postBulk(bulkBody(day,
                entry(UUID.randomUUID(), KARAM, "PRESENT", null, null, 0, null)));

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("rejected").asInt()).isEqualTo(1);
        assertThat(body.get("outcomes").get(0).get("outcome").asText()).isEqualTo("REJECTED");
        assertThat(body.get("outcomes").get(0).get("reason").asText()).contains("already marked");
    }

    @Test
    @DisplayName("the same batch re-sent creates one row, not three")
    void resendingABatchIsIdempotent() throws Exception {
        LocalDate day = LocalDate.of(2025, 6, 7);
        UUID id = UUID.randomUUID();
        String body = bulkBody(day, entry(id, KARAM, "PRESENT", null, null, 0, null));

        MvcResult first = postBulk(body);
        assertThat(objectMapper.readTree(first.getResponse().getContentAsString())
                .get("accepted").asInt()).isEqualTo(1);

        // The device could not read the response and sends the identical batch twice more.
        MvcResult replay = postBulk(body);
        postBulk(body);

        JsonNode replayBody = objectMapper.readTree(replay.getResponse().getContentAsString());
        assertThat(replayBody.get("unchanged").asInt())
                .as("a replay is reported as unchanged, not as a fresh edit")
                .isEqualTo(1);
        assertThat(replayBody.get("accepted").asInt()).isZero();

        Integer rows = jdbc.queryForObject("""
                SELECT count(*) FROM attendance_records
                WHERE worker_id = ?::uuid AND attendance_date = ? AND workflow_status <> 'CANCELLED'
                """, Integer.class, KARAM, day);
        assertThat(rows).as("one worker, one day, one row").isEqualTo(1);
    }

    @Test
    @DisplayName("a supervisor may enter attendance but not verify it")
    void supervisorCannotVerify() throws Exception {
        LocalDate day = LocalDate.of(2025, 6, 8);
        UUID id = UUID.randomUUID();
        saveBulk(entry(id, KARAM, "PRESENT", null, null, 0, null), day);
        submit(day);

        mockMvc.perform(post("/api/v1/attendance/verify")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + id + "\"],\"action\":\"VERIFY\"}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ the ledger

    @Test
    @DisplayName("verifying the same attendance row twice does not pay the worker twice")
    void verifyingTwiceDoesNotPayTwice() throws Exception {
        LocalDate day = LocalDate.of(2025, 6, 11);
        UUID id = UUID.randomUUID();
        // Suresh: 700 a day, overtime exactly 100 an hour, and used by no other test.
        saveBulk(entry(id, SURESH, "PRESENT", "08:00:00", "18:00:00", 0, "Shuttering"), day);
        submit(day);

        verify(id);
        assertThat(earnedFor(SURESH)).isEqualByComparingTo("1000.00");   // 700 + 3 x 100

        // A double click, a retried request, a re-verification after a correction.
        verify(id);
        verify(id);

        assertThat(earnedFor(SURESH))
                .as("the ledger is posted once per attendance row, ever")
                .isEqualByComparingTo("1000.00");

        Integer postings = jdbc.queryForObject("""
                SELECT count(*) FROM worker_ledger_entries
                WHERE source_type = 'ATTENDANCE' AND source_id = ?::uuid
                """, Integer.class, id.toString());
        assertThat(postings).as("one WAGE_EARNED and one OT_EARNED, no more").isEqualTo(2);
    }

    @Test
    @DisplayName("verification freezes the wage, so a later revision cannot reprice the day")
    void verificationFreezesTheWage() throws Exception {
        LocalDate day = LocalDate.of(2025, 6, 12);
        UUID id = UUID.randomUUID();
        saveBulk(entry(id, RAMESH, "PRESENT", null, null, 0, null), day);
        submit(day);
        verify(id);

        JsonNode verified = attendanceRow(id);
        assertThat(verified.get("appliedNormalRate").decimalValue()).isEqualByComparingTo("450.0000");
        assertThat(verified.get("computedWageAmount").decimalValue()).isEqualByComparingTo("450.00");

        reviseWage(RAMESH, "600.0000", "85.7143", day.plusMonths(1), "Annual revision");

        assertThat(attendanceRow(id).get("computedWageAmount").decimalValue())
                .as("the verified day keeps the rate it was verified against")
                .isEqualByComparingTo("450.00");
        assertThat(earnedFor(RAMESH)).isEqualByComparingTo("450.00");
    }

    /**
     * The field sheet computes, per worker per month, {@code Total Amount − Advance =
     * Balance Payment} — Karam's February read ₹26,399 − ₹9,594 = ₹16,805. What is asserted
     * here is that identity, reproduced by the ledger rather than by hand, using the same
     * ₹9,594 advance. The day pattern is our own: forcing the exact rupee total would mean
     * inventing a month of shifts that never happened, which proves less than the identity.
     */
    @Test
    @DisplayName("earned - advance = payable, the arithmetic the field sheet does by hand")
    void earnedMinusAdvanceReconciles() throws Exception {
        // Twenty-four days on the tools, every third one running three hours over.
        BigDecimal expectedEarned = BigDecimal.ZERO;
        for (int dayOfMonth = 1; dayOfMonth <= 24; dayOfMonth++) {
            LocalDate date = LocalDate.of(2025, 7, dayOfMonth);
            boolean overtime = dayOfMonth % 3 == 0;
            UUID id = UUID.randomUUID();
            saveBulk(entry(id, KARAM, "PRESENT",
                    overtime ? "08:00:00" : null,
                    overtime ? "18:00:00" : null,
                    0, overtime ? "Slab pour" : null), date);
            submit(date);
            verify(id);
            expectedEarned = expectedEarned.add(new BigDecimal(overtime ? "892.86" : "625.00"));
        }

        assertThat(expectedEarned).isEqualByComparingTo("17142.88");
        assertThat(earnedFor(KARAM)).isEqualByComparingTo(expectedEarned);

        BigDecimal advance = new BigDecimal("9594.00");
        approveAdvance(issueAdvance(KARAM, advance, LocalDate.of(2025, 7, 20)));

        Map<String, Object> balance = balanceFor(KARAM);
        assertThat((BigDecimal) balance.get("earned")).isEqualByComparingTo(expectedEarned);
        assertThat((BigDecimal) balance.get("advance")).isEqualByComparingTo(advance);
        assertThat((BigDecimal) balance.get("netPayable"))
                .as("earned minus advance, exactly as the sheet has always computed it")
                .isEqualByComparingTo(expectedEarned.subtract(advance));

        // The same figures, read back through the settlement endpoint a payday argument
        // would actually be conducted over.
        MvcResult settlement = mockMvc.perform(get("/api/v1/workers/" + KARAM + "/settlement")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(settlement.getResponse().getContentAsString());
        assertThat(body.get("earnedAmount").decimalValue()).isEqualByComparingTo(expectedEarned);
        assertThat(body.get("advanceAmount").decimalValue()).isEqualByComparingTo(advance);
        assertThat(body.get("netPayable").decimalValue())
                .isEqualByComparingTo(expectedEarned.subtract(advance));
        assertThat(body.get("entries").size())
                .as("24 wage lines, 8 overtime lines and the advance")
                .isEqualTo(33);
    }

    // ------------------------------------------------------------------ advances

    @Test
    @DisplayName("an advance reaches the ledger only once approved, and only if recoverable")
    void advancePostsOnApprovalAndOnlyWhenRecoverable() throws Exception {
        // Prakash is posted to the annexe, so he is untouched by every other test here.
        String prakash = "60000000-0000-0000-0000-000000000106";
        String annexe = "31000000-0000-0000-0000-000000000002";

        UUID recoverable = UUID.randomUUID();
        issueAdvanceAt(recoverable, annexe, prakash, new BigDecimal("2000.00"),
                LocalDate.of(2025, 9, 3), true);

        assertThat(advanceAmountFor(prakash))
                .as("recorded but not yet approved, so nothing is deducted")
                .isEqualByComparingTo("0.00");

        approveAdvance(recoverable);
        assertThat(advanceAmountFor(prakash)).isEqualByComparingTo("2000.00");

        // Footwear the contractor has decided to bear: handed over, recorded, not deducted.
        UUID borne = UUID.randomUUID();
        issueAdvanceAt(borne, annexe, prakash, new BigDecimal("800.00"),
                LocalDate.of(2025, 9, 4), false);
        approveAdvance(borne);

        assertThat(advanceAmountFor(prakash))
                .as("a non-recoverable advance is recorded but never charged to the worker")
                .isEqualByComparingTo("2000.00");
    }

    @Test
    @DisplayName("advance numbers are server-assigned and sequential, never invented by a device")
    void advanceNumbersAreServerAssigned() throws Exception {
        String annexe = "31000000-0000-0000-0000-000000000002";
        String mahesh = "60000000-0000-0000-0000-000000000105";

        String first = issueAdvanceAt(UUID.randomUUID(), annexe, mahesh,
                new BigDecimal("500.00"), LocalDate.of(2025, 9, 10), true);
        String second = issueAdvanceAt(UUID.randomUUID(), annexe, mahesh,
                new BigDecimal("500.00"), LocalDate.of(2025, 9, 11), true);

        assertThat(first).matches("ADV-2025-\\d{4}");
        assertThat(second).isNotEqualTo(first);
        assertThat(numberOf(second)).isEqualTo(numberOf(first) + 1);
    }

    @Test
    @DisplayName("re-sending the same advance creates one row, not two")
    void advanceCreationIsIdempotent() throws Exception {
        String annexe = "31000000-0000-0000-0000-000000000002";
        String mahesh = "60000000-0000-0000-0000-000000000105";
        UUID id = UUID.randomUUID();

        String body = """
                {"id":"%s","siteId":"%s","workerId":"%s","advanceDate":"2025-09-20",
                 "amount":750.00,"paymentMode":"CASH","purpose":"Ration","recoverable":true}
                """.formatted(id, annexe, mahesh);

        mockMvc.perform(post("/api/v1/worker-advances")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/worker-advances")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM worker_advances WHERE id = ?::uuid", Integer.class, id.toString());
        assertThat(rows).isEqualTo(1);
    }

    // ------------------------------------------------------------------ corrections

    @Test
    @DisplayName("an engineer corrects a verified day and the ledger follows by the difference")
    void correctingAVerifiedRowAdjustsTheLedger() throws Exception {
        LocalDate day = LocalDate.of(2025, 7, 2);
        UUID id = UUID.randomUUID();
        // Naresh: 700 a day, overtime exactly 100 an hour.
        saveBulk(entry(id, NARESH, "PRESENT", null, null, 0, "7", null), day);
        submit(day);
        verify(id);

        BigDecimal earnedAfterVerify = earnedFor(NARESH);
        long version = attendanceRow(id).get("version").asLong();

        // He actually did nine: two hours of overtime at 100 an hour.
        correct(id, "9", version, "Gate register shows he left at seven.");

        JsonNode corrected = attendanceRow(id);
        assertThat(corrected.get("enteredHours").decimalValue()).isEqualByComparingTo("9.00");
        assertThat(corrected.get("overtimeHours").decimalValue()).isEqualByComparingTo("2.00");
        assertThat(corrected.get("workflowStatus").asText())
                .as("a correction amends the verified row rather than reopening it")
                .isEqualTo("VERIFIED");
        assertThat(earnedFor(NARESH))
                .as("the ledger moved by the 200 the correction added, not by a re-post")
                .isEqualByComparingTo(earnedAfterVerify.add(new BigDecimal("200.00")));
    }

    @Test
    @DisplayName("a correction downwards takes the money back off the ledger")
    void correctingDownwardsReducesTheLedger() throws Exception {
        LocalDate day = LocalDate.of(2025, 7, 3);
        UUID id = UUID.randomUUID();
        saveBulk(entry(id, NARESH, "PRESENT", null, null, 0, "9", "Extra pour"), day);
        submit(day);
        verify(id);

        BigDecimal earnedAfterVerify = earnedFor(NARESH);
        long version = attendanceRow(id).get("version").asLong();

        correct(id, "7", version, "Overtime was double counted.");

        assertThat(earnedFor(NARESH))
                .isEqualByComparingTo(earnedAfterVerify.subtract(new BigDecimal("200.00")));
    }

    @Test
    @DisplayName("a correction records what changed, from what, to what and why")
    void correctionLeavesATrail() throws Exception {
        LocalDate day = LocalDate.of(2025, 7, 4);
        UUID id = UUID.randomUUID();
        saveBulk(entry(id, NARESH, "PRESENT", null, null, 0, "7", null), day);
        submit(day);
        verify(id);
        correct(id, "9", attendanceRow(id).get("version").asLong(),
                "Gate register shows he left at seven.");

        List<Map<String, Object>> trail = jdbc.queryForList(
                "SELECT field_name, previous_value, new_value, correction_reason, approval_status "
                        + "FROM attendance_corrections WHERE attendance_id = ?", id);

        // One row per field that actually moved: the hours, and the reason the helper sets.
        assertThat(trail).extracting(row -> row.get("field_name"))
                .containsExactlyInAnyOrder("enteredHours", "overtimeReason");
        Map<String, Object> hours = trail.stream()
                .filter(row -> "enteredHours".equals(row.get("field_name")))
                .findFirst().orElseThrow();
        assertThat(hours).containsEntry("previous_value", "7.00")
                .containsEntry("new_value", "9")
                .containsEntry("correction_reason", "Gate register shows he left at seven.")
                .containsEntry("approval_status", "APPROVED");
    }

    /**
     * The rate was pinned to the row when it was verified. A correction moves the hours; it
     * must not quietly reprice them against a rise signed since.
     */
    @Test
    @DisplayName("a correction uses the frozen rate, not a wage revision signed since")
    void correctionDoesNotRepriceTheDay() throws Exception {
        LocalDate day = LocalDate.of(2025, 7, 7);
        UUID id = UUID.randomUUID();
        saveBulk(entry(id, NARESH, "PRESENT", null, null, 0, "7", null), day);
        submit(day);
        verify(id);
        BigDecimal earnedAfterVerify = earnedFor(NARESH);

        reviseWage(NARESH, "1400.0000", "200.0000", day.plusDays(1), "Doubled since");

        correct(id, "9", attendanceRow(id).get("version").asLong(), "Two hours over.");

        assertThat(attendanceRow(id).get("appliedOtRate").decimalValue())
                .as("still the rate the day was verified against")
                .isEqualByComparingTo("100.0000");
        assertThat(earnedFor(NARESH))
                .as("two hours at the frozen 100, not at the new 200")
                .isEqualByComparingTo(earnedAfterVerify.add(new BigDecimal("200.00")));
    }

    @Test
    @DisplayName("a supervisor cannot correct a verified row")
    void supervisorCannotCorrect() throws Exception {
        LocalDate day = LocalDate.of(2025, 7, 8);
        UUID id = UUID.randomUUID();
        saveBulk(entry(id, NARESH, "PRESENT", null, null, 0, "7", null), day);
        submit(day);
        verify(id);

        mockMvc.perform(post("/api/v1/attendance/" + id + "/correct")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("9", attendanceRow(id).get("version").asLong(),
                                "Trying it on")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a stale version loses the race rather than overwriting the other correction")
    void staleCorrectionIsRejected() throws Exception {
        LocalDate day = LocalDate.of(2025, 7, 9);
        UUID id = UUID.randomUUID();
        saveBulk(entry(id, NARESH, "PRESENT", null, null, 0, "7", null), day);
        submit(day);
        verify(id);

        long stale = attendanceRow(id).get("version").asLong();
        correct(id, "9", stale, "First correction wins.");

        mockMvc.perform(post("/api/v1/attendance/" + id + "/correct")
                        .header("Authorization", "Bearer " + engineerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("11", stale, "Second, working from stale data")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a row still awaiting verification is edited, not corrected")
    void submittedRowIsNotCorrectable() throws Exception {
        LocalDate day = LocalDate.of(2025, 7, 10);
        UUID id = UUID.randomUUID();
        saveBulk(entry(id, NARESH, "PRESENT", null, null, 0, "7", null), day);
        submit(day);

        MvcResult result = mockMvc.perform(post("/api/v1/attendance/" + id + "/correct")
                        .header("Authorization", "Bearer " + engineerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("9", attendanceRow(id).get("version").asLong(),
                                "Not a correction")))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("edit it directly instead");
    }

    // ------------------------------------------------------------------ period lock

    @Test
    @DisplayName("a locked month rejects new attendance, and a wage revision cannot alter it")
    void lockedMonthIsImmovable() throws Exception {
        LocalDate inAugust = LocalDate.of(2025, 8, 5);
        UUID id = UUID.randomUUID();
        saveBulk(entry(id, DINESH, "PRESENT", null, null, 0, null), inAugust);
        submit(inAugust);
        verify(id);

        BigDecimal earnedBeforeLock = earnedFor(DINESH);
        assertThat(earnedBeforeLock).isEqualByComparingTo("700.00");

        mockMvc.perform(post("/api/v1/attendance/lock")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"siteId\":\"" + SITE_A + "\",\"yearMonth\":\"2025-08\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lockedRecords").value(1));

        // Nothing further may be entered into that month.
        MvcResult blocked = mockMvc.perform(post("/api/v1/attendance/bulk")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulkBody(inAugust.plusDays(1),
                                entry(UUID.randomUUID(), DINESH, "PRESENT", null, null, 0, null))))
                .andReturn();
        assertThat(blocked.getResponse().getStatus())
                .as("a write into a closed month is refused")
                .isEqualTo(422);

        // And a pay rise backdated into the locked month cannot reprice what was settled.
        reviseWage(DINESH, "900.0000", "128.5714", LocalDate.of(2025, 8, 1), "Backdated rise");

        assertThat(earnedFor(DINESH))
                .as("the locked month's cost is exactly what it was before the revision")
                .isEqualByComparingTo(earnedBeforeLock);
        assertThat(attendanceRow(id).get("workflowStatus").asText()).isEqualTo("LOCKED");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode entryFor(JsonNode roster, String workerId) {
        for (JsonNode entry : roster.get("entries")) {
            if (entry.get("workerId").asText().equals(workerId)) {
                return entry;
            }
        }
        throw new AssertionError("worker " + workerId + " is not on the roster");
    }

    private String entry(UUID id, String workerId, String status, String checkIn, String checkOut,
                         int breakMinutes, String overtimeReason) {
        return entry(id, workerId, status, checkIn, checkOut, breakMinutes, null, overtimeReason);
    }

    private String entry(UUID id, String workerId, String status, String checkIn, String checkOut,
                         int breakMinutes, String enteredHours, String overtimeReason) {
        return """
                {"id":"%s","workerId":"%s","status":"%s",%s%s%s"breakMinutes":%d%s}
                """.formatted(id, workerId, status,
                checkIn == null ? "" : "\"checkInTime\":\"" + checkIn + "\",",
                checkOut == null ? "" : "\"checkOutTime\":\"" + checkOut + "\",",
                enteredHours == null ? "" : "\"enteredHours\":" + enteredHours + ",",
                breakMinutes,
                overtimeReason == null ? "" : ",\"overtimeReason\":\"" + overtimeReason + "\"");
    }

    private String bulkBody(LocalDate date, String... entries) {
        return """
                {"siteId":"%s","date":"%s","entries":[%s]}
                """.formatted(SITE_A, date, String.join(",", List.of(entries)));
    }

    private void saveBulk(String entry, LocalDate date) throws Exception {
        postBulk(bulkBody(date, entry));
    }

    private MvcResult postBulk(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/attendance/bulk")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
    }

    private void submit(LocalDate date) throws Exception {
        mockMvc.perform(post("/api/v1/attendance/submit")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"siteId\":\"" + SITE_A + "\",\"date\":\"" + date + "\"}"))
                .andExpect(status().isOk());
    }

    private void verify(UUID id) throws Exception {
        mockMvc.perform(post("/api/v1/attendance/verify")
                        .header("Authorization", "Bearer " + engineerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + id + "\"],\"action\":\"VERIFY\"}"))
                .andExpect(status().isOk());
    }

    private void correct(UUID id, String hours, long version, String reason) throws Exception {
        mockMvc.perform(post("/api/v1/attendance/" + id + "/correct")
                        .header("Authorization", "Bearer " + engineerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody(hours, version, reason)))
                .andExpect(status().isOk());
    }

    private String correctionBody(String hours, long version, String reason) {
        return """
                {"status":"PRESENT","breakMinutes":0,"enteredHours":%s,
                 "overtimeReason":"Corrected","correctionReason":"%s","version":%d}
                """.formatted(hours, reason, version);
    }

    private void reviseWage(String workerId, String normalRate, String overtimeRate,
                            LocalDate effectiveFrom, String remarks) throws Exception {
        mockMvc.perform(post("/api/v1/workers/" + workerId + "/wage-rates")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"normalRate":%s,"overtimeRate":%s,"effectiveFrom":"%s","remarks":"%s"}
                                """.formatted(normalRate, overtimeRate, effectiveFrom, remarks)))
                .andExpect(status().isCreated());
    }

    /** Records an advance through the real endpoint and returns its id. */
    private UUID issueAdvance(String workerId, BigDecimal amount, LocalDate on) throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/worker-advances")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","siteId":"%s","workerId":"%s","advanceDate":"%s",
                                 "amount":%s,"paymentMode":"CASH","purpose":"Cash advance",
                                 "recoverable":true}
                                """.formatted(id, SITE_A, workerId, on, amount)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.advanceNumber").exists());
        return id;
    }

    /** Full-control form of {@link #issueAdvance}; returns the server-assigned number. */
    private String issueAdvanceAt(UUID id, String siteId, String workerId, BigDecimal amount,
                                  LocalDate on, boolean recoverable) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/worker-advances")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","siteId":"%s","workerId":"%s","advanceDate":"%s",
                                 "amount":%s,"paymentMode":"CASH","purpose":"Advance",
                                 "recoverable":%b}
                                """.formatted(id, siteId, workerId, on, amount, recoverable)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("advanceNumber").asText();
    }

    private BigDecimal advanceAmountFor(String workerId) {
        List<BigDecimal> found = jdbc.queryForList(
                "SELECT advance_amount FROM worker_balances WHERE worker_id = ?::uuid",
                BigDecimal.class, workerId);
        return found.isEmpty() ? BigDecimal.ZERO : found.get(0);
    }

    private static int numberOf(String documentNumber) {
        return Integer.parseInt(documentNumber.substring(documentNumber.lastIndexOf('-') + 1));
    }

    private void approveAdvance(UUID id) throws Exception {
        mockMvc.perform(post("/api/v1/worker-advances/" + id + "/decision")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isOk());
    }

    private BigDecimal earnedFor(String workerId) {
        return jdbc.queryForObject(
                "SELECT coalesce(earned_amount, 0) FROM worker_balances WHERE worker_id = ?::uuid",
                BigDecimal.class, workerId);
    }

    private Map<String, Object> balanceFor(String workerId) {
        return jdbc.queryForMap("""
                SELECT earned_amount AS earned, advance_amount AS advance,
                       paid_amount AS paid, net_payable AS "netPayable"
                  FROM worker_balances WHERE worker_id = ?::uuid
                """, workerId);
    }

    private JsonNode attendanceRow(UUID id) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/attendance")
                        .param("siteId", SITE_A)
                        .param("from", "2024-01-01")
                        .param("to", "2030-12-31")
                        .param("size", "500")
                        .header("Authorization", "Bearer " + engineerToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("content");
        for (JsonNode row : content) {
            if (row.get("id").asText().equals(id.toString())) {
                return row;
            }
        }
        throw new AssertionError("attendance " + id + " not found");
    }

    private String token(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Nirman@123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}

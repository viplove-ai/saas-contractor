package in.nirman.modules.payroll;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The month's payroll: drawn, corrected, finalised once.
 *
 * <p>The properties worth defending here are not the arithmetic — that is
 * {@link StatutoryContributionsTest}'s job — but the shape of the month: that a member with
 * no structure is <em>named</em> rather than silently absent, that a redraw keeps what the
 * office typed, that a finalised run refuses everything, and that a payslip's own totals can
 * never disagree with the lines above them.</p>
 */
class PayrollIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    /** A month safely in the past, so nothing here depends on what day the suite runs. */
    private static final LocalDate MONTH = LocalDate.of(2026, 5, 1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clearPayroll() {
        jdbc.update("DELETE FROM payslips");
        jdbc.update("DELETE FROM payroll_runs");
        String made = "SELECT id FROM users WHERE username LIKE 'pay.%'";
        jdbc.update("DELETE FROM staff_salary_revisions WHERE user_id IN (" + made + ")");
        jdbc.update("DELETE FROM staff_profiles WHERE user_id IN (" + made + ")");
    }

    @Test
    @DisplayName("a month is drawn from the structure, and the slip's totals add up")
    void aMonthIsDrawnFromTheStructure() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "pay.drawn");
        // Basic 15,000 and other allowances 3,000 — the payslip in the specification.
        saveRecord(admin, userId, true, true);
        recordStructure(admin, userId, "15000", "0", "0", "0", "3000");

        JsonNode run = openRun(admin, 26);
        JsonNode slip = slipFor(run, userId);

        assertThat(new BigDecimal(slip.get("structGross").asText()))
                .isEqualByComparingTo("18000");
        assertThat(new BigDecimal(slip.get("totalEarnings").asText()))
                .isEqualByComparingTo("18000");
        assertThat(new BigDecimal(slip.get("pfEmployee").asText()))
                .isEqualByComparingTo("1800");
        assertThat(new BigDecimal(slip.get("esiEmployee").asText()))
                .isEqualByComparingTo("135");
        // The firm deducts no professional tax, so the two statutory contributions are the
        // whole of what comes off a slip nobody has typed a figure on.
        assertThat(new BigDecimal(slip.get("professionalTax").asText()))
                .isEqualByComparingTo("0");
        assertThat(new BigDecimal(slip.get("totalDeductions").asText()))
                .isEqualByComparingTo("1935");
        assertThat(new BigDecimal(slip.get("netAmount").asText()))
                .isEqualByComparingTo("16065");
        // The employer's own contributions are outside the net, because they were never his.
        assertThat(new BigDecimal(slip.get("employerCost").asText()))
                .isGreaterThan(new BigDecimal("18000"));
    }

    /**
     * The rule that stops a payroll looking complete when it is not. A member the run could
     * not draw is named on the response with a sentence saying what to do about him.
     */
    @Test
    @DisplayName("a member with no structure is named, not silently left out")
    void aMemberWithNoStructureIsNamed() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "pay.nostructure");
        saveRecord(admin, userId, true, false);

        JsonNode run = openRun(admin, 30);
        JsonNode notDrawn = run.get("notDrawn");

        boolean named = false;
        for (JsonNode row : notDrawn) {
            if (row.get("userId").asText().equals(userId)) {
                named = true;
                assertThat(row.get("reason").asText()).contains("No salary");
            }
        }
        assertThat(named).isTrue();
        assertThat(slipForOrNull(run, userId)).isNull();
    }

    /**
     * The reason a redraw is safe to offer at all. An office that lost its typed figures every
     * time somebody corrected a basic pay would stop correcting basic pay.
     */
    @Test
    @DisplayName("redrawing rebuilds the structure and keeps what the office typed")
    void redrawingKeepsTheTypedFigures() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "pay.redraw");
        saveRecord(admin, userId, true, false);
        recordStructure(admin, userId, "20000", "0", "8000", "2000", "0");

        JsonNode run = openRun(admin, 30);
        JsonNode slip = slipFor(run, userId);
        // The office types the days and the tax office's share.
        updateSlip(admin, slip.get("id").asText(), """
                {"paidDays":24,"tds":1500,"salaryAdvance":2000,
                 "version":%d}""".formatted(slip.get("version").asLong()));

        // A raise effective inside the month lands afterwards, and it is drawn again. The
        // structure in force on the last day of the month is the one a month is paid on.
        recordStructure(admin, userId, "22000", "0", "8800", "2200", "0", "2026-05-01");
        MvcResult result = mockMvc.perform(post("/api/v1/payroll/runs/"
                        + run.get("id").asText() + "/redraw")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode redrawn = slipFor(read(result), userId);

        // The structure moved...
        assertThat(new BigDecimal(redrawn.get("structGross").asText()))
                .isEqualByComparingTo("33000");
        // ...and the typed figures survived it.
        assertThat(new BigDecimal(redrawn.get("paidDays").asText()))
                .isEqualByComparingTo("24");
        assertThat(new BigDecimal(redrawn.get("tds").asText())).isEqualByComparingTo("1500");
        assertThat(new BigDecimal(redrawn.get("salaryAdvance").asText()))
                .isEqualByComparingTo("2000");
        // The earnings were re-prorated against the new structure rather than left stale.
        assertThat(new BigDecimal(redrawn.get("totalEarnings").asText()))
                .isEqualByComparingTo("26400.00");
    }

    /**
     * Overtime is the one typed figure that also has a rule behind it, so the promise that a
     * redraw keeps what the office typed has to be true of it as well — otherwise it is true
     * of four fields and false of the fifth, which is the kind of exception nobody remembers
     * until a month goes out wrong.
     */
    @Test
    @DisplayName("hours are re-priced by a redraw; an amount the office typed is not")
    void aTypedOvertimeAmountSurvivesARedraw() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "pay.overtime");
        saveRecord(admin, userId, false, false);
        // 15,600 over 26 days of 8 hours is 75 an hour; twice that is 150.
        recordStructure(admin, userId, "15600", "0", "0", "0", "0");

        JsonNode run = openRun(admin, 26);
        JsonNode slip = slipFor(run, userId);
        updateSlip(admin, slip.get("id").asText(),
                "{\"paidDays\":26,\"overtimeHours\":4,\"version\":%d}"
                        .formatted(slip.get("version").asLong()));

        JsonNode priced = slipFor(fetch(admin, run.get("id").asText()), userId);
        assertThat(new BigDecimal(priced.get("overtimeAmount").asText()))
                .isEqualByComparingTo("600.00");

        // The office has agreed a flat 1,000 instead.
        updateSlip(admin, priced.get("id").asText(),
                "{\"paidDays\":26,\"overtimeHours\":4,\"overtimeAmount\":1000,\"version\":%d}"
                        .formatted(priced.get("version").asLong()));

        mockMvc.perform(post("/api/v1/payroll/runs/" + run.get("id").asText() + "/redraw")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        JsonNode redrawn = slipFor(fetch(admin, run.get("id").asText()), userId);
        assertThat(new BigDecimal(redrawn.get("overtimeAmount").asText()))
                .isEqualByComparingTo("1000.00");
        // And clearing the box hands it back to the rule rather than leaving it stuck.
        updateSlip(admin, redrawn.get("id").asText(),
                "{\"paidDays\":26,\"overtimeHours\":4,\"version\":%d}"
                        .formatted(redrawn.get("version").asLong()));
        assertThat(new BigDecimal(
                slipFor(fetch(admin, run.get("id").asText()), userId).get("overtimeAmount").asText()))
                .isEqualByComparingTo("600.00");
    }

    @Test
    @DisplayName("a finalised month refuses every further change")
    void aFinalisedMonthIsClosed() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "pay.closed");
        saveRecord(admin, userId, true, false);
        recordStructure(admin, userId, "20000", "0", "0", "0", "0");

        JsonNode run = openRun(admin, 30);
        String runId = run.get("id").asText();
        String slipId = slipFor(run, userId).get("id").asText();

        mockMvc.perform(post("/api/v1/payroll/runs/" + runId + "/finalise")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINALISED"))
                .andExpect(jsonPath("$.finalisedAt").isNotEmpty());

        mockMvc.perform(put("/api/v1/payroll/payslips/" + slipId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paidDays\":10,\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("finalised")));

        mockMvc.perform(delete("/api/v1/payroll/runs/" + runId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("two payrolls for one month are refused")
    void oneRunAMonth() throws Exception {
        String admin = login("viplove");
        openRun(admin, 30);
        mockMvc.perform(post("/api/v1/payroll/runs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periodMonth\":\"" + MONTH + "\",\"payableDays\":30}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("already been drawn")));
    }

    @Test
    @DisplayName("more days paid than the month is being paid against is refused")
    void daysCannotExceedTheMonth() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "pay.days");
        saveRecord(admin, userId, false, false);
        recordStructure(admin, userId, "20000", "0", "0", "0", "0");

        JsonNode slip = slipFor(openRun(admin, 26), userId);
        mockMvc.perform(put("/api/v1/payroll/payslips/" + slip.get("id").asText())
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paidDays\":30,\"version\":%d}"
                                .formatted(slip.get("version").asLong())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("overtime")));
    }

    @Test
    @DisplayName("a deduction with no reason beside it is refused")
    void otherDeductionsNeedAReason() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "pay.why");
        saveRecord(admin, userId, false, false);
        recordStructure(admin, userId, "20000", "0", "0", "0", "0");

        JsonNode slip = slipFor(openRun(admin, 26), userId);
        mockMvc.perform(put("/api/v1/payroll/payslips/" + slip.get("id").asText())
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paidDays\":26,\"otherDeduction\":500,\"version\":%d}"
                                .formatted(slip.get("version").asLong())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("what it is for")));
    }

    /** The accountant is who this feature is for; the permission grant is worth asserting. */
    @Test
    @DisplayName("the accountant can run the month")
    void theAccountantCanRunTheMonth() throws Exception {
        String accountant = login("uttam");
        mockMvc.perform(get("/api/v1/payroll/runs")
                        .header("Authorization", "Bearer " + accountant))
                .andExpect(status().isOk());
        openRun(accountant, 30);
    }

    /** A supervisor holds neither permission and must not see what anybody is paid. */
    @Test
    @DisplayName("a supervisor cannot read the payroll")
    void aSupervisorIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/payroll/runs")
                        .header("Authorization", "Bearer " + login("vivek")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the register and the payslip both render as PDFs")
    void theDocumentsRender() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "pay.print");
        saveRecord(admin, userId, true, true);
        recordStructure(admin, userId, "15000", "0", "0", "0", "3000");

        JsonNode run = openRun(admin, 26);
        byte[] register = mockMvc.perform(get("/api/v1/payroll/runs/"
                        + run.get("id").asText() + "/register.pdf")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(register, 0, 5)).startsWith("%PDF-");

        byte[] slip = mockMvc.perform(get("/api/v1/payroll/payslips/"
                        + slipFor(run, userId).get("id").asText() + "/pdf")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(slip, 0, 5)).startsWith("%PDF-");

        // Neither document names a deduction the firm does not make. Both keep the ability to
        // print one — a month drawn while professional tax was still deducted has to go on
        // adding up — and neither exercises it, because nothing can put a figure there now.
        assertThat(textOf(register)).doesNotContain("Prof.");
        assertThat(textOf(slip)).doesNotContain("Professional tax");
    }

    /** The rendered document as one run of text, so a test can read what an office reads. */
    private String textOf(byte[] pdf) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument document =
                     org.apache.pdfbox.pdmodel.PDDocument.load(pdf)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(document)
                    .replaceAll("\\s+", " ");
        }
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode openRun(String token, int payableDays) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/payroll/runs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periodMonth\":\"%s\",\"payableDays\":%d}"
                                .formatted(MONTH, payableDays)))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result);
    }

    private JsonNode fetch(String token, String runId) throws Exception {
        return read(mockMvc.perform(get("/api/v1/payroll/runs/" + runId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
    }

    private void updateSlip(String token, String slipId, String body) throws Exception {
        mockMvc.perform(put("/api/v1/payroll/payslips/" + slipId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private void saveRecord(String token, String userId, boolean pf, boolean esi)
            throws Exception {
        mockMvc.perform(put("/api/v1/staff/" + userId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"employmentType":"PERMANENT","joinedOn":"2024-04-01",
                                 "designation":"Site Engineer","pfApplicable":%s,
                                 "esiApplicable":%s,"pfOnFullWages":false}"""
                                .formatted(pf, esi)))
                .andExpect(status().isOk());
    }

    private void recordStructure(String token, String userId, String basic, String da,
                                 String hra, String conveyance, String other)
            throws Exception {
        recordStructure(token, userId, basic, da, hra, conveyance, other, "2024-04-01");
    }

    private void recordStructure(String token, String userId, String basic, String da,
                                 String hra, String conveyance, String other,
                                 String from) throws Exception {
        mockMvc.perform(post("/api/v1/staff/" + userId + "/salary")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"basic":%s,"dearnessAllowance":%s,"hra":%s,"conveyance":%s,
                                 "otherAllowance":%s,
                                 "effectiveFrom":"%s","reason":"Terms agreed"}"""
                                .formatted(basic, da, hra, conveyance, other, from)))
                .andExpect(status().isCreated());
    }

    private JsonNode slipFor(JsonNode run, String userId) {
        JsonNode slip = slipForOrNull(run, userId);
        assertThat(slip).as("a payslip for " + userId).isNotNull();
        return slip;
    }

    private JsonNode slipForOrNull(JsonNode run, String userId) {
        for (JsonNode slip : run.get("payslips")) {
            if (slip.get("userId").asText().equals(userId)) {
                return slip;
            }
        }
        return null;
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
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
        return read(result).get("id").asText();
    }

    private String login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}"
                                .formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).get("accessToken").asText();
    }
}

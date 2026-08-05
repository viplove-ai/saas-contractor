package in.nirman.modules.labour;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.AbstractIntegrationTest;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two labour reports. Worked on the annexe (KSN-B) and its two workers so this test
 * cannot collide with the attendance workflow suite, which owns the main block.
 */
class LabourReportIntegrationTest extends AbstractIntegrationTest {

    private static final String ANNEXE = "31000000-0000-0000-0000-000000000002";
    private static final String MAHESH = "60000000-0000-0000-0000-000000000105";   // 450/day
    private static final String PRAKASH = "60000000-0000-0000-0000-000000000106";  // 625/day

    private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
    private static final LocalDate TO = LocalDate.of(2026, 3, 31);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String adminToken;
    private String engineerToken;
    private String supervisorToken;

    @BeforeEach
    void seedAMonthOfWork() throws Exception {
        adminToken = token("viplove");
        engineerToken = token("uttam");
        supervisorToken = token("vivek");   // KSN-A only, so has no business here at all

        if (alreadySeeded()) {
            return;
        }
        // Three plain days each, plus one day where Prakash runs three hours over.
        for (int day = 2; day <= 4; day++) {
            LocalDate date = LocalDate.of(2026, 3, day);
            List<String> entries = new ArrayList<>();
            List<UUID> ids = new ArrayList<>();
            for (String worker : List.of(MAHESH, PRAKASH)) {
                UUID id = UUID.randomUUID();
                ids.add(id);
                boolean overtime = worker.equals(PRAKASH) && day == 4;
                entries.add("""
                        {"id":"%s","workerId":"%s","status":"PRESENT",%s"breakMinutes":0%s}
                        """.formatted(id, worker,
                        overtime ? "\"checkInTime\":\"08:00:00\",\"checkOutTime\":\"18:00:00\"," : "",
                        overtime ? ",\"overtimeReason\":\"Plaster\"" : ""));
            }
            mockMvc.perform(post("/api/v1/attendance/bulk")
                            .header("Authorization", "Bearer " + engineerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"siteId":"%s","date":"%s","entries":[%s]}
                                    """.formatted(ANNEXE, date, String.join(",", entries))))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/attendance/submit")
                            .header("Authorization", "Bearer " + engineerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"siteId\":\"%s\",\"date\":\"%s\"}".formatted(ANNEXE, date)))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/attendance/verify")
                            .header("Authorization", "Bearer " + engineerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"ids\":[%s],\"action\":\"VERIFY\"}".formatted(
                                    ids.stream().map(id -> "\"" + id + "\"").reduce((a, b) -> a + "," + b).orElse(""))))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("the register is a worker-by-day grid of single letters, and carries no money")
    void registerIsAGrid() throws Exception {
        JsonNode report = json("/api/v1/reports/attendance-register", engineerToken);

        assertThat(report.get("days").size()).as("one column per day of March").isEqualTo(31);
        assertThat(report.get("rows").size()).isEqualTo(2);

        JsonNode prakash = rowFor(report, PRAKASH);
        assertThat(prakash.get("presentDays").asInt()).isEqualTo(3);
        assertThat(prakash.get("marks").get("2026-03-02").asText()).isEqualTo("P");
        assertThat(prakash.get("overtimeHours").decimalValue()).isEqualByComparingTo("3.00");

        // The register is the version a field role may see, so it must not leak wages.
        assertThat(report.toString())
                .as("no money anywhere in the operational report")
                .doesNotContain("wageAmount").doesNotContain("netPayable");
    }

    @Test
    @DisplayName("the wage summary reconciles earned, drawn and payable per worker")
    void wageSummaryReconciles() throws Exception {
        JsonNode report = json("/api/v1/reports/wage-summary", adminToken);

        JsonNode mahesh = rowFor(report, MAHESH);
        assertThat(mahesh.get("totalEarned").decimalValue())
                .as("three days at 450")
                .isEqualByComparingTo("1350.00");

        JsonNode prakash = rowFor(report, PRAKASH);
        // 3 x 625 wage, plus 3 overtime hours at 89.2857 on the last day.
        assertThat(prakash.get("wageAmount").decimalValue()).isEqualByComparingTo("1875.00");
        assertThat(prakash.get("overtimeAmount").decimalValue()).isEqualByComparingTo("267.86");
        assertThat(prakash.get("totalEarned").decimalValue()).isEqualByComparingTo("2142.86");

        JsonNode totals = report.get("totals");
        assertThat(totals.get("workers").asInt()).isEqualTo(2);
        assertThat(totals.get("totalEarned").decimalValue()).isEqualByComparingTo("3492.86");
        assertThat(totals.get("netPayable").decimalValue())
                .as("nothing drawn in the period, so payable equals earned")
                .isEqualByComparingTo("3492.86");
    }

    /**
     * Deliberately run against KSN-A, the site Vivek <em>is</em> assigned to. Asking him for
     * the annexe would fail for two reasons at once and prove neither: this way the site
     * fence is out of the picture and the only thing that can refuse him is the missing
     * {@code report:financial} permission.
     */
    @Test
    @DisplayName("a supervisor may run the register for his own site but never its payroll")
    void permissionMatrixSplitsTheTwoReports() throws Exception {
        String ownSite = "31000000-0000-0000-0000-000000000001";

        mockMvc.perform(get("/api/v1/reports/attendance-register")
                        .param("siteId", ownSite).param("from", FROM.toString()).param("to", TO.toString())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/reports/wage-summary")
                        .param("siteId", ownSite).param("from", FROM.toString()).param("to", TO.toString())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the site fence still applies to a report the caller is otherwise entitled to run")
    void siteScopeStillAppliesToReports() throws Exception {
        mockMvc.perform(get("/api/v1/reports/attendance-register")
                        .param("siteId", ANNEXE).param("from", FROM.toString()).param("to", TO.toString())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("xlsx export returns a real workbook with the totals row")
    void exportsASpreadsheet() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/reports/wage-summary")
                        .param("siteId", ANNEXE).param("from", FROM.toString()).param("to", TO.toString())
                        .param("format", "xlsx")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".xlsx")))
                .andReturn();

        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes.length).isGreaterThan(0);

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("Wage summary");
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).contains("Annexe");

            Row lastRow = sheet.getRow(sheet.getLastRowNum());
            assertThat(lastRow.getCell(1).getStringCellValue()).isEqualTo("TOTAL");
            assertThat(lastRow.getCell(8).getNumericCellValue()).isEqualTo(3492.86);
        }
    }

    @Test
    @DisplayName("an unbounded date range is refused rather than timing out")
    void refusesAnAbsurdRange() throws Exception {
        mockMvc.perform(get("/api/v1/reports/attendance-register")
                        .param("siteId", ANNEXE).param("from", "2020-01-01").param("to", "2030-01-01")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity());
    }

    // ------------------------------------------------------------------ helpers

    private boolean alreadySeeded() throws Exception {
        return json("/api/v1/reports/attendance-register", adminToken).get("rows").size() > 0;
    }

    private JsonNode json(String path, String bearer) throws Exception {
        MvcResult result = mockMvc.perform(get(path)
                        .param("siteId", ANNEXE)
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode rowFor(JsonNode report, String workerId) {
        for (JsonNode row : report.get("rows")) {
            if (row.get("workerId").asText().equals(workerId)) {
                return row;
            }
        }
        throw new AssertionError("worker " + workerId + " is not in the report");
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

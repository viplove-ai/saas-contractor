package in.nirman.modules.billing;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A running account bill, end to end: measure, sign, sweep, pass — and what each step refuses.
 *
 * <p>Three properties carry the weight, and every test below is one of them.</p>
 *
 * <p><b>A sheet is signed only when its two totals agree.</b> The engineer works out a total by
 * hand at the foot of the paper and the system multiplies the rows; signing while they differ is
 * refused. That one number is what catches a typing slip, a skipped row and a transposed
 * dimension, and it is the entire reason the printed sheet is worth copying faithfully.</p>
 *
 * <p><b>A quantity cannot be paid twice.</b> A sheet carries the id of the one bill that claimed
 * it, so the second bill cannot see it at all — enforced by the column rather than by a rule
 * somebody has to keep remembering.</p>
 *
 * <p><b>A passed bill is what was paid.</b> Its figures are a snapshot written at the moment it
 * was passed, so measuring more work afterwards moves the next bill and never this one.</p>
 */
class RaBillWorkflowIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String PROJECT = "30000000-0000-0000-0000-000000000001";
    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";
    /** RCC M25 in columns — 18 cum at 8200.00, seeded by V901. */
    private static final String BOQ_COLUMNS = "70000000-0000-0000-0000-000000000001";
    /** RCC M25 in beams and slab — 22 cum at 7900.00. */
    private static final String BOQ_BEAMS = "70000000-0000-0000-0000-000000000002";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Projects this test made, so it can take them away again.
     *
     * <p>The suite shares one database. A site left behind here is a site every other test's
     * "how many sites can this user see" assertion counts — which is precisely how a green
     * feature turns four unrelated suites red.</p>
     */
    private final List<String> createdProjects = new ArrayList<>();

    @AfterEach
    void removeWhatThisTestCreated() {
        for (String projectId : createdProjects) {
            jdbc.update("""
                    DELETE FROM measurement_lines WHERE sheet_id IN
                        (SELECT id FROM measurement_sheets WHERE project_id = ?::uuid)
                    """, projectId);
            jdbc.update("DELETE FROM measurement_sheets WHERE project_id = ?::uuid", projectId);
            jdbc.update("""
                    DELETE FROM ra_bill_items WHERE ra_bill_id IN
                        (SELECT id FROM ra_bills WHERE project_id = ?::uuid)
                    """, projectId);
            jdbc.update("DELETE FROM ra_bills WHERE project_id = ?::uuid", projectId);
            jdbc.update("DELETE FROM agreements WHERE project_id = ?::uuid", projectId);
            jdbc.update("DELETE FROM boq_items WHERE project_id = ?::uuid", projectId);
            jdbc.update("""
                    DELETE FROM user_site_assignments WHERE site_id IN
                        (SELECT id FROM sites WHERE project_id = ?::uuid)
                    """, projectId);
            jdbc.update("DELETE FROM stores WHERE site_id IN "
                    + "(SELECT id FROM sites WHERE project_id = ?::uuid)", projectId);
            jdbc.update("DELETE FROM sites WHERE project_id = ?::uuid", projectId);
            jdbc.update("DELETE FROM projects WHERE id = ?::uuid", projectId);
        }
        createdProjects.clear();
        // The vault is org-scoped, not project-scoped, so its rows outlive the projects
        // above and would pile up across runs. Same rule as the sites: leave nothing behind.
        jdbc.update("DELETE FROM agreement_documents WHERE document_id IN "
                + "(SELECT id FROM reference_documents WHERE code LIKE 'DSR-%' "
                + "OR code LIKE 'CI-%')");
        jdbc.update("UPDATE reference_documents SET supersedes_id = NULL "
                + "WHERE code LIKE 'DSR-%' OR code LIKE 'CI-%'");
        jdbc.update("DELETE FROM reference_documents "
                + "WHERE code LIKE 'DSR-%' OR code LIKE 'CI-%'");
    }

    // ---------------------------------------------------------------- the checksum

    /**
     * The whole argument for asking him to copy his own total across.
     *
     * <p>Two rows that come to 3.61, a written total of 12.40, and a signature that is refused
     * with the difference named. Then the same sheet with the honest total signs.</p>
     */
    @Test
    @DisplayName("a sheet whose written and computed totals disagree cannot be signed")
    void checksumBlocksSigning() throws Exception {
        String token = loginToken("uttam");
        String projectId = billingProject(token);

        // 1 x 1 x 6.22 x 5.80 x 0.10 = 3.61, and 1 x 1 x 4.50 x 0.57 x 0.07 = 0.18
        String sheetId = createSheet(token, projectId, BOQ_COLUMNS, "MB-0001", "12.40", """
                [{"location":"GD Room","nos":1,"mult":1,"length":6.22,"breadth":5.80,"height":0.10},
                 {"location":"GD Room","nos":1,"mult":1,"length":4.50,"breadth":0.57,"height":0.07}]
                """);

        JsonNode sheet = getSheet(token, sheetId);
        assertThat(decimal(sheet, "computedTotal")).isEqualByComparingTo("3.79");
        assertThat(sheet.get("totalsAgree").asBoolean()).isFalse();

        mockMvc.perform(post("/api/v1/measurement-sheets/{id}/sign", sheetId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://nirman/errors/billing.totals-disagree"));

        // Correct the written total to what the rows actually say, and it signs.
        updateWrittenTotal(token, sheetId, "3.79");
        mockMvc.perform(post("/api/v1/measurement-sheets/{id}/sign", sheetId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNED"));
    }

    /**
     * Every measurement shape the real bill uses, through one endpoint.
     *
     * <p>Volume, area, linear and count are the same formula with the unused dimensions left
     * null — and null has to mean "the question does not arise", not zero, or a linear item
     * would measure nothing at all.</p>
     */
    @Test
    @DisplayName("volume, area, linear and count all multiply only the dimensions given")
    void everyMeasurementShapeComputes() throws Exception {
        String token = loginToken("uttam");
        String projectId = billingProject(token);

        String sheetId = createSheet(token, projectId, BOQ_COLUMNS, "MB-0002", null, """
                [{"location":"volume","nos":1,"mult":1,"length":6.22,"breadth":5.80,"height":0.10},
                 {"location":"area","nos":1,"mult":1,"length":4.50,"breadth":0.57},
                 {"location":"linear","nos":1,"mult":9,"length":1.56},
                 {"location":"count","nos":3,"mult":2},
                 {"location":"deduction","nos":1,"mult":2,"length":1.20,"breadth":2.10,"deduction":true}]
                """);

        JsonNode lines = getSheet(token, sheetId).get("lines");
        assertThat(decimal(lines.get(0), "contents")).isEqualByComparingTo("3.61");
        assertThat(decimal(lines.get(1), "contents")).isEqualByComparingTo("2.57");
        assertThat(decimal(lines.get(2), "contents")).isEqualByComparingTo("14.04");
        assertThat(decimal(lines.get(3), "contents")).isEqualByComparingTo("6.00");
        // A deduction is the same arithmetic, signed negative.
        assertThat(decimal(lines.get(4), "contents")).isEqualByComparingTo("-5.04");

        // 3.61 + 2.57 + 14.04 + 6.00 - 5.04
        assertThat(decimal(getSheet(token, sheetId), "computedTotal")).isEqualByComparingTo("21.18");
    }

    /**
     * A section item measures a length and is paid by weight, so the sheet's claim is its rows
     * times a tested kg/m — and the rows stay a length, because that is what he measured.
     */
    @Test
    @DisplayName("a sheet with a unit weight claims length times kg/m, not the length")
    void unitWeightSheetClaimsWeight() throws Exception {
        String token = loginToken("uttam");
        String projectId = billingProject(token);

        String sheetId = createSheetWithUnitWeight(token, projectId, BOQ_COLUMNS, "MB-0003",
                "9.56", """
                [{"location":"Glass House","nos":1,"mult":9,"length":1.56},
                 {"location":"Glass House","nos":1,"mult":1,"length":1.20}]
                """);

        JsonNode sheet = getSheet(token, sheetId);
        // 14.04 + 1.20 metres of section
        assertThat(decimal(sheet, "computedTotal")).isEqualByComparingTo("15.24");
        // times 9.56 kg/m
        assertThat(decimal(sheet, "claimedQuantity")).isEqualByComparingTo("145.6944");
    }

    // ---------------------------------------------------------------- the series

    /**
     * The property the whole feature exists for: nobody ever types a previous-bill figure again.
     *
     * <p>Bill one claims 3.61 cum. Bill two claims 2.00 more. Bill two's "since previous" is its
     * own sheets, its "up to date" is both, and its "amount of previous bill" came off bill one's
     * frozen snapshot rather than off somebody's printout.</p>
     */
    @Test
    @DisplayName("the second bill carries the first forward without anybody retyping it")
    void billSeriesCarriesForward() throws Exception {
        String token = loginToken("uttam");
        String projectId = billingProject(token);
        saveAgreement(token, projectId);

        signedSheet(token, projectId, BOQ_COLUMNS, "MB-1001", "3.61", """
                [{"location":"GD Room","nos":1,"mult":1,"length":6.22,"breadth":5.80,"height":0.10}]
                """);

        String firstBill = createBill(token, projectId, "2026-06-30");
        JsonNode first = getBill(token, firstBill);
        assertThat(first.get("items")).hasSize(1);
        assertThat(decimal(first.get("items").get(0), "qtySincePrevious")).isEqualByComparingTo("3.61");
        assertThat(decimal(first.get("items").get(0), "qtyToDate")).isEqualByComparingTo("3.61");
        assertThat(decimal(first.get("items").get(0), "amountPrevious")).isEqualByComparingTo("0.00");
        // 3.61 cum at 8200.00
        assertThat(decimal(first.get("items").get(0), "amountSince")).isEqualByComparingTo("29602.00");

        passBill(token, firstBill);

        // More work, and a second bill.
        signedSheet(token, projectId, BOQ_COLUMNS, "MB-1002", "2.00", """
                [{"location":"GD Room","nos":1,"mult":1,"length":2.00,"breadth":1.00,"height":1.00}]
                """);
        String secondBill = createBill(token, projectId, "2026-07-31");
        JsonNode second = getBill(token, secondBill);
        JsonNode row = second.get("items").get(0);

        assertThat(decimal(row, "qtySincePrevious")).isEqualByComparingTo("2.00");
        assertThat(decimal(row, "qtyToDate")).isEqualByComparingTo("5.61");
        assertThat(decimal(row, "amountPrevious")).isEqualByComparingTo("29602.00");
        assertThat(decimal(row, "amountSince")).isEqualByComparingTo("16400.00");
        assertThat(decimal(row, "amountToDate")).isEqualByComparingTo("46002.00");
        assertThat(second.get("serialNo").asInt()).isEqualTo(2);
        assertThat(second.get("title").asText()).isEqualTo("2nd RA Bill");
    }

    /**
     * The double-payment guard, stated as the schema states it: a sheet holds one bill id, so a
     * second bill cannot see it.
     */
    @Test
    @DisplayName("a sheet already on a bill is never swept into the next one")
    void billedSheetIsNeverSweptTwice() throws Exception {
        String token = loginToken("uttam");
        String projectId = billingProject(token);
        saveAgreement(token, projectId);

        String sheetId = signedSheet(token, projectId, BOQ_BEAMS, "MB-2001", "4.00", """
                [{"location":"Slab","nos":1,"mult":1,"length":4.00,"breadth":1.00,"height":1.00}]
                """);
        String firstBill = createBill(token, projectId, "2026-06-30");
        passBill(token, firstBill);

        assertThat(jdbc.queryForObject(
                "SELECT ra_bill_id FROM measurement_sheets WHERE id = ?::uuid", UUID.class, sheetId))
                .isEqualTo(UUID.fromString(firstBill));

        // Nothing left unbilled, so the next bill has nothing to claim and says so.
        signedSheet(token, projectId, BOQ_BEAMS, "MB-2002", "1.00", """
                [{"location":"Slab","nos":1,"mult":1,"length":1.00,"breadth":1.00,"height":1.00}]
                """);
        String secondBill = createBill(token, projectId, "2026-07-31");
        JsonNode second = getBill(token, secondBill);
        // Only the new sheet, never the one the first bill paid.
        assertThat(decimal(second.get("items").get(0), "qtySincePrevious")).isEqualByComparingTo("1.00");
    }

    /**
     * A passed bill is a record of a payment, so work measured afterwards has to leave it alone.
     */
    @Test
    @DisplayName("measuring more work after a bill is passed does not move that bill")
    void passedBillIsFrozen() throws Exception {
        String token = loginToken("uttam");
        String projectId = billingProject(token);
        saveAgreement(token, projectId);

        signedSheet(token, projectId, BOQ_COLUMNS, "MB-3001", "1.00", """
                [{"location":"A","nos":1,"mult":1,"length":1.00,"breadth":1.00,"height":1.00}]
                """);
        String billId = createBill(token, projectId, "2026-06-30");
        passBill(token, billId);
        BigDecimal grossAtFreeze = decimal(getBill(token, billId), "grossWorkDone");

        signedSheet(token, projectId, BOQ_COLUMNS, "MB-3002", "9.00", """
                [{"location":"B","nos":1,"mult":1,"length":9.00,"breadth":1.00,"height":1.00}]
                """);

        assertThat(decimal(getBill(token, billId), "grossWorkDone"))
                .isEqualByComparingTo(grossAtFreeze);
        assertThat(getBill(token, billId).get("frozen").asBoolean()).isTrue();
    }

    // ---------------------------------------------------------------- what is refused

    /**
     * The tender's own details are asked once, at its first bill, and the bill is refused
     * without them — because a CPWA-26 form with no contractor named on it is not a document
     * anybody can pass.
     */
    @Test
    @DisplayName("the first bill of a tender is refused until the tender's details are given")
    void firstBillNeedsTheAgreement() throws Exception {
        String token = loginToken("uttam");
        String projectId = billingProject(token);

        signedSheet(token, projectId, BOQ_COLUMNS, "MB-4001", "1.00", """
                [{"location":"A","nos":1,"mult":1,"length":1.00,"breadth":1.00,"height":1.00}]
                """);

        mockMvc.perform(post("/api/v1/ra-bills")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"%s","cutoffDate":"2026-06-30"}
                                """.formatted(projectId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://nirman/errors/billing.agreement-required"));

        saveAgreement(token, projectId);
        mockMvc.perform(post("/api/v1/ra-bills")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"%s","cutoffDate":"2026-06-30"}
                                """.formatted(projectId)))
                .andExpect(status().isCreated());
    }

    /** One serial, one sheet. Entering the same page twice is how a quantity gets paid twice. */
    @Test
    @DisplayName("a pre-printed serial already entered is refused rather than duplicated")
    void duplicateSheetSerialRefused() throws Exception {
        String token = loginToken("uttam");
        String projectId = billingProject(token);

        createSheet(token, projectId, BOQ_COLUMNS, "MB-5001", "1.00", """
                [{"location":"A","nos":1,"mult":1,"length":1.00,"breadth":1.00,"height":1.00}]
                """);

        mockMvc.perform(post("/api/v1/measurement-sheets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"%s","boqItemId":"%s",
                                 "sheetSerial":"MB-5001","measuredOn":"2026-06-01","lines":[]}
                                """.formatted(projectId, itemOn(projectId, BOQ_COLUMNS))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://nirman/errors/billing.sheet-already-entered"));
    }

    /** A signed sheet is somebody's signature. Corrections are new sheets, never edits. */
    @Test
    @DisplayName("a signed sheet cannot be edited")
    void signedSheetIsImmutable() throws Exception {
        String token = loginToken("uttam");
        String projectId = billingProject(token);
        String sheetId = signedSheet(token, projectId, BOQ_COLUMNS, "MB-6001", "1.00", """
                [{"location":"A","nos":1,"mult":1,"length":1.00,"breadth":1.00,"height":1.00}]
                """);

        long version = getSheet(token, sheetId).get("version").asLong();
        mockMvc.perform(put("/api/v1/measurement-sheets/{id}", sheetId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measuredOn":"2026-06-01","writtenTotal":99.00,"version":%d,
                                 "lines":[{"nos":1,"mult":1,"length":99.00,"breadth":1,"height":1}]}
                                """.formatted(version)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://nirman/errors/billing.sheet-signed"));
    }

    /** Passing a bill sends a figure to the department, which is not the same as preparing it. */
    @Test
    @DisplayName("a passed bill cannot be re-opened")
    void passedBillCannotReopen() throws Exception {
        String token = loginToken("uttam");
        String projectId = billingProject(token);
        saveAgreement(token, projectId);
        signedSheet(token, projectId, BOQ_COLUMNS, "MB-7001", "1.00", """
                [{"location":"A","nos":1,"mult":1,"length":1.00,"breadth":1.00,"height":1.00}]
                """);
        String billId = createBill(token, projectId, "2026-06-30");
        passBill(token, billId);

        mockMvc.perform(post("/api/v1/ra-bills/{id}/decide", billId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"REOPEN","remarks":"department returned it"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://nirman/errors/billing.passed-bill-final"));
    }

    /** Discarding a draft bill has to give its sheets back, or the work is stranded unbillable. */
    @Test
    @DisplayName("discarding a draft bill returns its sheets to the unbilled queue")
    void discardReleasesSheets() throws Exception {
        String token = loginToken("uttam");
        String projectId = billingProject(token);
        saveAgreement(token, projectId);
        String sheetId = signedSheet(token, projectId, BOQ_COLUMNS, "MB-8001", "1.00", """
                [{"location":"A","nos":1,"mult":1,"length":1.00,"breadth":1.00,"height":1.00}]
                """);
        String billId = createBill(token, projectId, "2026-06-30");

        mockMvc.perform(delete("/api/v1/ra-bills/{id}", billId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "SELECT ra_bill_id FROM measurement_sheets WHERE id = ?::uuid", UUID.class, sheetId))
                .isNull();
    }

    /** Billing is not a permission a supervisor holds, and the API says so rather than the screen. */
    @Test
    @DisplayName("a supervisor cannot record a measurement sheet")
    void supervisorIsRefused() throws Exception {
        String token = loginToken("vivek");
        mockMvc.perform(post("/api/v1/measurement-sheets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"%s","siteId":"%s","boqItemId":"%s",
                                 "measuredOn":"2026-06-01","lines":[]}
                                """.formatted(PROJECT, SITE_A, BOQ_COLUMNS)))
                .andExpect(status().isForbidden());
    }

    /**
     * The blank paper. It is an artefact that gets printed, bound and carried to a site, so a
     * template that fails to render is not a cosmetic problem — it is the engineer with no
     * sheet to write on.
     */
    @Test
    @DisplayName("blank measurement sheets render as a PDF, numbered from where the run starts")
    void blankSheetsRender() throws Exception {
        String token = loginToken("uttam");
        byte[] pdf = mockMvc.perform(get("/api/v1/measurement-sheets/blank")
                        .param("from", "1")
                        .param("count", "3")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1))
                .startsWith("%PDF-");
        assertThat(pdf.length).isGreaterThan(1000);
    }

    /** A print run has to be bounded, or a typo asks for a hundred thousand pages. */
    @Test
    @DisplayName("an unbounded print run is refused")
    void printRunIsBounded() throws Exception {
        String token = loginToken("uttam");
        mockMvc.perform(get("/api/v1/measurement-sheets/blank")
                        .param("from", "1")
                        .param("count", "100000")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://nirman/errors/billing.print-run-out-of-range"));
    }

    /**
     * The workbook that leaves the system, opened and read back.
     *
     * <p>The point of generating formulas rather than frozen numbers is that the Assistant
     * Engineer can click a cell and see the arithmetic. A test that only checked the values
     * would pass on a workbook of dead numbers, so this one reads the formula strings and the
     * cross-sheet reference that ties the abstract to the measurement book.</p>
     */
    @Test
    @DisplayName("the exported bill carries live formulas and real cross-sheet references")
    void exportedWorkbookIsLinked() throws Exception {
        String token = loginToken("uttam");
        String projectId = billingProject(token);
        saveAgreement(token, projectId);
        signedSheet(token, projectId, BOQ_COLUMNS, "MB-9001", "3.61", """
                [{"location":"GD Room","nos":1,"mult":1,"length":6.22,"breadth":5.80,"height":0.10}]
                """);
        String billId = createBill(token, projectId, "2026-06-30");

        byte[] body = mockMvc.perform(get("/api/v1/ra-bills/{id}/excel", billId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(body))) {
            assertThat(sheetNames(workbook)).contains("Front Page", "Measurement Book",
                    "Abstract of Cost", "Bill Form", "Recovery Statement", "Deviation Statement");

            // The measurement row multiplies only the dimensions given, and rounds as the book
            // rounds: nos x mult x L x B x H for a volume item.
            String rowFormula = firstFormulaContaining(workbook.getSheet("Measurement Book"),
                    "ROUND(C");
            assertThat(rowFormula).matches("ROUND\\(C\\d+\\*D\\d+\\*E\\d+\\*F\\d+\\*G\\d+,2\\)");

            // The abstract does not carry its own copy of the quantity — it points at the block
            // total in the measurement book.
            assertThat(firstFormulaContaining(workbook.getSheet("Abstract of Cost"),
                    "'Measurement Book'!"))
                    .startsWith("'Measurement Book'!H");

            // The amount is the rate times that quantity, live.
            assertThat(firstFormulaContaining(workbook.getSheet("Abstract of Cost"), "ROUND(F"))
                    .matches("ROUND\\(F\\d+\\*D\\d+,2\\)");

            // And the statutory forms read the abstract rather than restating it.
            assertThat(firstFormulaContaining(workbook.getSheet("Bill Form"), "Abstract of Cost"))
                    .contains("'Abstract of Cost'!G");
            assertThat(firstFormulaContaining(workbook.getSheet("Recovery Statement"), "2.5%"))
                    .contains("ROUND(E");
        }
    }

    private static java.util.List<String> sheetNames(
            org.apache.poi.xssf.usermodel.XSSFWorkbook workbook) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            names.add(workbook.getSheetName(i));
        }
        return names;
    }

    private static String firstFormulaContaining(org.apache.poi.ss.usermodel.Sheet sheet,
                                                 String needle) {
        for (org.apache.poi.ss.usermodel.Row row : sheet) {
            for (org.apache.poi.ss.usermodel.Cell cell : row) {
                if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA
                        && cell.getCellFormula().contains(needle)) {
                    return cell.getCellFormula();
                }
            }
        }
        throw new AssertionError("no formula containing " + needle + " on " + sheet.getSheetName());
    }

    // ---------------------------------------------------------------- the print run

    /**
     * One serial, one sheet, for ever. The register refuses a number it has already seen, so a
     * run that reprinted numbers would produce paper nobody could enter — which is why the
     * screen suggests where to start rather than defaulting to 1 every time.
     */
    @Test
    @DisplayName("the next print run is suggested after the highest serial already entered")
    void nextSerialFollowsTheHighestUsed() throws Exception {
        String token = loginToken("uttam");
        String projectId = billingProject(token);

        createSheet(token, projectId, BOQ_COLUMNS, "M-000041", "1.00", """
                [{"location":"A","nos":1,"mult":1,"length":1.00,"breadth":1.00,"height":1.00}]
                """);
        createSheet(token, projectId, BOQ_BEAMS, "M-000007", "1.00", """
                [{"location":"B","nos":1,"mult":1,"length":1.00,"breadth":1.00,"height":1.00}]
                """);

        // 41 and not 7: the comparison is numeric, so M-000007 does not out-rank M-000041 by
        // being read as a string.
        mockMvc.perform(get("/api/v1/measurement-sheets/next-serial")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastUsed").value(41))
                .andExpect(jsonPath("$.nextSerial").value(42));
    }

    /** With nothing entered there is nothing to follow, and the first book starts at one. */
    @Test
    @DisplayName("with no sheets entered the suggestion is the first serial")
    void firstPrintRunStartsAtOne() throws Exception {
        String token = loginToken("uttam");
        // Only this suite's own rows. A test that reaches past its own fixture is how one
        // suite starts deciding whether another passes.
        jdbc.update("UPDATE measurement_sheets SET deleted_at = now() "
                + "WHERE deleted_at IS NULL AND (sheet_serial LIKE 'M-%' OR sheet_serial LIKE 'MB-%')");

        mockMvc.perform(get("/api/v1/measurement-sheets/next-serial")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextSerial").value(1));
    }

    // ---------------------------------------------------------------- the vault

    /**
     * The property the vault exists for.
     *
     * <p>A tender is priced under a particular edition and stays priced under it. Publishing a
     * newer schedule says what to use next; it must not touch a single agreement already citing
     * the old one, because a bill that repriced itself when the shelf changed would invent
     * money in one direction or the other.</p>
     */
    @Test
    @DisplayName("superseding an edition leaves the tenders already priced under it alone")
    void supersedingDoesNotRepriceExistingTenders() throws Exception {
        String token = loginToken("uttam");
        String projectId = billingProject(token);
        saveAgreement(token, projectId);

        String dsr2023 = createDocument(token, "DSR", "DSR-2023-" + shortId(),
                "Delhi Schedule of Rates 2023", 2023, null);
        link(token, projectId, dsr2023);
        assertThat(tenderDocuments(token, projectId).get(0).get("code").asText())
                .startsWith("DSR-2023-");

        String dsr2026 = createDocument(token, "DSR", "DSR-2026-" + shortId(),
                "Delhi Schedule of Rates 2026", 2026, null);
        mockMvc.perform(post("/api/v1/reference-documents/{id}/supersede", dsr2023)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"replacedBy\":\"%s\"}".formatted(dsr2026)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPERSEDED"));

        // The tender still cites 2023, and still says so.
        JsonNode still = tenderDocuments(token, projectId).get(0);
        assertThat(still.get("documentId").asText()).isEqualTo(dsr2023);
        assertThat(still.get("code").asText()).startsWith("DSR-2023-");
    }

    /** An edition a tender is billing under cannot vanish from under it. */
    @Test
    @DisplayName("an edition a tender is priced under cannot be withdrawn")
    void inUseDocumentCannotBeWithdrawn() throws Exception {
        String token = loginToken("uttam");
        String projectId = billingProject(token);
        saveAgreement(token, projectId);
        String dsr = createDocument(token, "DSR", "DSR-USE-" + shortId(), "In use", 2023, null);
        link(token, projectId, dsr);

        mockMvc.perform(delete("/api/v1/reference-documents/{id}", dsr)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://nirman/errors/vault.document-in-use"));
    }

    /**
     * A cost index exists to state a percentage; a schedule of rates has thousands of rates and
     * no single number. Letting either carry the other's shape is how a figure gets used for
     * something it never meant.
     */
    @Test
    @DisplayName("only a cost index carries a percentage, and it must carry one")
    void percentBelongsOnlyToACostIndex() throws Exception {
        String token = loginToken("uttam");

        mockMvc.perform(post("/api/v1/reference-documents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"COST_INDEX","code":"CI-%s","title":"Mussoorie 2025"}
                                """.formatted(shortId())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://nirman/errors/vault.cost-index-needs-percent"));

        mockMvc.perform(post("/api/v1/reference-documents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"DSR","code":"DSR-BAD-%s","title":"With a percentage",
                                 "indexPercent":23}
                                """.formatted(shortId())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://nirman/errors/vault.percent-not-applicable"));
    }

    /** Two rows answering to one code make every later question about it ambiguous. */
    @Test
    @DisplayName("an edition code cannot be used twice")
    void editionCodeIsUnique() throws Exception {
        String token = loginToken("uttam");
        String code = "DSR-DUP-" + shortId();
        createDocument(token, "DSR", code, "First", 2023, null);

        mockMvc.perform(post("/api/v1/reference-documents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"DSR","code":"%s","title":"Second","editionYear":2023}
                                """.formatted(code)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://nirman/errors/vault.code-taken"));
    }

    private String createDocument(String token, String kind, String code, String title,
                                  Integer year, String percent) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/reference-documents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"%s","code":"%s","title":"%s","editionYear":%s%s}
                                """.formatted(kind, code, title, year,
                                percent == null ? "" : ",\"indexPercent\":" + percent)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void link(String token, String projectId, String documentId) throws Exception {
        mockMvc.perform(put("/api/v1/projects/{id}/agreement/documents", projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"documentId":"%s","role":"SCHEDULE_OF_RATES"}]
                                """.formatted(documentId)))
                .andExpect(status().isOk());
    }

    private JsonNode tenderDocuments(String token, String projectId) throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/v1/projects/{id}/agreement/documents", projectId)
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Each test works on its own project so the bill series in one does not disturb another.
     * The BOQ lines are the seeded ones, re-pointed, which keeps the rates real.
     */
    private String billingProject(String token) throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        String code = "BIL" + projectId.toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO projects (id, org_id, code, name, client_department, status, mode)
                VALUES (?::uuid, '10000000-0000-0000-0000-000000000001', ?, ?, 'CPWD', 'ACTIVE',
                        'BILLING_ONLY')
                """, projectId.toString(), code, "Billing test " + code);
        jdbc.update("""
                INSERT INTO sites (id, org_id, project_id, code, name, status)
                VALUES (?::uuid, '10000000-0000-0000-0000-000000000001', ?::uuid, ?, ?, 'ACTIVE')
                """, siteId.toString(), projectId.toString(), "S-" + code, "Site " + code);
        // The caller has to be posted to the site, or SiteAccessGuard refuses one the test
        // itself created — the same trap a BILLING_ONLY import has to avoid on the way in.
        jdbc.update("""
                INSERT INTO user_site_assignments (org_id, user_id, site_id)
                SELECT '10000000-0000-0000-0000-000000000001', u.id, ?::uuid
                  FROM users u WHERE u.username = 'uttam'
                """, siteId.toString());
        // Fresh copies of two seeded lines, so the rates under the arithmetic are real ones.
        for (String seeded : new String[] {BOQ_COLUMNS, BOQ_BEAMS}) {
            jdbc.update("""
                    INSERT INTO boq_items (id, org_id, project_id, site_id, item_number,
                                           description, unit_id, contract_quantity, contract_rate,
                                           contract_amount, source, sort_order)
                    SELECT ?::uuid, '10000000-0000-0000-0000-000000000001', ?::uuid, ?::uuid,
                           b.item_number, b.description, b.unit_id, b.contract_quantity,
                           b.contract_rate, b.contract_amount, 'MANUAL', b.sort_order
                      FROM boq_items b WHERE b.id = ?::uuid
                    """, UUID.randomUUID().toString(), projectId.toString(), siteId.toString(),
                    seeded);
        }
        createdProjects.add(projectId.toString());
        return projectId.toString();
    }

    private String createSheet(String token, String projectId, String boqItemId, String serial,
                               String writtenTotal, String linesJson) throws Exception {
        String written = writtenTotal == null ? "null" : writtenTotal;
        MvcResult result = mockMvc.perform(post("/api/v1/measurement-sheets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"%s","boqItemId":"%s","sheetSerial":"%s",
                                 "measuredOn":"2026-06-01","writtenTotal":%s,"lines":%s}
                                """.formatted(projectId, itemOn(projectId, boqItemId), serial,
                                written, linesJson)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createSheetWithUnitWeight(String token, String projectId, String boqItemId,
                                             String serial, String unitWeight, String linesJson)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/measurement-sheets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"%s","boqItemId":"%s","sheetSerial":"%s",
                                 "measuredOn":"2026-06-01","unitWeight":%s,"lines":%s}
                                """.formatted(projectId, itemOn(projectId, boqItemId), serial,
                                unitWeight, linesJson)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String signedSheet(String token, String projectId, String boqItemId, String serial,
                               String writtenTotal, String linesJson) throws Exception {
        String sheetId = createSheet(token, projectId, boqItemId, serial, writtenTotal, linesJson);
        mockMvc.perform(post("/api/v1/measurement-sheets/{id}/sign", sheetId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return sheetId;
    }

    /** The copy of a seeded BOQ line that lives on this test's own project. */
    private String itemOn(String projectId, String seededItemId) {
        String itemNumber = jdbc.queryForObject(
                "SELECT item_number FROM boq_items WHERE id = ?::uuid", String.class, seededItemId);
        return jdbc.queryForObject(
                "SELECT id::text FROM boq_items WHERE project_id = ?::uuid AND item_number = ?",
                String.class, projectId, itemNumber);
    }

    private void updateWrittenTotal(String token, String sheetId, String total) throws Exception {
        JsonNode sheet = getSheet(token, sheetId);
        StringBuilder lines = new StringBuilder("[");
        for (JsonNode line : sheet.get("lines")) {
            if (lines.length() > 1) {
                lines.append(',');
            }
            lines.append("{\"location\":\"").append(line.get("location").asText())
                    .append("\",\"nos\":").append(line.get("nos").asText())
                    .append(",\"mult\":").append(line.get("mult").asText())
                    .append(",\"length\":").append(line.get("length").asText())
                    .append(",\"breadth\":").append(line.get("breadth").asText())
                    .append(",\"height\":").append(line.get("height").asText())
                    .append('}');
        }
        lines.append(']');
        mockMvc.perform(put("/api/v1/measurement-sheets/{id}", sheetId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measuredOn":"2026-06-01","writtenTotal":%s,"version":%d,"lines":%s}
                                """.formatted(total, sheet.get("version").asLong(), lines)))
                .andExpect(status().isOk());
    }

    private void saveAgreement(String token, String projectId) throws Exception {
        mockMvc.perform(put("/api/v1/projects/{id}/agreement", projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agreementNo":"26/EE(E)/Mussoorie/2025-26",
                                 "division":"EE, Mussoorie","contractorName":"M/s Unique Associates",
                                 "measuredByName":"Sarvesh Kumar","measuredByDesignation":"JE(C)",
                                 "dsrCoefficient":0.973,"costIndexPct":23,"tenderPct":-11.11,
                                 "deviationLimitPct":100}
                                """))
                .andExpect(status().isOk());
    }

    private String createBill(String token, String projectId, String cutoff) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/ra-bills")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"%s","cutoffDate":"%s"}
                                """.formatted(projectId, cutoff)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void passBill(String token, String billId) throws Exception {
        decide(token, billId, "SUBMIT");
        decide(token, billId, "CHECK");
        decide(token, billId, "PASS");
    }

    private void decide(String token, String billId, String action) throws Exception {
        mockMvc.perform(post("/api/v1/ra-bills/{id}/decide", billId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"%s\"}".formatted(action)))
                .andExpect(status().isOk());
    }

    private JsonNode getSheet(String token, String sheetId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/measurement-sheets/{id}", sheetId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getBill(String token, String billId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/ra-bills/{id}", billId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
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

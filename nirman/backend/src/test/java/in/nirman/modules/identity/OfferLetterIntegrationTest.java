package in.nirman.modules.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.AbstractIntegrationTest;
import in.nirman.InMemoryStorageConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The offer letter: written off the record, and filed back onto it.
 *
 * <p>The two properties worth holding. First, that it states the terms the record already
 * carries rather than asking for them again — which is why a record with no salary structure
 * is refused with a sentence instead of producing a letter with a blank where the pay goes.
 * Second, that issuing it <em>files</em> it: a letter that lived only in a download folder
 * would be the one term of employment nobody could produce when it was disputed.</p>
 */
@Import(InMemoryStorageConfig.class)
class OfferLetterIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        String made = "SELECT id FROM users WHERE username LIKE 'offer.%'";
        jdbc.update("DELETE FROM staff_documents WHERE user_id IN (" + made + ")");
        jdbc.update("DELETE FROM staff_salary_revisions WHERE user_id IN (" + made + ")");
        jdbc.update("DELETE FROM staff_profiles WHERE user_id IN (" + made + ")");
    }

    @Test
    @DisplayName("the letter renders, and issuing it files a paper on the record")
    void issuingFilesItOnTheRecord() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "offer.filed");
        saveRecord(admin, userId);
        recordStructure(admin, userId);

        // Previewing keeps nothing: a letter is read before it is sent.
        byte[] preview = mockMvc.perform(post("/api/v1/staff/" + userId + "/offer-letter/preview")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeOfPosting\":\"Kausani\",\"reportingTo\":\"Uttam Rana\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(preview, 0, 5)).startsWith("%PDF-");
        assertThat(documents(admin, userId)).isEmpty();

        MvcResult issued = mockMvc.perform(post("/api/v1/staff/" + userId + "/offer-letter")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeOfPosting\":\"Kausani\",\"signatoryName\":\"V Chaudhary\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.docType").value("OFFER_LETTER"))
                .andReturn();

        JsonNode papers = documents(admin, userId);
        assertThat(papers).hasSize(1);
        assertThat(papers.get(0).get("docType").asText()).isEqualTo("OFFER_LETTER");
        // The reference is on the note, so the filing cabinet and the register agree.
        assertThat(papers.get(0).get("note").asText()).contains("SHIVADRI/HR/");
        assertThat(papers.get(0).get("fileName").asText()).endsWith(".pdf");

        // And it is a real file behind a signed link, like any other paper on the record.
        mockMvc.perform(get("/api/v1/attachments/"
                        + read(issued).get("attachmentId").asText() + "/url")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").isNotEmpty());
    }

    /**
     * The annexure states what will actually come out of the packet.
     *
     * <p>"Subject to statutory deductions" is the sentence every candidate reads as meaning
     * nothing and then queries on his first payslip. The fund and the insurance are arithmetic
     * on the structure and depend on nothing he has yet decided, so they are stated — computed
     * through the same class the payroll uses, so the letter and the first payslip agree by
     * construction rather than by luck.</p>
     */
    @Test
    @DisplayName("the letter states the deductions it can compute, and no take-home")
    void theLetterStatesItsDeductions() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "offer.deductions");
        saveRecord(admin, userId);
        recordStructure(admin, userId);

        String letter = textOf(preview(admin, userId, "{}"));

        // 12% of the 15,000 basic, which sits exactly on the statutory ceiling.
        assertThat(letter).contains("Provident fund @ 12%").contains("1,800.00");
        // 0.75% of the 18,000 gross.
        assertThat(letter).contains("State insurance @ 0.75%").contains("135.00");
        assertThat(letter).contains("Total deductions (before tax)");
        // And the one line that stops the total being read as a take-home.
        assertThat(letter).contains("Tax is not included above");
        // The firm deducts no professional tax, so the annexure does not state one — a letter
        // naming a deduction the first payslip will not show is the disagreement the annexure
        // exists to prevent.
        assertThat(letter).doesNotContain("Professional tax");
    }

    /**
     * The letterhead carries the firm, not the person who happens to hold the contact address.
     *
     * <p>{@code organisations.contact_email} holds one individual's address, and an offer
     * signed by somebody else still went out over it. A candidate replying to a letter replies
     * to whoever sent it to him.</p>
     */
    @Test
    @DisplayName("the letterhead carries no email address")
    void theLetterheadCarriesNoEmail() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "offer.letterhead");
        saveRecord(admin, userId);
        recordStructure(admin, userId);

        // The seeded organisation's contact address. The rates on the annexure are written
        // "@ 12%", so the address itself is what this looks for rather than the character.
        assertThat(textOf(preview(admin, userId, "{}"))).doesNotContain("office@nirman.example");
    }

    /**
     * A scheme that does not reach this member is said out loud rather than left off. A row
     * quietly missing is one the office never notices it forgot to tick.
     */
    @Test
    @DisplayName("a scheme that does not apply says so rather than going missing")
    void anInapplicableSchemeIsNamed() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "offer.nopf");
        saveRecordWith(admin, userId, "\"pfApplicable\":false,\"esiApplicable\":false");
        recordStructure(admin, userId);

        assertThat(textOf(preview(admin, userId, "{}")))
                .contains("Provident fund @ 12%")
                .contains("State insurance @ 0.75%")
                .contains("Not applicable");
    }

    /**
     * A room and a tank of petrol are terms of the engagement, and until they were on the
     * record the letter could not state them and the argument in the third month — "you said
     * the room was included" — had nothing behind it.
     */
    @Test
    @DisplayName("the letter states the accommodation and the fuel the firm provides")
    void theLetterStatesWhatTheFirmProvides() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "offer.provides");
        saveRecordWith(admin, userId,
                "\"accommodationProvided\":true,"
                        + "\"accommodationNote\":\"shared room at the site guest house\","
                        + "\"fuelProvided\":true,\"fuelMonthlyAmount\":2000");
        recordStructure(admin, userId);

        assertThat(textOf(preview(admin, userId, "{}")))
                .contains("What the firm provides")
                .contains("shared room at the site guest house")
                .contains("2,000.00")
                .contains("are not part of your");
    }

    /** Null is an arrangement and not a gap: fuel at actuals is not fuel of zero rupees. */
    @Test
    @DisplayName("fuel with no figure reads as reimbursement at actuals")
    void fuelWithNoFigureIsAtActuals() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "offer.actuals");
        saveRecordWith(admin, userId, "\"fuelProvided\":true");
        recordStructure(admin, userId);

        assertThat(textOf(preview(admin, userId, "{}")))
                .contains("reimbursed at actuals against bills");
    }

    /**
     * The numbered terms survive being copied out of the PDF.
     *
     * <p>openhtmltopdf writes an ordered list's markers as a text run of their own, positioned
     * apart from the lines they belong to: the page looked right and anybody who pasted the
     * letter into an email got "1. 2. 3. …" in a column at the top with eight unnumbered
     * paragraphs below it.</p>
     */
    @Test
    @DisplayName("the numbered terms keep their numbers when the letter is copied out")
    void theTermsAreNumberedInTheText() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "offer.numbered");
        saveRecord(admin, userId);
        recordStructure(admin, userId);

        // The defect, stated as what it looked like: every clause number stranded on a line of
        // its own, in a column at the top, with the clauses unnumbered below. So the test is
        // that no line of the letter is nothing but a number and a full stop.
        String[] lines = rawTextOf(preview(admin, userId, "{}")).split("\\R");
        assertThat(lines).noneMatch(line -> line.strip().matches("\\d+\\."));
        // And the numbers are on the page: eleven clauses, the first of them numbered 1.
        assertThat(textOf(preview(admin, userId, "{}"))).contains("1. ");
    }

    @Test
    @DisplayName("a record with no salary is refused rather than given a blank where the pay goes")
    void noSalaryNoLetter() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "offer.nopay");
        saveRecord(admin, userId);

        mockMvc.perform(post("/api/v1/staff/" + userId + "/offer-letter/preview")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("is not an offer")));
    }

    @Test
    @DisplayName("a member with no staff record at all is told to make one first")
    void noRecordNoLetter() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "offer.norecord");

        mockMvc.perform(post("/api/v1/staff/" + userId + "/offer-letter/preview")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("no staff record")));
    }

    /** No new permission was minted: writing the record and writing its letter are one act. */
    @Test
    @DisplayName("a supervisor cannot issue an offer letter")
    void aSupervisorIsRefused() throws Exception {
        String admin = login("viplove");
        String userId = onboard(admin, "offer.refused");
        saveRecord(admin, userId);

        mockMvc.perform(post("/api/v1/staff/" + userId + "/offer-letter")
                        .header("Authorization", "Bearer " + login("vivek"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode documents(String token, String userId) throws Exception {
        return read(mockMvc.perform(get("/api/v1/staff/" + userId + "/documents")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
    }

    /** The record, plus whatever extra terms a test needs on it. */
    private void saveRecordWith(String token, String userId, String extra) throws Exception {
        mockMvc.perform(put("/api/v1/staff/" + userId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"employmentType":"PROBATION","joinedOn":"2026-09-01",
                                 "probationDays":90,"designation":"Site Supervisor",
                                 "employeeNumber":"2298","noticePeriodDays":30,
                                 "currentAddress":"Bageshwar, Uttarakhand",
                                 "pfApplicable":true,"esiApplicable":true,
                                 "pfOnFullWages":false,%s}""".formatted(
                                extra.isEmpty() ? "" : "," + extra).replace(",,", ",")))
                .andExpect(status().isOk());
    }

    /**
     * The rendered letter as one run of text, so a test can read what a candidate would read.
     *
     * <p>Whitespace is collapsed, because a PDF wraps where the column ends: "shared room at
     * the site guest house" comes out of the extractor with a newline somewhere in the middle
     * of it, and an assertion that failed on that would be testing the line length rather than
     * the letter.</p>
     */
    private String textOf(byte[] pdf) throws Exception {
        return rawTextOf(pdf).replaceAll("\\s+", " ");
    }

    /** The same text with its lines intact, for the one test that is about the lines. */
    private String rawTextOf(byte[] pdf) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument document =
                     org.apache.pdfbox.pdmodel.PDDocument.load(pdf)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(document);
        }
    }

    private byte[] preview(String token, String userId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/staff/" + userId + "/offer-letter/preview")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private void saveRecord(String token, String userId) throws Exception {
        mockMvc.perform(put("/api/v1/staff/" + userId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"employmentType":"PROBATION","joinedOn":"2026-09-01",
                                 "probationDays":90,"designation":"Site Supervisor",
                                 "employeeNumber":"2299","noticePeriodDays":30,
                                 "currentAddress":"Bageshwar, Uttarakhand",
                                 "pfApplicable":true,"esiApplicable":true,
                                 "pfOnFullWages":false}"""))
                .andExpect(status().isOk());
    }

    private void recordStructure(String token, String userId) throws Exception {
        mockMvc.perform(post("/api/v1/staff/" + userId + "/salary")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"basic":15000,"otherAllowance":3000,
                                 "effectiveFrom":"2026-09-01","reason":"Terms offered"}"""))
                .andExpect(status().isCreated());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String onboard(String adminToken, String username) throws Exception {
        return read(mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","fullName":"%s","mobile":"9800000000",
                                 "temporaryPassword":"%s","roleCodes":["SUPERVISOR"]}"""
                                .formatted(username, username, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText();
    }

    private String login(String username) throws Exception {
        return read(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}"
                                .formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()).get("accessToken").asText();
    }
}

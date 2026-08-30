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
        assertThat(papers.get(0).get("note").asText()).contains("NIRMAN/HR/");
        assertThat(papers.get(0).get("fileName").asText()).endsWith(".pdf");

        // And it is a real file behind a signed link, like any other paper on the record.
        mockMvc.perform(get("/api/v1/attachments/"
                        + read(issued).get("attachmentId").asText() + "/url")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").isNotEmpty());
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
                                {"basic":15000,"otherAllowance":3000,"professionalTax":200,
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

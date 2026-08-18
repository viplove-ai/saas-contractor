package in.nirman.modules.treasury;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The FDR register: every fixed deposit the company holds, whatever it is pledged against.
 *
 * <p>The question the whole feature exists for is the one V38's per-contract register could not
 * answer — <b>what have we got that is free to bid with</b> — and it is the {@code idleAmount}
 * assertions below. The rest guard the two ways a certificate can be told two stories about
 * itself: closed while a department still holds it, and lodged against two contracts at once.</p>
 */
class BankDepositRegisterIntegrationTest extends AbstractIntegrationTest {

    private static final String MARK = "FDR/TEST/";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * By SQL, and for the reason the deposit register's own teardown gives: the endpoints
     * deliberately refuse to unpick a pledged certificate, which is what half these tests
     * assert, so a teardown written against the API could not put the fixture back.
     */
    @AfterEach
    void removeWhatTheTestLeftStanding() throws Exception {
        jdbc.update("DELETE FROM project_securities WHERE project_id IN "
                + "(SELECT id FROM projects WHERE code LIKE 'FDR%')");
        jdbc.update("DELETE FROM bank_deposit_photos WHERE deposit_id IN "
                + "(SELECT id FROM bank_deposits WHERE deposit_number LIKE ?)", MARK + "%");
        jdbc.update("DELETE FROM bank_deposits WHERE deposit_number LIKE ?", MARK + "%");
        String token = adminToken();
        for (JsonNode project : json(get("/api/v1/projects?size=100"), token).get("content")) {
            if (project.get("code").asText().startsWith("FDR")) {
                mockMvc.perform(delete("/api/v1/projects/" + project.get("id").asText())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"test fixture cleanup\"}"));
            }
        }
    }

    @Test
    @DisplayName("a deposit entered but pledged to nothing is money free for the next tender")
    void anIdleDepositIsCountedAsAvailable() throws Exception {
        String token = adminToken();
        createDeposit(token, "01", "500000.00");

        JsonNode register = json(get("/api/v1/bank-deposits"), token);
        JsonNode mine = rowFor(register, MARK + "01");

        assertThat(mine.get("status").asText()).isEqualTo("HELD");
        // Absent rather than null: the API omits empty fields, so "nothing holds it" reads as
        // the key not being there at all.
        assertThat(mine.hasNonNull("pledgedTo")).as("nothing holds it").isFalse();
        assertThat(mine.get("history")).isEmpty();
        // The summary counts it as idle, which is the figure the office could not get before.
        assertThat(register.get("summary").get("idleCount").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("lodging a certificate against a contract shows on the register as pledged to it")
    void pledgingShowsOnTheRegister() throws Exception {
        String token = adminToken();
        String depositId = createDeposit(token, "02", "500000.00").get("id").asText();
        String projectId = createContract(token, "FDR-ONE");
        String securityId = createSecurity(token, projectId, "500000.00");

        lodge(token, securityId, depositId, 0).andExpect(status().isOk())
                // The certificate's number is copied onto the contract's row as it is pledged,
                // so that screen reads without a second call.
                .andExpect(jsonPath("$.referenceNo").value(MARK + "02"))
                .andExpect(jsonPath("$.bankDepositId").value(depositId));

        JsonNode mine = rowFor(json(get("/api/v1/bank-deposits"), token), MARK + "02");
        assertThat(mine.get("pledgedTo").get("projectCode").asText()).isEqualTo("FDR-ONE");
        assertThat(mine.get("pledgedTo").get("securityType").asText()).isEqualTo("EMD");
        assertThat(mine.get("history")).hasSize(1);
    }

    /**
     * The reuse the register exists to make legible. The department gives the earnest money
     * back, the same certificate goes to the next tender, and its history is one thread rather
     * than two unrelated rows on two contracts.
     */
    @Test
    @DisplayName("a released certificate goes to the next contract, and its history is one thread")
    void aReleasedCertificateIsPledgedAgain() throws Exception {
        String token = adminToken();
        String depositId = createDeposit(token, "03", "500000.00").get("id").asText();

        String first = createContract(token, "FDR-FIRST");
        String firstSecurity = createSecurity(token, first, "500000.00");
        lodge(token, firstSecurity, depositId, 0).andExpect(status().isOk());

        // While it is lodged, the next contract cannot have it.
        String second = createContract(token, "FDR-SECOND");
        String secondSecurity = createSecurity(token, second, "500000.00");
        lodge(token, secondSecurity, depositId, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("already lodged")));

        release(token, firstSecurity, 1);
        lodge(token, secondSecurity, depositId, 0).andExpect(status().isOk());

        JsonNode mine = rowFor(json(get("/api/v1/bank-deposits"), token), MARK + "03");
        assertThat(mine.get("pledgedTo").get("projectCode").asText()).isEqualTo("FDR-SECOND");
        assertThat(mine.get("history")).as("both contracts, oldest first").hasSize(2);
        assertThat(mine.get("history").get(0).get("projectCode").asText()).isEqualTo("FDR-FIRST");
        assertThat(mine.get("history").get(0).get("status").asText()).isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("a certificate a department is holding cannot be closed")
    void aPledgedCertificateCannotBeClosed() throws Exception {
        String token = adminToken();
        JsonNode deposit = createDeposit(token, "04", "500000.00");
        String depositId = deposit.get("id").asText();
        String projectId = createContract(token, "FDR-HELD");
        lodge(token, createSecurity(token, projectId, "500000.00"), depositId, 0)
                .andExpect(status().isOk());

        long version = json(get("/api/v1/bank-deposits/" + depositId), token)
                .get("version").asLong();
        mockMvc.perform(post("/api/v1/bank-deposits/" + depositId + "/close")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"closedOn":"%s","reason":"matured","version":%d}"""
                                .formatted(LocalDate.now(), version)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("still lodged")));
    }

    @Test
    @DisplayName("a closed certificate leaves the held total and cannot be lodged with anybody")
    void closingTakesItOutOfWhatIsHeld() throws Exception {
        String token = adminToken();
        String depositId = createDeposit(token, "05", "700000.00").get("id").asText();

        close(token, depositId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        JsonNode register = json(get("/api/v1/bank-deposits"), token);
        assertThat(rowFor(register, MARK + "05").get("status").asText()).isEqualTo("CLOSED");

        String projectId = createContract(token, "FDR-CLOSED");
        lodge(token, createSecurity(token, projectId, "700000.00"), depositId, 0)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("paid out")));
    }

    @Test
    @DisplayName("the same certificate number is refused a second row")
    void oneRowPerCertificate() throws Exception {
        String token = adminToken();
        createDeposit(token, "06", "500000.00");

        mockMvc.perform(post("/api/v1/bank-deposits")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositBody("06", "500000.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("already in the register")));
    }

    @Test
    @DisplayName("a maturity date before the issue date is refused")
    void maturityCannotPrecedeIssue() throws Exception {
        mockMvc.perform(post("/api/v1/bank-deposits")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"depositNumber":"%s07","bankName":"SBI","amount":500000.00,
                                 "issuedOn":"2026-06-01","maturityOn":"2026-05-01"}"""
                                .formatted(MARK)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("the certificate carries its own photographs, and keeps them across contracts")
    void photographsBelongToTheCertificate() throws Exception {
        String token = adminToken();
        String depositId = createDeposit(token, "08", "500000.00").get("id").asText();
        String attachmentId = photographOnFile();

        mockMvc.perform(post("/api/v1/bank-deposits/" + depositId + "/photos")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentId\":\"%s\",\"caption\":\"FDR front\"}"
                                .formatted(attachmentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photos.length()").value(1))
                .andExpect(jsonPath("$.photos[0].caption").value("FDR front"));

        // The retried upload links nothing a second time.
        mockMvc.perform(post("/api/v1/bank-deposits/" + depositId + "/photos")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentId\":\"%s\"}".formatted(attachmentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photos.length()").value(1));

        mockMvc.perform(delete("/api/v1/bank-deposits/" + depositId + "/photos/" + attachmentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photos.length()").value(0));
    }

    @Test
    @DisplayName("correcting a mistyped certificate keeps its pledges")
    void amendingKeepsThePledge() throws Exception {
        String token = adminToken();
        String depositId = createDeposit(token, "09", "500000.00").get("id").asText();
        String projectId = createContract(token, "FDR-AMEND");
        lodge(token, createSecurity(token, projectId, "500000.00"), depositId, 0)
                .andExpect(status().isOk());

        long version = json(get("/api/v1/bank-deposits/" + depositId), token)
                .get("version").asLong();
        mockMvc.perform(put("/api/v1/bank-deposits/" + depositId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"depositNumber":"%s09","bankName":"State Bank of India",
                                 "branch":"Almora","amount":505000.00,"issuedOn":"2026-01-10",
                                 "maturityOn":"2027-01-10","version":%d}"""
                                .formatted(MARK, version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(505000.00))
                .andExpect(jsonPath("$.bankName").value("State Bank of India"))
                .andExpect(jsonPath("$.pledgedTo.projectCode").value("FDR-AMEND"));
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode rowFor(JsonNode register, String depositNumber) {
        for (JsonNode row : register.get("deposits")) {
            if (depositNumber.equals(row.get("depositNumber").asText())) {
                return row;
            }
        }
        throw new AssertionError(depositNumber + " is not in the register");
    }

    private JsonNode createDeposit(String token, String suffix, String amount) throws Exception {
        return perform(post("/api/v1/bank-deposits"), token, depositBody(suffix, amount));
    }

    private String depositBody(String suffix, String amount) {
        return """
                {"depositNumber":"%s%s","bankName":"SBI","branch":"Kausani","amount":%s,
                 "issuedOn":"2026-01-10","maturityOn":"2027-01-10","interestRate":6.75}"""
                .formatted(MARK, suffix, amount);
    }

    private String createContract(String token, String code) throws Exception {
        return perform(post("/api/v1/projects"), token, """
                {"code":"%s","name":"%s fdr fixture","contractValue":10000000.00,
                 "quotedCost":7000000.00,"quotedPercent":-30,"workNature":"CONSTRUCTION",
                 "startDate":"2026-01-12","expectedCompletionDate":"2027-06-30",
                 "defectLiabilityMonths":12}
                """.formatted(code, code)).get("id").asText();
    }

    private String createSecurity(String token, String projectId, String amount) throws Exception {
        return perform(post("/api/v1/securities"), token, """
                {"projectId":"%s","securityType":"EMD","instrument":"FDR","amount":%s}
                """.formatted(projectId, amount)).get("id").asText();
    }

    /**
     * The version is the caller's to track — a freshly created security is at 0 and each act
     * moves it on by one. There is no single-security GET to read it back from, and adding one
     * for a test's convenience would be an endpoint nothing else wants.
     */
    private org.springframework.test.web.servlet.ResultActions lodge(
            String token, String securityId, String depositId, long version) throws Exception {
        return mockMvc.perform(post("/api/v1/securities/" + securityId + "/lodge")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"lodgedOn":"2026-01-12","bankDepositId":"%s","version":%d}"""
                        .formatted(depositId, version)));
    }

    private void release(String token, String securityId, long version) throws Exception {
        perform(post("/api/v1/securities/" + securityId + "/release"), token, """
                {"releasedOn":"2026-03-01","reference":"Div/rel/1","version":%d}"""
                .formatted(version));
    }

    private org.springframework.test.web.servlet.ResultActions close(String token, String depositId)
            throws Exception {
        long version = json(get("/api/v1/bank-deposits/" + depositId), token)
                .get("version").asLong();
        return mockMvc.perform(post("/api/v1/bank-deposits/" + depositId + "/close")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"closedOn":"2026-04-01","reason":"matured and encashed","version":%d}"""
                        .formatted(version)));
    }

    /**
     * Written straight into the table rather than posted.
     *
     * <p>Uploading goes through MinIO, which no test environment here has — the endpoint answers
     * 503 and says so honestly. What is under test is the link between a certificate and a
     * photograph of it, not the object store, so the attachment row is stood up directly and the
     * linking endpoint is exercised for real.</p>
     */
    private String photographOnFile() {
        String id = java.util.UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO attachments (id, org_id, owner_entity_type, file_name, content_type,
                                         size_bytes, bucket, object_key, kind)
                VALUES (?::uuid, '10000000-0000-0000-0000-000000000001', 'BANK_DEPOSIT',
                        'fdr.jpg', 'image/jpeg', 2048, 'nirman', ?, 'PHOTO')""",
                id, "test/fdr/" + id);
        return id;
    }

    private JsonNode json(MockHttpServletRequestBuilder request, String token) throws Exception {
        MvcResult result = mockMvc.perform(request.header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode perform(MockHttpServletRequestBuilder request, String token, String body)
            throws Exception {
        MvcResult result = mockMvc.perform(request
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String adminToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"viplove\",\"password\":\"Nirman@123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}

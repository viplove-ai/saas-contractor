package in.nirman.modules.treasury;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The deposits and guarantees a contract locks up, end to end.
 *
 * <p>The contract under test is the one the whole feature exists for: a crore of estimate bid
 * thirty per cent below, which is five lakh of guarantee plus ten lakh of additional guarantee
 * — fifteen lakh of somebody's working capital in a bank before a rupee is billable.</p>
 */
class TreasuryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /**
     * The suite shares one database and every headline figure on the treasury dashboard is a
     * sum over the whole organisation, so one deposit left standing turns up inside another
     * test's company total.
     *
     * <p>The deposits go by SQL rather than through the API, which is the one place in this
     * class that reaches past the endpoints on purpose: the register deliberately refuses to
     * delete anything that has been lodged — that refusal is itself under test in
     * {@link #aLodgedDepositIsNotDeletable()} — so a teardown written against the API could
     * not put the fixture back. The projects still go the ordinary way, because nothing stops
     * them once their deposits are gone.</p>
     */
    @AfterEach
    void removeWhatTheTestLeftStanding() throws Exception {
        jdbc.update("DELETE FROM project_securities WHERE project_id IN "
                + "(SELECT id FROM projects WHERE code LIKE 'TRS%')");
        String token = adminToken();
        for (JsonNode project : liveProjects(token)) {
            if (!project.get("code").asText().startsWith("TRS")) {
                continue;
            }
            mockMvc.perform(delete("/api/v1/projects/" + project.get("id").asText())
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"test fixture cleanup\"}"));
        }
    }

    // ------------------------------------------------------------------ the proposal

    @Test
    @DisplayName("a deep bid is proposed a guarantee on the estimate and an additional one on top")
    void proposalForADeepBid() throws Exception {
        String token = adminToken();
        String projectId = createContract(token, "TRS-DEEP", "10000000.00", "7000000.00");

        JsonNode proposal = json(get("/api/v1/projects/" + projectId + "/securities/proposal"),
                token);

        assertThat(amountOf(proposal, "EMD")).isEqualByComparingTo("250000.00");
        // Five per cent of the crore, not of the seventy lakh actually bid.
        assertThat(amountOf(proposal, "PERFORMANCE_GUARANTEE")).isEqualByComparingTo("500000.00");
        assertThat(amountOf(proposal, "ADDITIONAL_PG")).isEqualByComparingTo("1000000.00");
        // The retention is off the contract: it comes out of the bills, and the bills are the
        // contract's.
        assertThat(amountOf(proposal, "SECURITY_DEPOSIT")).isEqualByComparingTo("175000.00");
    }

    @Test
    @DisplayName("a bid inside the threshold is proposed no additional guarantee at all")
    void noAdditionalGuaranteeOnAShallowBid() throws Exception {
        String token = adminToken();
        String projectId = createContract(token, "TRS-SHALLOW", "10000000.00", "9000000.00");

        JsonNode proposal = json(get("/api/v1/projects/" + projectId + "/securities/proposal"),
                token);
        assertThat(typesIn(proposal)).doesNotContain("ADDITIONAL_PG");
    }

    // ------------------------------------------------------------------ the lifecycle

    @Test
    @DisplayName("an earnest money deposit is lodged, released, and named onto the next tender")
    void earnestMoneyGoesOutAndComesBack() throws Exception {
        String token = adminToken();
        String projectId = createContract(token, "TRS-EMD", "10000000.00", "7000000.00");
        String nextTender = createContract(token, "TRS-NEXT", "5000000.00", "4800000.00");

        String securityId = create(token, projectId, "EMD", "FDR", "250000.00");

        // Nothing is held until it is lodged: a deposit the office has not yet found the money
        // for is exactly the thing the dashboard must show as still to find.
        assertThat(dashboard(token).get("lodgedFromOwnFunds").decimalValue())
                .isEqualByComparingTo("0.00");
        assertThat(dashboard(token).get("awaitingLodgement").decimalValue())
                .isEqualByComparingTo("250000.00");

        JsonNode lodged = perform(post("/api/v1/securities/" + securityId + "/lodge"), token,
                """
                {"lodgedOn":"2026-01-06","referenceNo":"FDR/9912","bankName":"SBI",
                 "branch":"Almora","maturityOn":"2027-01-06",
                 "expectedReleaseOn":"2026-01-16","version":0}
                """);
        assertThat(lodged.get("status").asText()).isEqualTo("LODGED");
        assertThat(lodged.get("heldAmount").decimalValue()).isEqualByComparingTo("250000.00");

        JsonNode afterLodging = dashboard(token);
        assertThat(afterLodging.get("lodgedFromOwnFunds").decimalValue())
                .isEqualByComparingTo("250000.00");
        assertThat(afterLodging.get("retainedFromBills").decimalValue())
                .isEqualByComparingTo("0.00");
        assertThat(afterLodging.get("awaitingLodgement").decimalValue())
                .isEqualByComparingTo("0.00");
        assertThat(bankSlice(afterLodging, "SBI")).isEqualByComparingTo("250000.00");

        JsonNode released = perform(post("/api/v1/securities/" + securityId + "/release"), token,
                """
                {"releasedOn":"2026-01-18","reference":"Div/rel/44","version":1}
                """);
        assertThat(released.get("status").asText()).isEqualTo("RELEASED");
        assertThat(released.get("heldAmount").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(released.get("freeToReuse").asBoolean()).isTrue();

        // Released and unspoken-for is the answer to "what can I bid with".
        JsonNode afterRelease = dashboard(token);
        assertThat(afterRelease.get("freeToReuse").decimalValue())
                .isEqualByComparingTo("250000.00");
        assertThat(afterRelease.get("lodgedFromOwnFunds").decimalValue())
                .isEqualByComparingTo("0.00");

        JsonNode redeployed = perform(post("/api/v1/securities/" + securityId + "/redeploy"),
                token, "{\"toProjectId\":\"" + nextTender + "\",\"version\":2}");
        assertThat(redeployed.get("redeployedToProjectCode").asText()).isEqualTo("TRS-NEXT");
        assertThat(redeployed.get("freeToReuse").asBoolean()).isFalse();
        assertThat(dashboard(token).get("freeToReuse").decimalValue())
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a retention accrues bill by bill and never joins the money the company lodged")
    void retentionAccruesAndStaysApart() throws Exception {
        String token = adminToken();
        String projectId = createContract(token, "TRS-SD", "10000000.00", "7000000.00");
        String depositId = create(token, projectId, "SECURITY_DEPOSIT", "BILL_RETENTION",
                "175000.00");

        perform(post("/api/v1/securities/" + depositId + "/retained"), token,
                """
                {"retainedToDate":"40000.00","asOf":"2026-03-31","version":0}
                """);
        JsonNode afterFirstBill = dashboard(token);
        assertThat(afterFirstBill.get("retainedFromBills").decimalValue())
                .isEqualByComparingTo("40000.00");
        // The whole point of the two columns: this never counted as cash the company put up.
        assertThat(afterFirstBill.get("lodgedFromOwnFunds").decimalValue())
                .isEqualByComparingTo("0.00");
        assertThat(afterFirstBill.get("totalBlocked").decimalValue())
                .isEqualByComparingTo("40000.00");

        // Absolute, not incremental — the running total off the bill summary.
        JsonNode afterSecondBill = perform(post("/api/v1/securities/" + depositId + "/retained"),
                token, """
                {"retainedToDate":"95000.00","asOf":"2026-06-30","version":1}
                """);
        assertThat(afterSecondBill.get("heldAmount").decimalValue())
                .isEqualByComparingTo("95000.00");
        assertThat(dashboard(token).get("retainedFromBills").decimalValue())
                .isEqualByComparingTo("95000.00");
    }

    @Test
    @DisplayName("a retention refuses to be withheld beyond what the deposit is")
    void retentionCannotExceedTheDeposit() throws Exception {
        String token = adminToken();
        String projectId = createContract(token, "TRS-OVER", "10000000.00", "7000000.00");
        String depositId = create(token, projectId, "SECURITY_DEPOSIT", "BILL_RETENTION",
                "175000.00");

        mockMvc.perform(post("/api/v1/securities/" + depositId + "/retained")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"retainedToDate\":\"200000.00\",\"asOf\":\"2026-03-31\","
                                + "\"version\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("more than the deposit is")));
    }

    @Test
    @DisplayName("a retention was never lodged, so it cannot fund the next tender")
    void retentionCannotBeRedeployed() throws Exception {
        String token = adminToken();
        String projectId = createContract(token, "TRS-REUSE", "10000000.00", "7000000.00");
        String other = createContract(token, "TRS-REUSE2", "5000000.00", "4900000.00");
        String depositId = create(token, projectId, "SECURITY_DEPOSIT", "BILL_RETENTION",
                "175000.00");

        perform(post("/api/v1/securities/" + depositId + "/retained"), token,
                "{\"retainedToDate\":\"175000.00\",\"asOf\":\"2026-03-31\",\"version\":0}");
        perform(post("/api/v1/securities/" + depositId + "/release"), token,
                "{\"releasedOn\":\"2028-06-30\",\"reference\":\"final bill\",\"version\":1}");

        mockMvc.perform(post("/api/v1/securities/" + depositId + "/redeploy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toProjectId\":\"" + other + "\",\"version\":2}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("withheld from bills")));
    }

    @Test
    @DisplayName("one retention pot per contract, because two would be two answers to one question")
    void oneRetentionPerContract() throws Exception {
        String token = adminToken();
        String projectId = createContract(token, "TRS-DUP", "10000000.00", "7000000.00");
        create(token, projectId, "SECURITY_DEPOSIT", "BILL_RETENTION", "175000.00");

        mockMvc.perform(post("/api/v1/securities")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(projectId, "SECURITY_DEPOSIT", "BILL_RETENTION",
                                "175000.00")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("earnest money is never a deduction from a bill")
    void earnestMoneyIsNotWithheld() throws Exception {
        String token = adminToken();
        String projectId = createContract(token, "TRS-WRONG", "10000000.00", "7000000.00");

        mockMvc.perform(post("/api/v1/securities")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(projectId, "EMD", "BILL_RETENTION", "250000.00")))
                .andExpect(status().isUnprocessableEntity());
    }

    // ------------------------------------------------------------------ what the office chases

    @Test
    @DisplayName("a deposit past its release date is flagged for attention and counted as releasable")
    void overdueReleaseIsSurfaced() throws Exception {
        String token = adminToken();
        String projectId = createContract(token, "TRS-LATE", "10000000.00", "7000000.00");
        String securityId = create(token, projectId, "PERFORMANCE_GUARANTEE", "FDR", "500000.00");

        perform(post("/api/v1/securities/" + securityId + "/lodge"), token, """
                {"lodgedOn":"2024-02-01","referenceNo":"FDR/1","bankName":"PNB",
                 "maturityOn":"2030-01-01","expectedReleaseOn":"2025-01-01","version":0}
                """);

        JsonNode board = dashboard(token);
        assertThat(board.get("releasableNow").decimalValue()).isEqualByComparingTo("500000.00");
        assertThat(board.get("releasableNowCount").asInt()).isEqualTo(1);
        assertThat(board.get("needsAttention")).anyMatch(
                row -> row.get("id").asText().equals(securityId));
    }

    @Test
    @DisplayName("a deposit maturing before the department is due to release it is flagged for renewal")
    void maturityBeforeReleaseIsFlagged() throws Exception {
        String token = adminToken();
        String projectId = createContract(token, "TRS-RENEW", "10000000.00", "7000000.00");
        String securityId = create(token, projectId, "PERFORMANCE_GUARANTEE", "FDR", "500000.00");

        // Matures inside the quarter; the guarantee has years to run. Left alone, the bank
        // closes the deposit and only the department notices.
        String maturingSoon = LocalDate.now().plusDays(45).toString();
        perform(post("/api/v1/securities/" + securityId + "/lodge"), token, """
                {"lodgedOn":"2024-02-01","referenceNo":"FDR/2","bankName":"PNB",
                 "maturityOn":"%s","expectedReleaseOn":"2030-06-30","version":0}
                """.formatted(maturingSoon));

        JsonNode board = dashboard(token);
        // Not due for release, so it is not in the releasable figure — but it still needs a call.
        assertThat(board.get("releasableNow").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(board.get("needsAttention")).anyMatch(
                row -> row.get("id").asText().equals(securityId));
    }

    @Test
    @DisplayName("a deposit maturing years out is not an alarm, or every guarantee would be one")
    void distantMaturityIsNotAnAlarm() throws Exception {
        String token = adminToken();
        String projectId = createContract(token, "TRS-QUIET", "10000000.00", "7000000.00");
        String securityId = create(token, projectId, "PERFORMANCE_GUARANTEE", "FDR", "500000.00");

        String maturingLater = LocalDate.now().plusDays(200).toString();
        perform(post("/api/v1/securities/" + securityId + "/lodge"), token, """
                {"lodgedOn":"2024-02-01","referenceNo":"FDR/3","bankName":"PNB",
                 "maturityOn":"%s","expectedReleaseOn":"2030-06-30","version":0}
                """.formatted(maturingLater));

        assertThat(dashboard(token).get("needsAttention")).noneMatch(
                row -> row.get("id").asText().equals(securityId));
    }

    @Test
    @DisplayName("the project row adds up the four kinds and names the next release")
    void projectRowSummarisesTheContract() throws Exception {
        String token = adminToken();
        String projectId = createContract(token, "TRS-ROW", "10000000.00", "7000000.00");

        String pg = create(token, projectId, "PERFORMANCE_GUARANTEE", "FDR", "500000.00");
        String apg = create(token, projectId, "ADDITIONAL_PG", "FDR", "1000000.00");
        perform(post("/api/v1/securities/" + pg + "/lodge"), token, """
                {"lodgedOn":"2026-02-01","bankName":"SBI","expectedReleaseOn":"2029-06-30",
                 "version":0}
                """);
        perform(post("/api/v1/securities/" + apg + "/lodge"), token, """
                {"lodgedOn":"2026-02-01","bankName":"SBI","expectedReleaseOn":"2028-06-30",
                 "version":0}
                """);

        JsonNode row = projectRow(dashboard(token), "TRS-ROW");
        assertThat(row.get("performanceGuaranteeHeld").decimalValue())
                .isEqualByComparingTo("500000.00");
        assertThat(row.get("additionalPgHeld").decimalValue()).isEqualByComparingTo("1000000.00");
        assertThat(row.get("totalHeld").decimalValue()).isEqualByComparingTo("1500000.00");
        // The earlier of the two, which is the one the office should be watching.
        assertThat(row.get("nextReleaseOn").asText()).isEqualTo("2028-06-30");
    }

    // ------------------------------------------------------------------ who may see it

    @Test
    @DisplayName("a supervisor cannot read the company's treasury, nor write to the register")
    void theFieldCannotSeeTheCompanysMoney() throws Exception {
        String token = loginToken("vivek");

        mockMvc.perform(get("/api/v1/treasury/dashboard")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/securities")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("30000000-0000-0000-0000-000000000001", "EMD", "FDR",
                                "1000.00")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a project with money outstanding against it refuses to be deleted")
    void aContractHoldingADepositCannotBeDeleted() throws Exception {
        String token = adminToken();
        String projectId = createContract(token, "TRS-HOLD", "10000000.00", "7000000.00");
        String securityId = create(token, projectId, "EMD", "FDR", "250000.00");
        perform(post("/api/v1/securities/" + securityId + "/lodge"), token,
                "{\"lodgedOn\":\"2026-01-06\",\"bankName\":\"SBI\",\"version\":0}");

        mockMvc.perform(delete("/api/v1/projects/" + projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"changed my mind\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("outstanding against it")));

        // And released, it lets go.
        perform(post("/api/v1/securities/" + securityId + "/release"), token,
                "{\"releasedOn\":\"2026-01-18\",\"version\":1}");
        mockMvc.perform(delete("/api/v1/projects/" + projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"changed my mind\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a deposit that has gone out cannot be removed from the register")
    void aLodgedDepositIsNotDeletable() throws Exception {
        String token = adminToken();
        String projectId = createContract(token, "TRS-KEEP", "10000000.00", "7000000.00");
        String securityId = create(token, projectId, "EMD", "FDR", "250000.00");
        perform(post("/api/v1/securities/" + securityId + "/lodge"), token,
                "{\"lodgedOn\":\"2026-01-06\",\"bankName\":\"SBI\",\"version\":0}");

        mockMvc.perform(delete("/api/v1/securities/" + securityId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The notice's figure goes in {@code contractValue} and the bid in {@code quotedCost} —
     * V41's naming, and the way the NIT import has always filled the first of the two. The
     * dates are the work's own: V41 dropped the three letter dates the release schedule used
     * to be counted from, so the earnest money is now due back when work starts and the
     * guarantee a year past completion.
     */
    private String createContract(String token, String code, String estimate, String bid)
            throws Exception {
        String body = """
                {"code":"%s","name":"%s treasury fixture","contractValue":%s,
                 "quotedCost":%s,"quotedPercent":-30,"workNature":"CONSTRUCTION",
                 "startDate":"2026-01-12","expectedCompletionDate":"2027-06-30",
                 "defectLiabilityMonths":12}
                """.formatted(code, code, estimate, bid);
        return perform(post("/api/v1/projects"), token, body).get("id").asText();
    }

    private String createBody(String projectId, String type, String instrument, String amount) {
        return """
                {"projectId":"%s","securityType":"%s","instrument":"%s","amount":%s}
                """.formatted(projectId, type, instrument, amount);
    }

    private String create(String token, String projectId, String type, String instrument,
                          String amount) throws Exception {
        return perform(post("/api/v1/securities"), token,
                createBody(projectId, type, instrument, amount)).get("id").asText();
    }

    private JsonNode dashboard(String token) throws Exception {
        return json(get("/api/v1/treasury/dashboard"), token);
    }

    private static java.math.BigDecimal amountOf(JsonNode proposals, String type) {
        for (JsonNode proposal : proposals) {
            if (proposal.get("securityType").asText().equals(type)) {
                return proposal.get("amount").decimalValue();
            }
        }
        throw new AssertionError("no proposal of type " + type);
    }

    private static java.util.List<String> typesIn(JsonNode proposals) {
        java.util.List<String> types = new java.util.ArrayList<>();
        proposals.forEach(proposal -> types.add(proposal.get("securityType").asText()));
        return types;
    }

    private static java.math.BigDecimal bankSlice(JsonNode dashboard, String bank) {
        for (JsonNode slice : dashboard.get("byBank")) {
            if (slice.get("bankName").asText().equals(bank)) {
                return slice.get("held").decimalValue();
            }
        }
        throw new AssertionError("no bank slice for " + bank);
    }

    private static JsonNode projectRow(JsonNode dashboard, String code) {
        for (JsonNode row : dashboard.get("projects")) {
            if (code.equals(row.path("projectCode").asText())) {
                return row;
            }
        }
        throw new AssertionError("no project row for " + code);
    }

    private JsonNode liveProjects(String token) throws Exception {
        return json(get("/api/v1/projects?size=100"), token).get("content");
    }

    private JsonNode json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
                                  request, String token) throws Exception {
        MvcResult result = mockMvc.perform(request.header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode perform(org.springframework.test.web.servlet.request
                                     .MockHttpServletRequestBuilder request,
                             String token, String body) throws Exception {
        MvcResult result = mockMvc.perform(request
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String adminToken() throws Exception {
        return loginToken("viplove");
    }

    private String loginToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Nirman@123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}

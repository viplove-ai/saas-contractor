package in.nirman.modules.dashboard;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Phase 6 exit criterion about the dashboards: <b>material purchased, consumed and inventory
 * value are three separate figures that add up.</b>
 *
 * <p>Two halves, and both are needed. That they <em>add up</em> is arithmetic, checked as the
 * identity the response carries — {@code opening + received − consumed − residual = inventory
 * value} — and checked again against the ledger and the balance cache in SQL, so the dashboard
 * cannot satisfy its own identity with three figures it invented. That they are <em>separate</em>
 * is the part with money riding on it: purchased comes from the bills and consumed comes from the
 * ledger, they are different numbers about different events, and the failure this guards against
 * is the tidy-looking one where somebody adds material purchases to a cost that already counts
 * material at issue and overstates the project by the value of its own stockyard.</p>
 *
 * <p>The window is June 2025, which is where V903 puts two days of a real site diary.</p>
 */
class DashboardIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";
    private static final String SITE_B = "31000000-0000-0000-0000-000000000002";
    private static final String ORG = "10000000-0000-0000-0000-000000000001";
    private static final LocalDate FROM = LocalDate.of(2025, 6, 1);
    private static final LocalDate TO = LocalDate.of(2025, 6, 30);

    /** The tolerance the service itself calls reconciled. Money, to the paisa. */
    private static final BigDecimal ROUNDING = new BigDecimal("0.05");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------------------------------------------------------------- the exit criterion

    /**
     * The identity, on the company dashboard, term by term. Not "the numbers look plausible" —
     * the response states an equation about itself and this is that equation.
     */
    @Test
    @DisplayName("opening plus received less consumed lands on the inventory value the balances hold")
    void theThreeMaterialFiguresAddUp() throws Exception {
        JsonNode material = companyDashboard().get("material");

        BigDecimal opening = decimal(material, "openingValue");
        BigDecimal received = decimal(material, "received");
        BigDecimal consumed = decimal(material, "consumed");
        BigDecimal inventoryValue = decimal(material, "inventoryValue");
        BigDecimal residual = decimal(material, "residual");

        assertThat(opening.add(received).subtract(consumed).subtract(residual))
                .as("opening + received − consumed − residual = inventory value")
                .isEqualByComparingTo(inventoryValue);
    }

    /**
     * The same three figures, each against the table it claims to reflect. This is the assertion
     * that catches a dashboard whose arithmetic is internally consistent and externally wrong.
     */
    @Test
    @DisplayName("each material figure matches the ledger and the balance cache it comes from")
    void theThreeMaterialFiguresMatchTheirSources() throws Exception {
        JsonNode material = companyDashboard().get("material");

        assertThat(decimal(material, "consumed"))
                .as("consumed is issues plus wastage out of the ledger")
                .isEqualByComparingTo(ledgerValue("'ISSUE', 'WASTAGE', 'DAMAGE'"));
        assertThat(decimal(material, "received"))
                .as("received is receipts, returns and opening stock out of the ledger")
                .isEqualByComparingTo(ledgerValue("'RECEIPT', 'RETURN', 'OPENING_STOCK'"));
        assertThat(decimal(material, "inventoryValue"))
                .as("inventory value is what the balance cache holds right now")
                .isEqualByComparingTo(inventoryValueFromBalances());

        // Issued and wasted are carried apart and sum to consumed: a site losing material to
        // breakage needs that as its own number, not folded into what it used.
        assertThat(decimal(material, "issued").add(decimal(material, "wasted")))
                .isEqualByComparingTo(decimal(material, "consumed"));
    }

    /**
     * The separation, which is the half of the criterion that is about money rather than
     * arithmetic. Purchased is from the bills; consumed is from the ledger. The dashboard reports
     * both and adds neither to the other.
     */
    @Test
    @DisplayName("material purchased is a separate figure from consumed and from received")
    void purchasedIsItsOwnFigure() throws Exception {
        JsonNode dashboard = companyDashboard();
        JsonNode material = dashboard.get("material");

        BigDecimal purchased = decimal(material, "purchased");
        assertThat(purchased)
                .as("purchased comes from expenses flagged as material purchases")
                .isEqualByComparingTo(materialPurchasesFromExpenses());

        // The gap between a bill and a lorry is a real figure with its own name, not a defect.
        assertThat(decimal(material, "purchasedNotReceived"))
                .isEqualByComparingTo(purchased.subtract(decimal(material, "received")));

        // The criterion's point: cost counts material once, at issue. Purchased is not in it.
        assertThat(decimal(dashboard, "costIncurred"))
                .as("cost incurred is labour + material consumed + other expense, and nothing else")
                .isEqualByComparingTo(decimal(dashboard, "labourCost")
                        .add(decimal(dashboard, "materialConsumed"))
                        .add(decimal(dashboard, "otherCost")));
        assertThat(decimal(dashboard, "materialConsumed"))
                .isEqualByComparingTo(decimal(material, "consumed"));
    }

    /**
     * Total booked is cash and cost incurred is cost. A reader who cannot see the difference adds
     * the wrong one to something, so both are on the response and they are not the same number.
     */
    @Test
    @DisplayName("total booked and cost incurred are carried apart, and the caveat says why")
    void cashAndCostAreNotTheSameNumber() throws Exception {
        JsonNode dashboard = companyDashboard();

        assertThat(decimal(dashboard, "totalBooked"))
                .as("the seed books material purchases, so cash exceeds cost")
                .isGreaterThan(decimal(dashboard, "costIncurred"));
        assertThat(dashboard.get("caveat").asText())
                .contains("Total booked")
                .contains("is not a cost figure");
    }

    /** A reconciled position says so, and says what the residual would have meant. */
    @Test
    @DisplayName("a reconciled material position is declared reconciled")
    void reconciliationIsDeclared() throws Exception {
        JsonNode material = companyDashboard().get("material");

        assertThat(decimal(material, "residual").abs())
                .isLessThanOrEqualTo(ROUNDING);
        assertThat(material.get("reconciles").asBoolean()).isTrue();
        assertThat(material.get("note").asText()).contains("three figures are the whole picture");
    }

    // ---------------------------------------------------------------- site dashboard

    @Test
    @DisplayName("the site dashboard reports material with the same three figures")
    void theSiteDashboardUsesTheSameMaterialShape() throws Exception {
        JsonNode material = siteDashboard(SITE_A, "uttam").get("material");

        assertThat(material.get("openingValue")).isNotNull();
        assertThat(decimal(material, "openingValue")
                .add(decimal(material, "received"))
                .subtract(decimal(material, "consumed"))
                .subtract(decimal(material, "residual")))
                .isEqualByComparingTo(decimal(material, "inventoryValue"));
    }

    /**
     * The labour tile keeps the unsigned part of the wage bill visible. V903 leaves 09 and 10 June
     * submitted rather than verified, so this site's whole labour cost is still provisional.
     */
    @Test
    @DisplayName("the labour tile shows the unverified wage bill apart from the verified one")
    void theLabourTileSplitsVerifiedFromUnverified() throws Exception {
        JsonNode labour = siteDashboard(SITE_A, "uttam").get("labour");

        assertThat(decimal(labour, "cost"))
                .isEqualByComparingTo(decimal(labour, "verifiedCost")
                        .add(decimal(labour, "unverifiedCost")));
        assertThat(decimal(labour, "unverifiedCost")).isGreaterThan(BigDecimal.ZERO);
        assertThat(labour.get("pendingVerification").asInt()).isGreaterThan(0);
    }

    /** V903 verifies the 9 June report and leaves the 10th a draft. The tile counts both. */
    @Test
    @DisplayName("the DPR tile counts the verified report, the draft, and the days with neither")
    void theDprTileCountsTheDiary() throws Exception {
        JsonNode dpr = siteDashboard(SITE_A, "uttam").get("dpr");

        assertThat(dpr.get("reportsInRange").asInt()).isEqualTo(2);
        assertThat(dpr.get("verified").asInt()).isEqualTo(1);
        assertThat(dpr.get("draft").asInt()).isEqualTo(1);
        // June 2025 is long past, so every other day of it is a day nobody reported.
        assertThat(dpr.get("daysWithoutReport").size()).isGreaterThan(0);
    }

    /** Percent complete is by value, so a project's cheap lines cannot flatter it. */
    @Test
    @DisplayName("contract progress is reported by value rather than by line count")
    void progressIsByValue() throws Exception {
        JsonNode progress = siteDashboard(SITE_A, "uttam").get("progress");

        assertThat(decimal(progress, "contractValue")).isGreaterThan(BigDecimal.ZERO);
        assertThat(decimal(progress, "valueOfWorkDone"))
                .isLessThanOrEqualTo(decimal(progress, "contractValue"));
        assertThat(progress.get("itemsTotal").asInt()).isGreaterThan(0);
    }

    // ---------------------------------------------------------------- who may look

    /**
     * {@code dashboard:company} is the administrator's and the accountant's. A supervisor asking
     * for the whole firm's money is refused — the same split that keeps the wage summary away
     * from the attendance register.
     */
    @Test
    @DisplayName("a supervisor cannot open the company dashboard")
    void theCompanyDashboardIsNotForSupervisors() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/admin")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .header("Authorization", "Bearer " + loginToken("vivek")))
                .andExpect(status().isForbidden());
    }

    /** He gets his own site instead, which is a different question rather than a smaller one. */
    @Test
    @DisplayName("a supervisor sees the dashboard of the site he is posted to")
    void aSupervisorSeesHisOwnSite() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/site/" + SITE_A)
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .header("Authorization", "Bearer " + loginToken("vivek")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.siteCode").value("KSN-A"));
    }

    /** Site scope is a fence on the dashboard like everywhere else. */
    @Test
    @DisplayName("a supervisor cannot open the dashboard of a site he is not posted to")
    void siteScopeAppliesToTheSiteDashboard() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/site/" + SITE_B)
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .header("Authorization", "Bearer " + loginToken("vivek")))
                .andExpect(status().isForbidden());
    }

    /** A dashboard draws a point per day, so an unbounded range is refused rather than served. */
    @Test
    @DisplayName("a range wider than a year is refused")
    void anAbsurdRangeIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/admin")
                        .param("from", "2020-01-01").param("to", "2026-01-01")
                        .header("Authorization", "Bearer " + loginToken("viplove")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("dashboard.range-too-wide"));
    }

    /** The trend is three series, because one blended line hides the story it is read for. */
    @Test
    @DisplayName("the cost trend keeps labour, material and other cost as separate series")
    void theTrendIsNotBlended() throws Exception {
        JsonNode trend = companyDashboard().get("trend");

        assertThat(trend.size()).isEqualTo(30);   // a point per day of June, gaps included
        JsonNode day = trend.get(0);
        assertThat(day.has("labourCost")).isTrue();
        assertThat(day.has("materialConsumed")).isTrue();
        assertThat(day.has("otherCost")).isTrue();
    }

    // ---------------------------------------------------------------- data quality

    /**
     * A data-quality dashboard that only counts problems gets ignored, so every finding carries
     * what to do about it and the examples behind the count.
     */
    @Test
    @DisplayName("every data-quality finding names what to do and shows its evidence")
    void everyFindingIsActionable() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/dashboard/data-quality")
                        .param("siteId", SITE_A)
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .header("Authorization", "Bearer " + loginToken("uttam")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode findings = body.get("findings");

        assertThat(findings.size()).isGreaterThan(0);
        findings.forEach(finding -> {
            assertThat(finding.get("code").asText()).isNotBlank();
            assertThat(finding.get("whatToDo").asText()).isNotBlank();
            assertThat(finding.get("severity").asText()).isIn("ACT", "WATCH");
            assertThat(finding.get("count").asInt()).isGreaterThan(0);
        });

        // The two counts partition the findings: every one is either something to act on or
        // something to watch, and nothing is uncounted.
        assertThat(body.get("actCount").asInt() + body.get("watchCount").asInt())
                .isEqualTo(findings.size());
    }

    /** Unverified attendance is the finding V903 guarantees, since it leaves the muster unsigned. */
    @Test
    @DisplayName("attendance left unverified is reported as a finding")
    void unverifiedAttendanceIsAFinding() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/dashboard/data-quality")
                        .param("siteId", SITE_A)
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .header("Authorization", "Bearer " + loginToken("uttam")))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode findings = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("findings");
        boolean found = false;
        for (JsonNode finding : findings) {
            if (finding.get("code").asText().contains("attendance")) {
                found = true;
            }
        }
        assertThat(found)
                .as("the seed leaves June attendance submitted but unverified")
                .isTrue();
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode companyDashboard() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/dashboard/admin")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .header("Authorization", "Bearer " + loginToken("viplove")))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode siteDashboard(String siteId, String username) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/dashboard/site/" + siteId)
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .header("Authorization", "Bearer " + loginToken(username)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** The same figure, summed straight out of the ledger the dashboard claims to reflect. */
    private BigDecimal ledgerValue(String txnTypes) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(value), 0) FROM stock_transactions
                WHERE org_id = ?::uuid AND txn_date BETWEEN ? AND ?
                  AND txn_type IN (%s)
                """.formatted(txnTypes), BigDecimal.class, ORG, FROM, TO);
    }

    private BigDecimal inventoryValueFromBalances() {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(total_value), 0) FROM stock_balances b
                JOIN stores s ON s.id = b.store_id
                WHERE s.org_id = ?::uuid
                """, BigDecimal.class, ORG);
    }

    private BigDecimal materialPurchasesFromExpenses() {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(total_amount), 0) FROM expenses
                WHERE org_id = ?::uuid AND expense_date BETWEEN ? AND ?
                  AND workflow_status <> 'VOIDED' AND is_material_purchase
                """, BigDecimal.class, ORG, FROM, TO);
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

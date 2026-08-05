package in.nirman.modules.inventory;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Phase 4 exit criterion about estimates: <b>variance is computed only over the BOQ
 * scope an estimate covers, with uncovered scope shown separately.</b>
 *
 * <p>This is the completeness trap from docs/09 section B, and it is the one failure mode
 * that would quietly destroy the feature's credibility rather than break it. The Kausani
 * cement take-off totals 263 bags against 460 received. Reported naively that is a 75%
 * overrun; in fact the take-off covered RCC and two brickwork lines and never touched
 * plaster or flooring. Tell an engineer he wasted 197 bags he did not waste and he never
 * opens the dashboard again.</p>
 *
 * <p>The seed (V901) sets this up exactly: cement is estimated against B-001 to B-004 only,
 * while B-005 plaster and B-006 flooring are real work with real cement in them and no
 * estimate at all. Steel is estimated from a bar bending schedule covering the whole
 * structure, and is the clean comparison.</p>
 */
class MaterialEstimateVarianceIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String PROJECT = "30000000-0000-0000-0000-000000000001";
    private static final String STORE_A = "32000000-0000-0000-0000-000000000001";
    private static final String CEMENT = "40000000-0000-0000-0000-000000000001";
    private static final String STEEL = "40000000-0000-0000-0000-000000000002";
    private static final String BOQ_RCC_COLUMNS = "70000000-0000-0000-0000-000000000001";
    private static final String BOQ_BRICKWORK_14 = "70000000-0000-0000-0000-000000000003";
    private static final String BOQ_PLASTER = "70000000-0000-0000-0000-000000000005";
    private static final String BOQ_STEEL = "70000000-0000-0000-0000-000000000007";
    private static final String BOQ_SYNTHETIC = "70000000-0000-0000-0000-000000000099";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * These tests consume seeded cement and steel, which other classes read the balance of.
     * Removing the movements and rebuilding the balance from the ledger — rather than
     * setting a number — is also a small proof that the cache really is derivable.
     */
    @AfterEach
    void undoTestConsumption() {
        jdbc.update("""
                DELETE FROM material_issue_items WHERE issue_id IN
                    (SELECT id FROM material_issues WHERE purpose LIKE 'VARIANCE-TEST%')""");
        jdbc.update("DELETE FROM stock_transactions WHERE reason LIKE 'VARIANCE-TEST%'");
        jdbc.update("DELETE FROM material_issues WHERE purpose LIKE 'VARIANCE-TEST%'");
        jdbc.update("""
                UPDATE stock_balances b SET
                    quantity_base = COALESCE(l.qty, 0),
                    stock_value   = COALESCE(l.value, 0)
                FROM (SELECT store_id, material_id,
                             SUM(quantity_base * direction) AS qty,
                             SUM(value * direction)         AS value
                      FROM stock_transactions GROUP BY store_id, material_id) l
                WHERE b.store_id = l.store_id AND b.material_id = l.material_id""");
    }

    /**
     * The trap itself. Cement is issued to one estimated line and one unestimated one; the
     * variance must see only the first, and must say out loud that it did.
     */
    @Test
    @DisplayName("variance covers only the estimated BOQ scope, and names what fell outside it")
    void varianceIsMeasuredOnlyOverTheScopeTheEstimateCovers() throws Exception {
        String uttam = loginToken("uttam");
        String bagUnit = unitId(uttam, "BAG");

        // 80 bags against RCC columns, which the take-off estimated at 90 (+3% = 92.7).
        issueAndApprove(uttam, CEMENT, bagUnit, "80", BOQ_RCC_COLUMNS);
        // 30 bags into plaster, which nobody estimated. Not an overrun — different work.
        issueAndApprove(uttam, CEMENT, bagUnit, "30", BOQ_PLASTER);

        JsonNode cement = varianceFor(uttam, "EXECUTION_TAKEOFF", CEMENT);

        // Estimated: 90 + 110 + 31.5 + 31.25 = 262.75, plus 3% wastage = 270.6325.
        assertThat(decimal(cement, "estimatedQty")).isEqualByComparingTo("270.6325");

        // Only the 80 bags charged to an estimated line count towards the comparison.
        assertThat(decimal(cement, "issuedWithinScope")).isEqualByComparingTo("80.0000");
        assertThat(decimal(cement, "variance")).isEqualByComparingTo("-190.6325");

        // The plaster cement is reported beside the variance, not inside it.
        assertThat(cement.get("scopeComplete").asBoolean()).isFalse();
        assertThat(decimal(cement, "issuedOutsideScope")).isEqualByComparingTo("30.0000");

        JsonNode uncovered = cement.get("uncovered");
        assertThat(uncovered).hasSize(1);
        assertThat(uncovered.get(0).get("itemNumber").asText()).isEqualTo("B-005");
        assertThat(new BigDecimal(uncovered.get(0).get("issuedQty").asText()))
                .isEqualByComparingTo("30.0000");
    }

    /**
     * Material issued against no work item at all is the other half of the same problem: it
     * is consumption nobody can attribute, and folding it into the variance would make the
     * variance a guess.
     */
    @Test
    @DisplayName("material issued against no work item is reported separately, not as an overrun")
    void unallocatedConsumptionIsHeldApart() throws Exception {
        String uttam = loginToken("uttam");
        String bagUnit = unitId(uttam, "BAG");

        issueAndApprove(uttam, CEMENT, bagUnit, "25", BOQ_BRICKWORK_14);
        issueAndApprove(uttam, CEMENT, bagUnit, "15", null);   // purpose only, no work item

        JsonNode cement = varianceFor(uttam, "EXECUTION_TAKEOFF", CEMENT);

        assertThat(decimal(cement, "issuedWithinScope")).isEqualByComparingTo("25.0000");
        assertThat(decimal(cement, "issuedWithoutBoqItem")).isEqualByComparingTo("15.0000");
        assertThat(cement.get("scopeComplete").asBoolean()).isFalse();

        // The row for unattributed consumption carries no work item at all — and the API
        // omits null fields, so its absence is what "no work item" looks like on the wire.
        JsonNode unallocated = cement.get("uncovered").get(0);
        assertThat(unallocated.has("boqItemId")).isFalse();
        assertThat(unallocated.get("description").asText()).isEqualTo("Issued without a work item");
        assertThat(new BigDecimal(unallocated.get("issuedQty").asText()))
                .isEqualByComparingTo("15.0000");
    }

    /**
     * Steel is the case where the comparison can be read as the whole story, because a bar
     * bending schedule covers the whole structure. The report has to say that too — an
     * honest tool has to be able to tell you when the number <i>is</i> trustworthy.
     */
    @Test
    @DisplayName("a complete estimate reports a complete scope, and says so")
    void completeScopeIsReportedAsComplete() throws Exception {
        String uttam = loginToken("uttam");
        String kgUnit = unitId(uttam, "KG");

        issueAndApprove(uttam, STEEL, kgUnit, "1200", BOQ_STEEL);

        JsonNode steel = varianceFor(uttam, "BBS", STEEL);
        assertThat(decimal(steel, "estimatedQty")).isEqualByComparingTo("4641.0000");   // +2%
        assertThat(decimal(steel, "issuedWithinScope")).isEqualByComparingTo("1200.0000");
        assertThat(steel.get("scopeComplete").asBoolean()).isTrue();
        assertThat(steel.get("uncovered")).isEmpty();
        assertThat(steel.get("issuedOutsideScope").decimalValue()).isEqualByComparingTo("0");
    }

    /**
     * The tender said 4,200 kg and the drawings demand 4,550. That gap is the single most
     * commercially useful number in the set, and keeping the two levels apart is the only
     * way it survives.
     */
    @Test
    @DisplayName("the tender and the drawings are compared separately, never collapsed into one")
    void estimateLevelsAreKeptApart() throws Exception {
        String uttam = loginToken("uttam");

        JsonNode tender = varianceFor(uttam, "TENDER_BOQ", STEEL);
        JsonNode bbs = varianceFor(uttam, "BBS", STEEL);

        assertThat(decimal(tender, "estimatedQty")).isEqualByComparingTo("4200.0000");
        assertThat(decimal(bbs, "estimatedQty")).isEqualByComparingTo("4641.0000");
    }

    @Test
    @DisplayName("the report carries a plain-English caveat about what its variance covers")
    void reportExplainsItsOwnCoverage() throws Exception {
        String uttam = loginToken("uttam");
        String bagUnit = unitId(uttam, "BAG");
        issueAndApprove(uttam, CEMENT, bagUnit, "10", BOQ_PLASTER);

        MvcResult result = variance(uttam, "EXECUTION_TAKEOFF", null);
        String caveat = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("caveat").asText();
        assertThat(caveat).contains("only over the BOQ items the estimate covers");
    }

    /** A revision supersedes; it does not overwrite. Last month's figure stays readable. */
    @Test
    @DisplayName("revising an estimate supersedes the old one instead of rewriting it")
    void revisingSupersedesRatherThanOverwrites() throws Exception {
        String uttam = loginToken("uttam");

        mockMvc.perform(post("/api/v1/material-estimates")
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"%s","boqItemId":"%s","materialId":"%s",
                                 "estimateLevel":"BBS","estimatedQtyBase":4800,
                                 "wastagePercent":2,"sourceRef":"BBS/KSN/R3"}"""
                                .formatted(PROJECT, BOQ_STEEL, STEEL)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.estimatedQtyBase").value(4800.0000));

        // One live row, and the superseded one still on file.
        Integer live = jdbc.queryForObject("""
                SELECT count(*) FROM material_estimates
                WHERE boq_item_id = ?::uuid AND estimate_level = 'BBS' AND superseded_at IS NULL""",
                Integer.class, BOQ_STEEL);
        Integer superseded = jdbc.queryForObject("""
                SELECT count(*) FROM material_estimates
                WHERE boq_item_id = ?::uuid AND estimate_level = 'BBS'
                  AND superseded_at IS NOT NULL""", Integer.class, BOQ_STEEL);
        assertThat(live).isEqualTo(1);
        assertThat(superseded).isEqualTo(1);

        // Put the seed back, so the other tests in the class still see 4,550.
        jdbc.update("""
                DELETE FROM material_estimates
                WHERE boq_item_id = ?::uuid AND estimate_level = 'BBS' AND revision = 2""",
                BOQ_STEEL);
        jdbc.update("""
                UPDATE material_estimates SET superseded_at = NULL
                WHERE boq_item_id = ?::uuid AND estimate_level = 'BBS'""", BOQ_STEEL);
    }

    @Test
    @DisplayName("an estimate derived from a consumption norm applies the BOQ line's quantity")
    void estimateCanBeDerivedFromANorm() throws Exception {
        String uttam = loginToken("uttam");

        // B-005 plaster has no norm; B-003 brickwork 1:4 does, at 0.63 bags per cum of 50.
        mockMvc.perform(post("/api/v1/material-estimates/derive")
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"boqItemId":"%s","materialId":"%s","workSubType":"1:4",
                                 "wastagePercent":0,"estimateLevel":"REVISED"}"""
                                .formatted(BOQ_BRICKWORK_14, CEMENT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estimatedQtyBase").value(31.5000));

        jdbc.update("""
                DELETE FROM material_estimates
                WHERE boq_item_id = ?::uuid AND estimate_level = 'REVISED'""", BOQ_BRICKWORK_14);
    }

    @Test
    @DisplayName("nothing can be charged to a parser's reconciliation placeholder")
    void syntheticBoqItemsRefuseCost() throws Exception {
        String uttam = loginToken("uttam");
        String bagUnit = unitId(uttam, "BAG");

        mockMvc.perform(post("/api/v1/inventory/issues")
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","storeId":"%s","issueDate":"%s",
                                 "boqItemId":"%s","purpose":"VARIANCE-TEST synthetic",
                                 "lines":[{"materialId":"%s","unitId":"%s","quantity":1}]}"""
                                .formatted(UUID.randomUUID(), STORE_A, LocalDate.now(),
                                        BOQ_SYNTHETIC, CEMENT, bagUnit)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("reconciliation placeholder")));
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode varianceFor(String token, String level, String materialId) throws Exception {
        MvcResult result = variance(token, level, materialId);
        JsonNode materials = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("materials");
        assertThat(materials).as("one row for the material asked for").hasSize(1);
        return materials.get(0);
    }

    private MvcResult variance(String token, String level, String materialId) throws Exception {
        var request = get("/api/v1/material-estimates/variance")
                .param("projectId", PROJECT)
                .param("level", level)
                .header("Authorization", "Bearer " + token);
        if (materialId != null) {
            request = request.param("materialId", materialId);
        }
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asText());
    }

    private void issueAndApprove(String token, String materialId, String unitId, String quantity,
                                 String boqItemId) throws Exception {
        String boqClause = boqItemId == null ? "" : "\"boqItemId\":\"%s\",".formatted(boqItemId);
        MvcResult result = mockMvc.perform(post("/api/v1/inventory/issues")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","storeId":"%s","issueDate":"%s",%s
                                 "purpose":"VARIANCE-TEST consumption",
                                 "lines":[{"materialId":"%s","unitId":"%s","quantity":%s}]}"""
                                .formatted(UUID.randomUUID(), STORE_A, LocalDate.now(), boqClause,
                                        materialId, unitId, quantity)))
                .andExpect(status().isCreated())
                .andReturn();
        String issueId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
        mockMvc.perform(post("/api/v1/inventory/issues/" + issueId + "/approve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isOk());
    }

    private String unitId(String token, String code) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/units")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode unit : objectMapper.readTree(result.getResponse().getContentAsString())) {
            if (code.equals(unit.get("code").asText())) {
                return unit.get("id").asText();
            }
        }
        throw new IllegalStateException("The dev seed has no unit " + code);
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

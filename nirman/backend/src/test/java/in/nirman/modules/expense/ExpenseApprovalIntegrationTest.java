package in.nirman.modules.expense;

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
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Phase 5 exit criterion about editing — <b>an approved expense cannot be silently
 * edited</b> — and the two-level routing behind it.
 *
 * <p>The routing is the interesting half. docs/09 open question 2 found two approval systems
 * coexisting and ruled that the generic engine is authoritative and the per-module status
 * column is a cache written by its event. These tests assert both sides of that: that the
 * chain decides, and that {@code expenses.workflow_status} follows it exactly.</p>
 *
 * <p>The seeded rules (V902) put every expense past the engineer and anything from ₹25,000
 * past the administrator too, which is what makes "one level or two" testable with two
 * amounts.</p>
 */
class ExpenseApprovalIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";
    private static final String CAT_MISC_SITE = "MISC-SITE";
    private static final String TEST_MARK = "PHASE5-APPROVAL";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeTestExpenses() {
        jdbc.update("""
                DELETE FROM approvals WHERE entity_id IN
                    (SELECT id FROM expenses WHERE description LIKE ?)""", TEST_MARK + "%");
        jdbc.update("DELETE FROM payments WHERE expense_id IN "
                + "(SELECT id FROM expenses WHERE description LIKE ?)", TEST_MARK + "%");
        jdbc.update("DELETE FROM expenses WHERE description LIKE ?", TEST_MARK + "%");
    }

    /** A small bill passes the engineer and is done. One level, because the rules say so. */
    @Test
    @DisplayName("a bill below the threshold is approved by the engineer alone")
    void smallExpenseNeedsOneLevel() throws Exception {
        String uttam = loginToken("uttam");   // ADMIN + ENGINEER + ACCOUNTANT
        String id = createExpense(uttam, "4000", "cartage");
        submit(uttam, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$.pendingWithRole").value("ENGINEER"));

        decide(uttam, id, "APPROVE", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("APPROVED"));

        // Nothing is left waiting: the chain closed with the record.
        assertThat(pendingCount(id)).isZero();
    }

    /**
     * A large bill passes two people, and the state in between has its own name. That
     * intermediate {@code L1_APPROVED} is the reason the status column cannot simply mirror
     * the approval row.
     */
    @Test
    @DisplayName("a bill at or above the threshold needs the engineer and then the administrator")
    void largeExpenseNeedsTwoLevels() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "60000", "steel invoice");
        submit(uttam, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingWithRole").value("ENGINEER"));

        decide(uttam, id, "APPROVE", null).andExpect(status().isOk())
                // Past one, waiting on the next — and the status says exactly that.
                .andExpect(jsonPath("$.workflowStatus").value("L1_APPROVED"))
                .andExpect(jsonPath("$.pendingLevel").value(2))
                .andExpect(jsonPath("$.pendingWithRole").value("ADMIN"));

        decide(uttam, id, "APPROVE", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("APPROVED"));
    }

    /** The threshold is inclusive at the bottom: ₹25,000 exactly goes to the administrator. */
    @Test
    @DisplayName("the threshold is inclusive, so a bill of exactly the limit needs both levels")
    void thresholdIsInclusive() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "25000", "exactly at the line");
        submit(uttam, id).andExpect(status().isOk());
        decide(uttam, id, "APPROVE", null)
                .andExpect(jsonPath("$.workflowStatus").value("L1_APPROVED"));
    }

    @Test
    @DisplayName("an approved expense cannot be silently edited")
    void approvedExpenseCannotBeEdited() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "4000", "cartage");
        submit(uttam, id).andExpect(status().isOk());
        decide(uttam, id, "APPROVE", null).andExpect(status().isOk());

        long version = versionOf(uttam, id);
        mockMvc.perform(put("/api/v1/expenses/" + id)
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("999999", version)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("Void it and book a replacement")));

        // And the figure is untouched.
        mockMvc.perform(get("/api/v1/expenses/" + id)
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(jsonPath("$.totalAmount").value(4000.00));
    }

    /** A submitted expense is out of the author's hands too — the edit window closes early. */
    @Test
    @DisplayName("a submitted expense is already out of the author's hands")
    void submittedExpenseCannotBeEdited() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "4000", "cartage");
        long version = versionOf(uttam, id);
        submit(uttam, id).andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/expenses/" + id)
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("5000", version + 1)))
                .andExpect(status().isUnprocessableEntity());
    }

    /**
     * Returned is not a softer rejection: the record goes back editable, and the author
     * fixes it and sends it again. Collapsing the two would make every correction look like
     * a refusal in the history.
     */
    @Test
    @DisplayName("a returned expense becomes editable again and can be resubmitted")
    void returnedExpenseIsEditableAgain() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "4000", "cartage");
        submit(uttam, id).andExpect(status().isOk());
        decide(uttam, id, "RETURN", "Bill number missing").andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("RETURNED"))
                .andExpect(jsonPath("$.rejectionReason").value("Bill number missing"));

        long version = versionOf(uttam, id);
        mockMvc.perform(put("/api/v1/expenses/" + id)
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("4000", version)))
                .andExpect(status().isOk());
        submit(uttam, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("SUBMITTED"));
    }

    /** The queue belongs to the job. A supervisor holds no approval role and cannot decide. */
    @Test
    @DisplayName("a supervisor cannot approve his own expense")
    void supervisorCannotApprove() throws Exception {
        String vivek = loginToken("vivek");
        String id = createExpense(vivek, "3000", "site consumables");
        submit(vivek, id).andExpect(status().isOk());

        decide(vivek, id, "APPROVE", null).andExpect(status().isForbidden());
        assertThat(statusOf(id)).isEqualTo("SUBMITTED");
    }

    /**
     * The generic queue and the per-record endpoint are two doors onto one engine, so a
     * decision taken from the queue moves the expense exactly as one taken from the record.
     */
    @Test
    @DisplayName("deciding from the shared approvals queue moves the expense the same way")
    void decidingFromTheQueueMovesTheExpense() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "4000", "cartage");
        submit(uttam, id).andExpect(status().isOk());

        MvcResult queue = mockMvc.perform(get("/api/v1/approvals/pending")
                        .param("entityType", "EXPENSE")
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(status().isOk())
                .andReturn();
        String approvalId = null;
        for (JsonNode row : objectMapper.readTree(queue.getResponse().getContentAsString())) {
            if (id.equals(row.get("entityId").asText())) {
                approvalId = row.get("id").asText();
            }
        }
        assertThat(approvalId).as("the expense is in the engineer's queue").isNotNull();

        mockMvc.perform(post("/api/v1/approvals/" + approvalId + "/action")
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isOk());

        assertThat(statusOf(id)).isEqualTo("APPROVED");
    }

    /** Every level is on the record afterwards, decided or not. */
    @Test
    @DisplayName("the approval history keeps every level that was raised")
    void historyKeepsEveryLevel() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "60000", "steel invoice");
        submit(uttam, id).andExpect(status().isOk());
        decide(uttam, id, "APPROVE", "Checked against the challan").andExpect(status().isOk());
        decide(uttam, id, "APPROVE", "Cleared for payment").andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/api/v1/approvals/history")
                        .param("entityType", "EXPENSE")
                        .param("entityId", id)
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode history = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("level").asInt()).isEqualTo(1);
        assertThat(history.get(0).get("assignedRole").asText()).isEqualTo("ENGINEER");
        assertThat(history.get(0).get("remarks").asText()).isEqualTo("Checked against the challan");
        assertThat(history.get(1).get("level").asInt()).isEqualTo(2);
        assertThat(history.get(1).get("assignedRole").asText()).isEqualTo("ADMIN");
    }

    /** Voiding an in-flight expense closes the chain rather than leaving it in a queue. */
    @Test
    @DisplayName("voiding an expense cancels whatever level was waiting on it")
    void voidingClosesTheChain() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "60000", "steel invoice");
        submit(uttam, id).andExpect(status().isOk());
        assertThat(pendingCount(id)).isEqualTo(1);

        mockMvc.perform(post("/api/v1/expenses/" + id + "/void")
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Booked against the wrong site\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("VOIDED"));

        assertThat(pendingCount(id)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private String createExpense(String token, String amount, String what) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","siteId":"%s","expenseDate":"%s","categoryId":"%s",
                                 "description":"%s %s","billNumber":"BILL-%s",
                                 "amountBeforeTax":%s,"gstPercent":0,"paymentMode":"CASH"}"""
                                .formatted(UUID.randomUUID(), SITE_A, LocalDate.now(),
                                        categoryId(), TEST_MARK, what,
                                        UUID.randomUUID().toString().substring(0, 8), amount)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String updateBody(String amount, long version) {
        return """
                {"expenseDate":"%s","categoryId":"%s","description":"%s edited",
                 "amountBeforeTax":%s,"gstPercent":0,"version":%d}"""
                .formatted(LocalDate.now(), categoryId(), TEST_MARK, amount, version);
    }

    private ResultActions submit(String token, String id) throws Exception {
        return mockMvc.perform(post("/api/v1/expenses/" + id + "/submit")
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions decide(String token, String id, String action, String remarks)
            throws Exception {
        String body = remarks == null
                ? "{\"action\":\"%s\"}".formatted(action)
                : "{\"action\":\"%s\",\"remarks\":\"%s\"}".formatted(action, remarks);
        return mockMvc.perform(post("/api/v1/expenses/" + id + "/approve")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long versionOf(String token, String id) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/expenses/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("version").asLong();
    }

    private String statusOf(String expenseId) {
        return jdbc.queryForObject("SELECT workflow_status FROM expenses WHERE id = ?::uuid",
                String.class, expenseId);
    }

    private Integer pendingCount(String expenseId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM approvals
                WHERE entity_type = 'EXPENSE' AND entity_id = ?::uuid AND status = 'PENDING'""",
                Integer.class, expenseId);
    }

    private String categoryId() {
        return jdbc.queryForObject("SELECT id FROM expense_categories WHERE code = ?",
                String.class, CAT_MISC_SITE);
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

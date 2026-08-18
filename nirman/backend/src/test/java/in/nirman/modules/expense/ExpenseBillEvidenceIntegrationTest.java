package in.nirman.modules.expense;

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

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What an expense has to carry before somebody approves it, and — the bug these tests were
 * written for — what it does <b>not</b> have to carry.
 *
 * <p>Every other expense test types a bill number into its fixture, which is why none of them
 * ever met this. A ₹813 bus fare with no bill number and no reason submitted perfectly well
 * and then failed at the approval with "This record conflicts with one that already exists" —
 * V1's {@code ck_expense_bill_reason} firing on the status change, unable to see that the
 * organisation's own threshold said no bill was needed below ₹5,000. V40 moved the rule to
 * {@link in.nirman.modules.expense.service.ExpenseEvidencePolicy}; these tests are the
 * boundary it now draws.</p>
 */
class ExpenseBillEvidenceIntegrationTest extends AbstractIntegrationTest {

    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";
    private static final String CAT_MISC_SITE = "MISC-SITE";
    private static final String TEST_MARK = "EVIDENCE-TEST";
    private static final String ORG = "10000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("""
                DELETE FROM approvals WHERE entity_id IN
                    (SELECT id FROM expenses WHERE description LIKE ?)""", TEST_MARK + "%");
        jdbc.update("DELETE FROM expenses WHERE description LIKE ?", TEST_MARK + "%");
        jdbc.update("UPDATE expense_settings SET bill_required_above = 5000 WHERE org_id = ?::uuid",
                ORG);
    }

    /**
     * The bug, in one test. Below the threshold the organisation has said it expects no bill,
     * and the approval must go through without one.
     */
    @Test
    @DisplayName("a small expense with no bill number is approved without one")
    void smallExpenseNeedsNoBillNumber() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "813", null, null);

        submit(uttam, id).andExpect(status().isOk());
        decide(uttam, id, "APPROVE").andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("APPROVED"));

        assertThat(statusOf(id)).isEqualTo("APPROVED");
    }

    /** Above it, the rule bites — and it bites at submission, with a sentence. */
    @Test
    @DisplayName("a large expense with no bill and no reason is refused at submission")
    void largeExpenseWithoutEvidenceIsRefused() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "60000", null, null);

        submit(uttam, id).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("written reason there is none")));
    }

    /** A written reason is evidence, and carries the bill through both levels. */
    @Test
    @DisplayName("a large expense with a written reason and no bill number is approved")
    void writtenReasonStandsInForTheBill() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "60000", null,
                "Sand from an unregistered quarry; no bill issued.");

        submit(uttam, id).andExpect(status().isOk());
        decide(uttam, id, "APPROVE").andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("L1_APPROVED"));
        decide(uttam, id, "APPROVE").andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("APPROVED"));
    }

    /**
     * A placeholder is not a bill number. "Local" and "NIL" are what a mandatory field gets
     * typed into it when there is no bill, and accepting them would satisfy the rule with the
     * absence it exists to detect.
     */
    @Test
    @DisplayName("a placeholder bill number does not count as a bill")
    void placeholderIsNotABill() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "60000", "Local", null);

        submit(uttam, id).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("written reason there is none")));
    }

    /**
     * The backstop the dropped check used to be. The office lowers its threshold while a bill
     * is sitting in the queue, and the approval is refused — with the sentence, from the
     * listener both approval doors pass through, rather than as a 409 about duplicates.
     */
    @Test
    @DisplayName("an approval is refused when the threshold moves under a waiting bill")
    void approvalIsRefusedWhenTheThresholdMoves() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "813", null, null);
        submit(uttam, id).andExpect(status().isOk());

        jdbc.update("UPDATE expense_settings SET bill_required_above = 100 WHERE org_id = ?::uuid",
                ORG);

        decide(uttam, id, "APPROVE").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("written reason there is none")));

        // And the refusal took the decision with it: the row is still waiting, not half-moved.
        assertThat(statusOf(id)).isEqualTo("SUBMITTED");
        assertThat(pendingCount(id)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private String createExpense(String token, String amount, String billNumber,
                                 String noBillReason) throws Exception {
        String bill = billNumber == null ? "" : "\"billNumber\":\"%s\",".formatted(billNumber);
        String reason = noBillReason == null ? ""
                : "\"noBillReason\":\"%s\",".formatted(noBillReason);
        MvcResult result = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","siteId":"%s","expenseDate":"%s","categoryId":"%s",
                                 "description":"%s road fare",%s%s
                                 "amountBeforeTax":%s,"gstPercent":0,"paymentMode":"CASH"}"""
                                .formatted(UUID.randomUUID(), SITE_A, LocalDate.now(),
                                        categoryId(), TEST_MARK, bill, reason, amount)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private ResultActions submit(String token, String id) throws Exception {
        return mockMvc.perform(post("/api/v1/expenses/" + id + "/submit")
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions decide(String token, String id, String action) throws Exception {
        return mockMvc.perform(post("/api/v1/expenses/" + id + "/approve")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"%s\"}".formatted(action)));
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
                        .content("{\"username\":\"%s\",\"password\":\"Nirman@123\"}"
                                .formatted(username)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}

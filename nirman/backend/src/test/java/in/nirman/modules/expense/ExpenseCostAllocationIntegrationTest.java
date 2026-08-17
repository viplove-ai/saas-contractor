package in.nirman.modules.expense;

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
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Whose cost it is (V36), and re-opening an approved expense to correct it.
 *
 * <p>The rule the whole feature turns on: an expense is typed at a site and that is not the
 * same thing as being the site's cost. The approver decides, the head proposes so that the
 * question is still read on the bill where it matters, and the office can re-decide until the
 * site closes.</p>
 */
class ExpenseCostAllocationIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";
    private static final String TEST_MARK = "ALLOCATION-TEST";

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
        // Whatever a test did to the site, it did not mean to leave it done.
        jdbc.update("UPDATE sites SET status = 'ACTIVE' WHERE id = ?::uuid", SITE_A);
    }

    // ------------------------------------------------------------------ the proposal

    /**
     * The head's answer, carried from the moment the bill is booked. Staff salary and office
     * spending are the organisation's at every site, and an approver asked the same question
     * two hundred times stops reading it by the twentieth.
     */
    @Test
    @DisplayName("an office bill is booked already proposing the company, and a site bill the site")
    void theHeadProposesTheAnswer() throws Exception {
        String uttam = loginToken("uttam");

        String office = createExpense(uttam, "8000", "office rent", head("OFFICE-GEN"));
        mockMvc.perform(get("/api/v1/expenses/" + office)
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(jsonPath("$.costAllocation").value("COMPANY"))
                .andExpect(jsonPath("$.siteCost").value(0))
                .andExpect(jsonPath("$.companyCost").value(8000.00))
                // Proposed, not decided. The register counts these so the office can find the
                // rows nobody actually looked at.
                .andExpect(jsonPath("$.allocatedAt").doesNotExist());

        String cartage = createExpense(uttam, "4000", "cartage", head("MISC-SITE"));
        mockMvc.perform(get("/api/v1/expenses/" + cartage)
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(jsonPath("$.costAllocation").value("SITE"))
                .andExpect(jsonPath("$.siteCost").value(4000.00))
                .andExpect(jsonPath("$.companyCost").value(0));
    }

    /** The staff head V36 adds, because a man is posted to whichever site needs him. */
    @Test
    @DisplayName("staff salary is the company's, wherever the man was standing")
    void staffSalaryIsTheCompanys() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "22000", "storekeeper salary", head("OFFICE-STAFF"));
        submit(uttam, id).andExpect(status().isOk());
        decide(uttam, id, "{\"action\":\"APPROVE\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.costAllocation").value("COMPANY"))
                .andExpect(jsonPath("$.siteCost").value(0));
    }

    // ------------------------------------------------------------------ the decision

    /**
     * One bill, two costs. The diesel that ran the mixer and the office car is not two bills
     * and must not be booked as two rows, or the paper and the system stop matching.
     */
    @Test
    @DisplayName("the approver can split a bill between the site and the company by amount")
    void theApproverCanSplitABill() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "10000", "diesel", head("MISC-PETROL"));
        submit(uttam, id).andExpect(status().isOk());

        decide(uttam, id, """
                {"action":"APPROVE","allocation":"SPLIT","siteShare":6000,
                 "allocationNote":"mixer and the office car"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("APPROVED"))
                .andExpect(jsonPath("$.costAllocation").value("SPLIT"))
                .andExpect(jsonPath("$.siteCost").value(6000.00))
                .andExpect(jsonPath("$.companyCost").value(4000.00))
                .andExpect(jsonPath("$.allocationNote").value("mixer and the office car"));
    }

    /**
     * A split of the whole bill is a site cost and a split of none of it is a company cost.
     * Two spellings of one fact would make the register disagree with itself.
     */
    @Test
    @DisplayName("a split has to be a split: not the whole amount, and not nothing")
    void aSplitHasToBeASplit() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "10000", "diesel", head("MISC-PETROL"));
        submit(uttam, id).andExpect(status().isOk());

        decide(uttam, id, "{\"action\":\"APPROVE\",\"allocation\":\"SPLIT\",\"siteShare\":10000}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("All of it is a site cost")));
        decide(uttam, id, "{\"action\":\"APPROVE\",\"allocation\":\"SPLIT\",\"siteShare\":0}")
                .andExpect(status().isUnprocessableEntity());

        // And the expense is exactly where it was: nothing decided, nothing approved.
        assertThat(statusOf(id)).isEqualTo("SUBMITTED");
    }

    /**
     * The older invariant, held. A material purchase becomes stock in <i>this site's</i> store
     * and a wage payment settles a wage <i>this site's</i> attendance already counted; charging
     * half of either to the company would leave the stock ledger and the muster roll answering
     * a question the expense register answers differently.
     */
    @Test
    @DisplayName("a material purchase cannot be charged to the company, because its value is stock here")
    void whatIsCountedElsewhereStaysAtTheSite() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "9000", "cement", head("MAT-PURCHASE"));
        submit(uttam, id).andExpect(status().isOk());

        decide(uttam, id, "{\"action\":\"APPROVE\",\"allocation\":\"COMPANY\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("counted at the site")));
    }

    /** A refusal decides nothing about a cost that is not being incurred. */
    @Test
    @DisplayName("returning an expense takes no decision about whose cost it is")
    void aReturnDecidesNothing() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "4000", "cartage", head("MISC-SITE"));
        submit(uttam, id).andExpect(status().isOk());

        decide(uttam, id, """
                {"action":"RETURN","remarks":"which vehicle","allocation":"COMPANY"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("RETURNED"))
                .andExpect(jsonPath("$.costAllocation").value("SITE"))
                .andExpect(jsonPath("$.allocatedAt").doesNotExist());
    }

    // ------------------------------------------------------------------ re-deciding

    /**
     * The month read afterwards. The approver had the bill in front of him and was wrong about
     * it; the accountant reading the register a month later can see that the diesel was the
     * office car's, and he holds no approval permission at all.
     */
    @Test
    @DisplayName("the office can re-decide an approved expense, and the register follows")
    void theOfficeCanReDecide() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "5000", "diesel", head("MISC-PETROL"));
        submit(uttam, id).andExpect(status().isOk());
        decide(uttam, id, "{\"action\":\"APPROVE\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.costAllocation").value("SITE"));

        mockMvc.perform(put("/api/v1/expenses/" + id + "/allocation")
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"allocation":"COMPANY","note":"the office car, not the mixer"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.costAllocation").value("COMPANY"))
                .andExpect(jsonPath("$.siteCost").value(0))
                .andExpect(jsonPath("$.companyCost").value(5000.00))
                .andExpect(jsonPath("$.allocatedAt").exists());

        // The register's own arithmetic, over the same rows the screen shows.
        mockMvc.perform(get("/api/v1/expenses/summary")
                        .param("siteId", SITE_A)
                        .param("allocation", "COMPANY")
                        .param("from", LocalDate.now().toString())
                        .param("to", LocalDate.now().toString())
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.siteCost").value(0))
                .andExpect(jsonPath("$.companyCost").value(5000.00));
    }

    /** A supervisor books the bill. He does not decide whose bill it was. */
    @Test
    @DisplayName("a supervisor cannot re-decide whose cost an expense is")
    void theFieldDoesNotAllocate() throws Exception {
        String uttam = loginToken("uttam");
        String vivek = loginToken("vivek");
        String id = createExpense(uttam, "4000", "cartage", head("MISC-SITE"));
        submit(uttam, id).andExpect(status().isOk());
        decide(uttam, id, "{\"action\":\"APPROVE\"}").andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/expenses/" + id + "/allocation")
                        .header("Authorization", "Bearer " + vivek)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allocation\":\"COMPANY\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * A closed site's figures have gone to the department and been paid against. A cost moved
     * off one afterwards moves a number somebody has already been paid for.
     */
    @Test
    @DisplayName("a closed site takes no more corrections")
    void aClosedSiteIsClosed() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "4000", "cartage", head("MISC-SITE"));
        submit(uttam, id).andExpect(status().isOk());
        decide(uttam, id, "{\"action\":\"APPROVE\"}").andExpect(status().isOk());

        jdbc.update("UPDATE sites SET status = 'CLOSED' WHERE id = ?::uuid", SITE_A);

        mockMvc.perform(put("/api/v1/expenses/" + id + "/allocation")
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allocation\":\"COMPANY\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("closed")));
    }

    // ------------------------------------------------------------------ re-opening

    /**
     * The figure typed badly. V1's only answer was to void it and book a replacement under a
     * new number, which leaves the vendor's bill and the system disagreeing about what the
     * record is called — and teaches the supervisor to telephone the office instead.
     */
    @Test
    @DisplayName("the author re-opens an approved expense: same number, cancelled approval, new decision")
    void theAuthorCanReOpenAnApprovedExpense() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "45000", "scaffolding hire", head("MISC-SITE"));
        submit(uttam, id).andExpect(status().isOk());
        decide(uttam, id, "{\"action\":\"APPROVE\"}").andExpect(status().isOk());
        decide(uttam, id, "{\"action\":\"APPROVE\",\"allocation\":\"COMPANY\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("APPROVED"));
        String number = numberOf(uttam, id);

        mockMvc.perform(post("/api/v1/expenses/" + id + "/revise")
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody("4500", versionOf(uttam, id))))
                .andExpect(status().isOk())
                // Its own number, kept. That is the whole difference from a void.
                .andExpect(jsonPath("$.expenseNumber").value(number))
                .andExpect(jsonPath("$.totalAmount").value(4500.00))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.revisionReason").value("typed 45,000 for 4,500"))
                // Back in the queue, not standing approved on a figure nobody signed.
                .andExpect(jsonPath("$.workflowStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$.approvedAt").doesNotExist())
                // And the allocation went with the amount it was a decision about.
                .andExpect(jsonPath("$.costAllocation").value("SITE"))
                .andExpect(jsonPath("$.allocatedAt").doesNotExist());

        assertThat(pendingCount(id)).isEqualTo(1);
    }

    /**
     * Not once the cash has gone. Paid and payable are computed against the total, and moving
     * the total under a payment already made is how a supplier's ledger stops matching his
     * bills.
     */
    @Test
    @DisplayName("an expense that has been paid is voided, not re-opened")
    void aPaidExpenseCannotBeReOpened() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "4000", "cartage", head("MISC-SITE"));
        submit(uttam, id).andExpect(status().isOk());
        decide(uttam, id, "{\"action\":\"APPROVE\"}").andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expenseId":"%s","paymentDate":"%s","amount":1000,
                                 "paymentMode":"CASH"}""".formatted(id, LocalDate.now())))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/expenses/" + id + "/revise")
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody("3000", versionOf(uttam, id))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("void it")));
    }

    /** A draft is corrected where it stands. Re-opening is for what has already been signed. */
    @Test
    @DisplayName("an expense that was never approved is corrected, not re-opened")
    void onlyAnApprovedExpenseIsReOpened() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "4000", "cartage", head("MISC-SITE"));

        mockMvc.perform(post("/api/v1/expenses/" + id + "/revise")
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody("3000", versionOf(uttam, id))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(
                        containsString("still be corrected where it stands")));
    }

    // ------------------------------------------------------------------ helpers

    private String createExpense(String token, String amount, String what, String categoryId)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","siteId":"%s","expenseDate":"%s","categoryId":"%s",
                                 "description":"%s %s","billNumber":"BILL-%s",
                                 "amountBeforeTax":%s,"gstPercent":0,"paymentMode":"CASH"}"""
                                .formatted(UUID.randomUUID(), SITE_A, LocalDate.now(), categoryId,
                                        TEST_MARK, what,
                                        UUID.randomUUID().toString().substring(0, 8), amount)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String reviseBody(String amount, long version) {
        return """
                {"expenseDate":"%s","categoryId":"%s","description":"%s corrected",
                 "billNumber":"BILL-%s","amountBeforeTax":%s,"gstPercent":0,
                 "reason":"typed 45,000 for 4,500","version":%d}"""
                .formatted(LocalDate.now(), head("MISC-SITE"), TEST_MARK,
                        UUID.randomUUID().toString().substring(0, 8), amount, version);
    }

    private ResultActions submit(String token, String id) throws Exception {
        return mockMvc.perform(post("/api/v1/expenses/" + id + "/submit")
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions decide(String token, String id, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/expenses/" + id + "/approve")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String head(String code) {
        return jdbc.queryForObject("SELECT id FROM expense_categories WHERE code = ?",
                String.class, code);
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

    private long versionOf(String token, String id) throws Exception {
        return objectMapper.readTree(expenseJson(token, id)).get("version").asLong();
    }

    private String numberOf(String token, String id) throws Exception {
        return objectMapper.readTree(expenseJson(token, id)).get("expenseNumber").asText();
    }

    private String expenseJson(String token, String id) throws Exception {
        return mockMvc.perform(get("/api/v1/expenses/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
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

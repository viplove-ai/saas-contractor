package in.nirman.modules.expense;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Phase 5 exit criterion about money: <b>approved cost, cash paid and payable
 * reconcile.</b>
 *
 * <p>Three amounts, never merged (docs/02). What was agreed, what actually went out, and the
 * difference — which nobody types, because it falls out of the other two. Partial payment is
 * the normal case, so most of these tests pay a bill twice.</p>
 *
 * <p>Also covers the float: a site advance is cleared by bills and cash back, and the holder
 * cannot clear his own balance by asserting it.</p>
 */
// The suite runs no MinIO on purpose — the application starts without it so every
// non-attachment feature works on a laptop with no container. A payment's proof is an upload,
// so the bucket stands in as a map here.
@Import(InMemoryStorageConfig.class)
class CashReconciliationIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";
    private static final String VIVEK = "20000000-0000-0000-0000-000000000003";
    private static final String TEST_MARK = "PHASE5-CASH";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeTestRecords() {
        jdbc.update("""
                DELETE FROM advance_settlement_expenses WHERE expense_id IN
                    (SELECT id FROM expenses WHERE description LIKE ?)""", TEST_MARK + "%");
        jdbc.update("""
                DELETE FROM approvals WHERE entity_id IN
                    (SELECT id FROM expenses WHERE description LIKE ?)""", TEST_MARK + "%");
        jdbc.update("""
                DELETE FROM approvals WHERE entity_type = 'ADVANCE_SETTLEMENT'
                  AND entity_id IN (SELECT id FROM advance_settlements
                                    WHERE advance_id = '80000000-0000-0000-0000-000000000001')""");
        jdbc.update("DELETE FROM payments WHERE expense_id IN "
                + "(SELECT id FROM expenses WHERE description LIKE ?)", TEST_MARK + "%");
        jdbc.update("DELETE FROM advance_settlements WHERE advance_id = "
                + "'80000000-0000-0000-0000-000000000001'");
        jdbc.update("DELETE FROM expenses WHERE description LIKE ?", TEST_MARK + "%");
        // The seeded float goes back to untouched, so each test starts from ₹20,000 open.
        jdbc.update("""
                UPDATE site_advances SET adjusted_amount = 0, returned_amount = 0,
                    settlement_status = 'OPEN', closed_at = NULL
                WHERE id = '80000000-0000-0000-0000-000000000001'""");
    }

    /** The identity, stated as three figures that have to add up after every payment. */
    @Test
    @DisplayName("approved cost, cash paid and payable reconcile across partial payments")
    void partialPaymentsReconcile() throws Exception {
        String uttam = loginToken("uttam");
        String id = approvedExpense(uttam, "50000", "steel");

        assertAmounts(uttam, id, "50000.00", "0.00", "50000.00");

        pay(uttam, id, "20000").andExpect(status().isCreated());
        assertAmounts(uttam, id, "50000.00", "20000.00", "30000.00");
        assertThat(paymentStatus(id)).isEqualTo("PARTIAL");

        pay(uttam, id, "30000").andExpect(status().isCreated());
        assertAmounts(uttam, id, "50000.00", "50000.00", "0.00");
        assertThat(paymentStatus(id)).isEqualTo("PAID");
    }

    /**
     * Overpaying is a real event but it is not a payment against this bill — it is an
     * advance to the vendor, and merging the two makes the ageing report lie.
     */
    @Test
    @DisplayName("a payment cannot exceed what is still payable")
    void cannotOverpayABill() throws Exception {
        String uttam = loginToken("uttam");
        String id = approvedExpense(uttam, "10000", "cartage");
        pay(uttam, id, "8000").andExpect(status().isCreated());

        pay(uttam, id, "5000").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("Only 2000.00 is still payable")));

        assertAmounts(uttam, id, "10000.00", "8000.00", "2000.00");
    }

    /** The control the whole approval flow exists for: no cash against an unapproved bill. */
    @Test
    @DisplayName("nothing is paid against an expense nobody has approved")
    void cannotPayAnUnapprovedExpense() throws Exception {
        String uttam = loginToken("uttam");
        String id = createExpense(uttam, "9000", "unapproved");
        submit(uttam, id).andExpect(status().isOk());

        pay(uttam, id, "9000").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("Nothing is paid until it is approved")));
    }

    /** Recording a payment is the accountant's job, deliberately not the author's. */
    @Test
    @DisplayName("a supervisor cannot pay the expense he raised")
    void supervisorCannotRecordPayments() throws Exception {
        String uttam = loginToken("uttam");
        String vivek = loginToken("vivek");
        String id = approvedExpense(uttam, "5000", "consumables");

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + vivek)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(id, "5000")))
                .andExpect(status().isForbidden());
    }

    /**
     * A reference number is not a receipt.
     *
     * <p>Until V45 the register could hold twelve digits and nothing else, so a supplier
     * disputing a payment nine months later was answered with a string and the hope that
     * somebody's phone still had the screenshot on it. The picture is claimed by the payment
     * in the same transaction that creates it, which is what stops
     * {@code AttachmentService.delete} discarding it afterwards as a stray upload.</p>
     */
    @Test
    @DisplayName("a payment carries the proof it went out, and the file becomes the payment's")
    void aPaymentCarriesItsProof() throws Exception {
        String uttam = loginToken("uttam");
        String id = approvedExpense(uttam, "18000", "steel with a screenshot");
        String proof = uploadProof(uttam, "upi-4471.jpg");

        MvcResult result = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expenseId":"%s","paymentDate":"%s","amount":18000,
                                 "paymentMode":"BANK","referenceNumber":"UTR-9912",
                                 "attachmentId":"%s","proofType":"SCREENSHOT"}"""
                                .formatted(id, LocalDate.now(), proof)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachments.length()").value(1))
                .andExpect(jsonPath("$.attachments[0].attachmentId").value(proof))
                .andExpect(jsonPath("$.attachments[0].docType").value("SCREENSHOT"))
                .andExpect(jsonPath("$.attachments[0].fileName").value("upi-4471.jpg"))
                .andReturn();

        // Claimed, so nothing can discard it out from under the payment that points at it.
        mockMvc.perform(delete("/api/v1/attachments/" + proof)
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(status().isUnprocessableEntity());

        // And it is still there when the payment is read back, not only on the way out.
        String paymentId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
        mockMvc.perform(get("/api/v1/payments")
                        .param("expenseId", id)
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(paymentId))
                .andExpect(jsonPath("$.content[0].attachments[0].attachmentId").value(proof));
    }

    /**
     * Cash handed across a table has nothing to photograph. Refusing the payment for want of
     * a picture is how a real payment ends up in the second book that this module exists to
     * close, so the proof is optional and the figures reconcile without it.
     */
    @Test
    @DisplayName("a payment with no proof is still a payment")
    void proofIsOptional() throws Exception {
        String uttam = loginToken("uttam");
        String id = approvedExpense(uttam, "3000", "cash cartage");

        pay(uttam, id, "3000").andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachments.length()").value(0));
        assertAmounts(uttam, id, "3000.00", "3000.00", "0.00");
    }

    /** Vendor balances are built from the bills, never from a figure somebody typed. */
    @Test
    @DisplayName("vendor balances are the three figures, derived from the outstanding bills")
    void vendorBalancesDeriveFromTheBills() throws Exception {
        String uttam = loginToken("uttam");
        String vendor = vendorId();
        String first = approvedExpense(uttam, "30000", "steel", vendor);
        approvedExpense(uttam, "12000", "cement", vendor);
        pay(uttam, first, "10000").andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/api/v1/vendors/balances")
                        .param("vendorId", vendor)
                        .header("Authorization", "Bearer " + uttam))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode row = objectMapper.readTree(result.getResponse().getContentAsString()).get(0);

        assertThat(new BigDecimal(row.get("approvedAmount").asText()))
                .isEqualByComparingTo("42000.00");
        assertThat(new BigDecimal(row.get("paidAmount").asText()))
                .isEqualByComparingTo("10000.00");
        assertThat(new BigDecimal(row.get("payableAmount").asText()))
                .isEqualByComparingTo("32000.00");
        assertThat(row.get("openBills").asInt()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ the float

    /**
     * The float moves when the office agrees, not when the holder claims. That is the whole
     * control: a supervisor who could reduce his own balance by submitting a settlement
     * would not need the office at all.
     */
    @Test
    @DisplayName("a float is cleared only when the settlement is approved")
    void floatMovesOnApprovalNotOnSubmission() throws Exception {
        String uttam = loginToken("uttam");
        String vivek = loginToken("vivek");
        String bill = approvedExpense(uttam, "8000", "cartage");

        MvcResult submitted = mockMvc.perform(post("/api/v1/advances/" + advanceId()
                        + "/settlements")
                        .header("Authorization", "Bearer " + vivek)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"settlementDate":"%s","expenseIds":["%s"],"returnedAmount":2000,
                                 "remarks":"June petty cash"}"""
                                .formatted(LocalDate.now(), bill)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clearedAmount").value(10000.00))
                .andReturn();
        String settlementId = objectMapper.readTree(submitted.getResponse().getContentAsString())
                .get("id").asText();

        // Claimed but not agreed: the float is untouched.
        assertThat(advanceBalance()).isEqualByComparingTo("20000.00");

        mockMvc.perform(post("/api/v1/settlements/" + settlementId + "/decision")
                        .header("Authorization", "Bearer " + uttam)   // holds ACCOUNTANT
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // ₹8,000 of bills plus ₹2,000 handed back leaves ₹10,000 in his pocket.
        assertThat(advanceBalance()).isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("a settlement cannot clear more than the float still outstanding")
    void settlementCannotExceedTheFloat() throws Exception {
        String uttam = loginToken("uttam");
        String vivek = loginToken("vivek");
        String bill = approvedExpense(uttam, "25000", "too much");

        mockMvc.perform(post("/api/v1/advances/" + advanceId() + "/settlements")
                        .header("Authorization", "Bearer " + vivek)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"settlementDate":"%s","expenseIds":["%s"]}"""
                                .formatted(LocalDate.now(), bill)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("against a float of 20000.00")));
    }

    /** The same bill on two floats would clear ₹16,000 of cash with ₹8,000 of paper. */
    @Test
    @DisplayName("the same bill cannot be claimed against a float twice")
    void oneBillSettlesOneFloat() throws Exception {
        String uttam = loginToken("uttam");
        String vivek = loginToken("vivek");
        String bill = approvedExpense(uttam, "5000", "claimed twice");

        mockMvc.perform(post("/api/v1/advances/" + advanceId() + "/settlements")
                        .header("Authorization", "Bearer " + vivek)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"settlementDate":"%s","expenseIds":["%s"]}"""
                                .formatted(LocalDate.now(), bill)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/advances/" + advanceId() + "/settlements")
                        .header("Authorization", "Bearer " + vivek)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"settlementDate":"%s","expenseIds":["%s"]}"""
                                .formatted(LocalDate.now(), bill)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("only approved bills can be claimed against a float")
    void unapprovedBillsCannotBeClaimed() throws Exception {
        String uttam = loginToken("uttam");
        String vivek = loginToken("vivek");
        String draft = createExpense(uttam, "3000", "still a draft");

        mockMvc.perform(post("/api/v1/advances/" + advanceId() + "/settlements")
                        .header("Authorization", "Bearer " + vivek)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"settlementDate":"%s","expenseIds":["%s"]}"""
                                .formatted(LocalDate.now(), draft)))
                .andExpect(status().isUnprocessableEntity());
    }

    /** A supervisor cannot hand himself petty cash. That is the entire control. */
    @Test
    @DisplayName("a supervisor cannot issue himself a float")
    void supervisorCannotIssueAdvances() throws Exception {
        String vivek = loginToken("vivek");
        mockMvc.perform(post("/api/v1/advances")
                        .header("Authorization", "Bearer " + vivek)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"siteId":"%s","issuedToUserId":"%s","advanceDate":"%s",
                                 "amount":5000,"paymentMode":"CASH","purpose":"Helping myself"}"""
                                .formatted(SITE_A, VIVEK, LocalDate.now())))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private void assertAmounts(String token, String id, String total, String paid,
                               String payable) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/expenses/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(new BigDecimal(body.get("totalAmount").asText())).isEqualByComparingTo(total);
        assertThat(new BigDecimal(body.get("paidAmount").asText())).isEqualByComparingTo(paid);
        assertThat(new BigDecimal(body.get("payableAmount").asText()))
                .isEqualByComparingTo(payable);
        // The identity itself, asserted rather than assumed.
        assertThat(new BigDecimal(body.get("totalAmount").asText())
                .subtract(new BigDecimal(body.get("paidAmount").asText())))
                .isEqualByComparingTo(body.get("payableAmount").asText());
    }

    private String approvedExpense(String token, String amount, String what) throws Exception {
        return approvedExpense(token, amount, what, vendorId());
    }

    private String approvedExpense(String token, String amount, String what, String vendor)
            throws Exception {
        String id = createExpense(token, amount, what, vendor);
        submit(token, id).andExpect(status().isOk());
        approveFully(token, id);
        return id;
    }

    /** Approves through however many levels the amount routed it to. */
    private void approveFully(String token, String id) throws Exception {
        for (int level = 0; level < 3; level++) {
            if (!"SUBMITTED".equals(statusOf(id)) && !"L1_APPROVED".equals(statusOf(id))) {
                return;
            }
            mockMvc.perform(post("/api/v1/expenses/" + id + "/approve")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"APPROVE\"}"))
                    .andExpect(status().isOk());
        }
    }

    private String createExpense(String token, String amount, String what) throws Exception {
        return createExpense(token, amount, what, vendorId());
    }

    private String createExpense(String token, String amount, String what, String vendor)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","siteId":"%s","expenseDate":"%s","categoryId":"%s",
                                 "vendorId":"%s","description":"%s %s","billNumber":"B-%s",
                                 "amountBeforeTax":%s,"gstPercent":0,"paymentMode":"CASH"}"""
                                .formatted(UUID.randomUUID(), SITE_A, LocalDate.now(),
                                        categoryId(), vendor, TEST_MARK, what,
                                        UUID.randomUUID().toString().substring(0, 8), amount)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private ResultActions submit(String token, String id) throws Exception {
        return mockMvc.perform(post("/api/v1/expenses/" + id + "/submit")
                .header("Authorization", "Bearer " + token));
    }

    /** A picture in the bucket, waiting for the payment that will claim it. */
    private String uploadProof(String token, String fileName) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/attachments")
                        .file(new MockMultipartFile("file", fileName, "image/jpeg",
                                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}))
                        .param("ownerEntityType", "PAYMENT")
                        .param("kind", "DOCUMENT")
                        .param("siteId", SITE_A)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private ResultActions pay(String token, String expenseId, String amount) throws Exception {
        return mockMvc.perform(post("/api/v1/payments")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(paymentBody(expenseId, amount)));
    }

    private String paymentBody(String expenseId, String amount) {
        return """
                {"expenseId":"%s","paymentDate":"%s","amount":%s,"paymentMode":"BANK",
                 "referenceNumber":"UTR-%s"}"""
                .formatted(expenseId, LocalDate.now(), amount,
                        UUID.randomUUID().toString().substring(0, 8));
    }

    private String statusOf(String expenseId) {
        return jdbc.queryForObject("SELECT workflow_status FROM expenses WHERE id = ?::uuid",
                String.class, expenseId);
    }

    private String paymentStatus(String expenseId) {
        return jdbc.queryForObject("SELECT payment_status FROM expenses WHERE id = ?::uuid",
                String.class, expenseId);
    }

    private BigDecimal advanceBalance() {
        return jdbc.queryForObject(
                "SELECT balance_amount FROM site_advances WHERE id = ?::uuid",
                BigDecimal.class, advanceId());
    }

    private String advanceId() {
        return "80000000-0000-0000-0000-000000000001";
    }

    private String vendorId() {
        return jdbc.queryForObject("SELECT id FROM vendors WHERE code = 'VND-001'", String.class);
    }

    private String categoryId() {
        return jdbc.queryForObject("SELECT id FROM expense_categories WHERE code = 'MISC-SITE'",
                String.class);
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

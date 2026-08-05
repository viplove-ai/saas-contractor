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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Phase 5 exit criterion about duplicates: <b>a duplicate invoice returns 409 with
 * candidates.</b>
 *
 * <p>The half that matters is the near-miss. The field writes "Local", "NIL" or "-" in the
 * bill-number box for a great many cash purchases (docs/09), and a placeholder collides with
 * nothing — so an exact bill-number check alone would catch the tidy cases and miss every
 * real double entry. The second check is same vendor, same amount, within a week, whatever
 * the bill number says.</p>
 */
class ExpenseDuplicateIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";
    private static final String VENDOR_SHRI_JI = "VND-001";
    private static final String TEST_MARK = "PHASE5-DUP";

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
        jdbc.update("DELETE FROM expenses WHERE description LIKE ?", TEST_MARK + "%");
    }

    @Test
    @DisplayName("the same vendor bill twice is refused with the candidate named")
    void sameBillNumberIsRefusedWithCandidates() throws Exception {
        String uttam = loginToken("uttam");
        MvcResult first = create(uttam, "SS/856", "8000", LocalDate.now(), vendorId(), false, null)
                .andExpect(status().isCreated())
                .andReturn();
        String firstNumber = number(first);

        MvcResult clash = create(uttam, "SS/856", "8000", LocalDate.now(), vendorId(), false, null)
                .andExpect(status().isConflict())
                .andReturn();
        JsonNode body = objectMapper.readTree(clash.getResponse().getContentAsString());

        // Not just "duplicate" — the row it collided with, so nobody has to go hunting.
        JsonNode candidates = body.get("candidates");
        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).get("expenseNumber").asText()).isEqualTo(firstNumber);
        assertThat(candidates.get(0).get("matchedOn").asText())
                .isEqualTo("same vendor and bill number");
    }

    /** Case and whitespace do not make a new bill. */
    @Test
    @DisplayName("bill numbers are compared without regard to case or spacing")
    void billNumbersAreComparedLoosely() throws Exception {
        String uttam = loginToken("uttam");
        create(uttam, "SS/857", "9000", LocalDate.now(), vendorId(), false, null)
                .andExpect(status().isCreated());
        create(uttam, "  ss/857 ", "9000", LocalDate.now(), vendorId(), false, null)
                .andExpect(status().isConflict());
    }

    /**
     * The check that actually catches a double entry. Two cash purchases with a placeholder
     * bill number would collide on nothing, so the same vendor and amount within a week is
     * flagged on its own.
     */
    @Test
    @DisplayName("a placeholder bill number still gets caught by the amount-and-date near miss")
    void nearMissCatchesPlaceholderBills() throws Exception {
        String uttam = loginToken("uttam");
        create(uttam, "Local", "3500", LocalDate.now(), vendorId(), false, null)
                .andExpect(status().isCreated());

        MvcResult clash = create(uttam, "Local", "3500", LocalDate.now().minusDays(2),
                        vendorId(), false, null)
                .andExpect(status().isConflict())
                .andReturn();
        JsonNode candidates = objectMapper.readTree(clash.getResponse().getContentAsString())
                .get("candidates");
        assertThat(candidates.get(0).get("matchedOn").asText())
                .isEqualTo("same vendor and amount within a week");
    }

    /** Outside the window is a different purchase, not a duplicate. */
    @Test
    @DisplayName("the same amount a month later is not a duplicate")
    void nearMissHasAWindow() throws Exception {
        String uttam = loginToken("uttam");
        create(uttam, "Local", "4400", LocalDate.now(), vendorId(), false, null)
                .andExpect(status().isCreated());
        create(uttam, "Local", "4400", LocalDate.now().minusDays(30), vendorId(), false, null)
                .andExpect(status().isCreated());
    }

    /**
     * A vendorless expense is the case the database index alone never caught: {@code
     * vendor_id} is nullable and NULLs compare as distinct, so without explicit handling
     * every cash purchase escaped the check — and most site expenses have no vendor.
     */
    @Test
    @DisplayName("a vendorless cash purchase is checked too, not skipped for want of a vendor")
    void vendorlessExpensesAreCheckedAsWell() throws Exception {
        String uttam = loginToken("uttam");
        create(uttam, "CASH", "1200", LocalDate.now(), null, false, null)
                .andExpect(status().isCreated());
        create(uttam, "CASH", "1200", LocalDate.now(), null, false, null)
                .andExpect(status().isConflict());
    }

    /** Overriding is allowed — with a reason, and the original stays linked to the new row. */
    @Test
    @DisplayName("a genuine second delivery is booked with force and a stated reason")
    void forceBooksItAnywayWithAReason() throws Exception {
        String uttam = loginToken("uttam");
        MvcResult first = create(uttam, "SS/900", "5000", LocalDate.now(), vendorId(), false, null)
                .andExpect(status().isCreated())
                .andReturn();
        String originalId = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("id").asText();

        // Force without a reason is still refused: the override has to explain itself.
        create(uttam, "SS/901", "5000", LocalDate.now(), vendorId(), true, null)
                .andExpect(status().isUnprocessableEntity());

        MvcResult forced = create(uttam, "SS/901", "5000", LocalDate.now(), vendorId(), true,
                        "Second lorry the same afternoon, separate challan")
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(forced.getResponse().getContentAsString());
        assertThat(body.get("duplicateOfId").asText()).isEqualTo(originalId);
    }

    /** A voided expense is not something a later booking should collide with. */
    @Test
    @DisplayName("a voided expense stops blocking the bill number")
    void voidedExpensesDoNotBlock() throws Exception {
        String uttam = loginToken("uttam");
        MvcResult first = create(uttam, "SS/902", "6000", LocalDate.now(), vendorId(), false, null)
                .andExpect(status().isCreated())
                .andReturn();
        String id = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(post("/api/v1/expenses/" + id + "/void")
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Typed against the wrong vendor\"}"))
                .andExpect(status().isOk());

        create(uttam, "SS/902", "6000", LocalDate.now(), vendorId(), false, null)
                .andExpect(status().isCreated());
    }

    /** The same client id twice is a re-send, not a duplicate — and must not raise a 409. */
    @Test
    @DisplayName("re-sending the same expense id is one expense, not a duplicate warning")
    void resendingTheSameIdIsIdempotent() throws Exception {
        String uttam = loginToken("uttam");
        String clientId = UUID.randomUUID().toString();
        String body = expenseBody(clientId, "SS/903", "7000", LocalDate.now(), vendorId(), null);

        MvcResult first = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult second = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", "Bearer " + uttam)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();

        assertThat(number(second)).isEqualTo(number(first));
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM expenses WHERE id = ?::uuid",
                Integer.class, clientId);
        assertThat(rows).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions create(String token, String billNumber, String amount, LocalDate date,
                                 String vendorId, boolean force, String overrideReason)
            throws Exception {
        var request = post("/api/v1/expenses")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(expenseBody(UUID.randomUUID().toString(), billNumber, amount, date,
                        vendorId, overrideReason));
        if (force) {
            request = request.param("force", "true");
        }
        return mockMvc.perform(request);
    }

    private String expenseBody(String id, String billNumber, String amount, LocalDate date,
                               String vendorId, String overrideReason) {
        String vendorClause = vendorId == null ? "" : "\"vendorId\":\"%s\",".formatted(vendorId);
        String overrideClause = overrideReason == null ? ""
                : ",\"duplicateOverrideReason\":\"%s\"".formatted(overrideReason);
        return """
                {"id":"%s","siteId":"%s","expenseDate":"%s","categoryId":"%s",%s
                 "description":"%s purchase","billNumber":"%s",
                 "amountBeforeTax":%s,"gstPercent":0,"paymentMode":"CASH"%s}"""
                .formatted(id, SITE_A, date, categoryId(), vendorClause, TEST_MARK, billNumber,
                        amount, overrideClause);
    }

    private String number(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("expenseNumber").asText();
    }

    private String vendorId() {
        return jdbc.queryForObject("SELECT id FROM vendors WHERE code = ?", String.class,
                VENDOR_SHRI_JI);
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

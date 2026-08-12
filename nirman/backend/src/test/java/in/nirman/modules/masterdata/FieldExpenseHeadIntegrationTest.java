package in.nirman.modules.masterdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An expense head named at the site.
 *
 * <p>The same shape of problem as a material named at the gate, one screen along. An expense
 * is booked against a head and cannot be booked without one, and the taxonomy an organisation
 * starts with is the CPWD list. The first bill for something that list never imagined leaves
 * a supervisor choosing whichever head is least wrong — and a month later the office is
 * looking at a "Miscellaneous" total it cannot break down.</p>
 *
 * <p>What he may set is the name. The two flags that decide whether a head's rows are cost at
 * all stay false and the row is marked provisional, because a supervisor guessing that a head
 * is a labour payment would take a month's wages out of the cost figure twice over.</p>
 */
class FieldExpenseHeadIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("a supervisor names a head at the site and gets a usable, unvetted row")
    void namesAHeadFromTheSite() throws Exception {
        JsonNode created = nameHead(loginToken("vivek"), "Temple Donation On Foundation Day");

        assertThat(created.get("name").asText()).isEqualTo("Temple Donation On Foundation Day");
        assertThat(created.get("provisional").asBoolean()).isTrue();
        assertThat(created.get("code").asText()).startsWith("EXH-");
        // The two figures nobody at a site may decide. Either one guessed double-counts a
        // month: material purchase is inventory rather than cost, and a labour payment
        // settles a wage that verified attendance has already counted.
        assertThat(created.get("materialPurchase").asBoolean()).isFalse();
        assertThat(created.get("labourPayment").asBoolean()).isFalse();
    }

    /** Two heads for one kind of spending split a month's figure across two report lines. */
    @Test
    @DisplayName("the same name typed again is the same head, whatever the case and spacing")
    void doesNotDuplicateAHeadSomebodyAlreadyNamed() throws Exception {
        String token = loginToken("vivek");

        JsonNode first = nameHead(token, "Dewatering Pump Diesel");
        JsonNode again = nameHead(token, "  dewatering   PUMP diesel ");

        assertThat(again.get("id").asText()).isEqualTo(first.get("id").asText());
    }

    /** The starter taxonomy already holds these. Typing one is not a second head. */
    @Test
    @DisplayName("a name the taxonomy already holds returns the vetted head, not a new one")
    void returnsTheEstablishedHeadRatherThanShadowingIt() throws Exception {
        JsonNode answer = nameHead(loginToken("vivek"), "Site Expenses");

        assertThat(answer.get("provisional").asBoolean()).isFalse();
        assertThat(answer.get("code").asText()).doesNotStartWith("EXH-");
    }

    /**
     * The point of the whole exercise: the head he named can carry the bill in his hand.
     * A head that cannot be booked against is a row in a table and nothing else.
     */
    @Test
    @DisplayName("the head he named can take the expense straight away")
    void booksTheExpenseAgainstIt() throws Exception {
        String token = loginToken("vivek");
        String headId = nameHead(token, "Rat Menace Control").get("id").asText();

        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","siteId":"%s","expenseDate":"%s","categoryId":"%s",
                                 "description":"Bait and traps for the store",
                                 "amountBeforeTax":900,"gstPercent":0}"""
                                .formatted(UUID.randomUUID(), SITE_A, LocalDate.now(), headId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryName").value("Rat Menace Control"));
    }

    /**
     * Naming what a bill was for and owning the chart of accounts are different acts. If a
     * supervisor could do both, the provisional flag would be decoration.
     */
    @Test
    @DisplayName("a supervisor still may not create a head the ordinary way")
    void namingIsNotTheSameAsOwningTheTaxonomy() throws Exception {
        mockMvc.perform(post("/api/v1/expense-categories")
                        .header("Authorization", "Bearer " + loginToken("vivek"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"SNEAK-1","name":"Wages by another name",
                                 "labourPayment":true,"materialPurchase":false,
                                 "requiresVendor":false,"sortOrder":0}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a head needs a name, and blank spaces are not one")
    void refusesABlankName() throws Exception {
        mockMvc.perform(post("/api/v1/expense-categories/field")
                        .header("Authorization", "Bearer " + loginToken("vivek"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    private JsonNode nameHead(String token, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/expense-categories/field")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new NamedHead(name))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private record NamedHead(String name) {
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

package in.nirman.modules.labour;

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

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The supervisor's day, when the men came from somebody else.
 *
 * <p>There is one supplier register now (V23): the firm that brings a gang is a vendor like
 * the one that brings cement, registered once, with an account and an address behind the
 * name. So naming him on a day's counts names something real, and the day can also record
 * the thing a site actually argues about a fortnight later — whether he, or the man he sends
 * to run the gang, was standing there.</p>
 *
 * <p>Still no money. Nothing here is multiplied by a rate; the supplier bills for the work.</p>
 */
class LabourSupplierDayIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";
    private static final String MASON = "MASON";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * KSN-A keeps a muster roll in the sample data, so the flag has to be turned on for the
     * counts section to exist at all — and turned back off, or every other class's view of
     * that site changes.
     */
    @AfterEach
    void restoreTheSite() {
        jdbc.update("DELETE FROM site_labour_supplier_days WHERE site_id = ?::uuid", SITE_A);
        jdbc.update("DELETE FROM site_labour_counts WHERE site_id = ?::uuid", SITE_A);
        jdbc.update("UPDATE sites SET uses_outsourced_labour = false WHERE id = ?::uuid", SITE_A);
    }

    @Test
    @DisplayName("the day names a registered supplier and says whether he stood there")
    void namesTheSupplierAndWhetherHeCame() throws Exception {
        String admin = login("viplove");
        enableCounts(admin);
        String supplierId = labourSupplierId(admin);

        JsonNode day = saveDay(admin, """
                {"siteId":"%s","date":"%s",
                 "lines":[{"skillCategoryId":"%s","labourSupplierId":"%s","headCount":11,"hours":8}],
                 "suppliers":[{"labourSupplierId":"%s","supplierPresent":true,
                               "representativeName":"Karam Singh"}]}"""
                .formatted(SITE_A, LocalDate.now(), skillId(admin, MASON), supplierId, supplierId));

        assertThat(day.get("lines").get(0).get("labourSupplierName").asText())
                .isEqualTo("Kausani Labour Co-operative");
        JsonNode supplier = day.get("suppliers").get(0);
        assertThat(supplier.get("supplierPresent").asBoolean()).isTrue();
        assertThat(supplier.get("representativeName").asText()).isEqualTo("Karam Singh");
        // His men, added across the trades he supplied.
        assertThat(supplier.get("headCount").asInt()).isEqualTo(11);
    }

    /**
     * The row a supervisor needs in order to answer the question. A supplier who sent men
     * and about whom nobody said anything appears as not-present rather than being left off
     * — absent from the list, he would be a question nobody is ever asked.
     */
    @Test
    @DisplayName("a supplier nobody answered for still appears, as not present")
    void anUnansweredSupplierStillAppears() throws Exception {
        String admin = login("viplove");
        enableCounts(admin);
        String supplierId = labourSupplierId(admin);

        JsonNode day = saveDay(admin, """
                {"siteId":"%s","date":"%s",
                 "lines":[{"skillCategoryId":"%s","labourSupplierId":"%s","headCount":6}],
                 "suppliers":[]}"""
                .formatted(SITE_A, LocalDate.now(), skillId(admin, MASON), supplierId));

        assertThat(day.get("suppliers")).hasSize(1);
        assertThat(day.get("suppliers").get(0).get("supplierPresent").asBoolean()).isFalse();
    }

    /**
     * One answer per supplier, however many trades he sent. Three count rows and three
     * answers is a question that can disagree with itself.
     */
    @Test
    @DisplayName("one supplier across three trades is one presence answer and one head count")
    void oneSupplierAcrossThreeTrades() throws Exception {
        String admin = login("viplove");
        enableCounts(admin);
        String supplierId = labourSupplierId(admin);

        JsonNode day = saveDay(admin, """
                {"siteId":"%s","date":"%s","lines":[
                    {"skillCategoryId":"%s","labourSupplierId":"%s","headCount":11},
                    {"skillCategoryId":"%s","labourSupplierId":"%s","headCount":6},
                    {"skillCategoryId":"%s","labourSupplierId":"%s","headCount":3}],
                 "suppliers":[{"labourSupplierId":"%s","supplierPresent":false}]}"""
                .formatted(SITE_A, LocalDate.now(),
                        skillId(admin, MASON), supplierId,
                        skillId(admin, "HELPER"), supplierId,
                        skillId(admin, "CARPENTER"), supplierId, supplierId));

        assertThat(day.get("lines")).hasSize(3);
        assertThat(day.get("suppliers")).hasSize(1);
        assertThat(day.get("suppliers").get(0).get("headCount").asInt()).isEqualTo(20);
    }

    /** A gang nobody attributed is a real entry, and there is nobody to ask about. */
    @Test
    @DisplayName("an unattributed gang produces no supplier question")
    void anUnattributedGangHasNoSupplier() throws Exception {
        String admin = login("viplove");
        enableCounts(admin);

        JsonNode day = saveDay(admin, """
                {"siteId":"%s","date":"%s",
                 "lines":[{"skillCategoryId":"%s","headCount":4}],"suppliers":[]}"""
                .formatted(SITE_A, LocalDate.now(), skillId(admin, MASON)));

        assertThat(day.get("lines")).hasSize(1);
        assertThat(day.get("suppliers")).isEmpty();
    }

    /** The register is one register: a name that is not on it is not a supplier. */
    @Test
    @DisplayName("a supplier who is not on the register is refused")
    void anUnregisteredSupplierIsRefused() throws Exception {
        String admin = login("viplove");
        enableCounts(admin);

        mockMvc.perform(put("/api/v1/labour-counts")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"siteId":"%s","date":"%s",
                                 "lines":[{"skillCategoryId":"%s",
                                           "labourSupplierId":"11111111-1111-1111-1111-111111111111",
                                           "headCount":4}],"suppliers":[]}"""
                                .formatted(SITE_A, LocalDate.now(), skillId(admin, MASON))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("not on the register")));
    }

    /**
     * An older phone replaying a queued day sends no supplier list at all. That is "nothing
     * to say", not "nobody was there", and it must not wipe an answer given at the desk.
     */
    @Test
    @DisplayName("a client that sends no supplier list leaves the answers alone")
    void anAbsentSupplierListChangesNothing() throws Exception {
        String admin = login("viplove");
        enableCounts(admin);
        String supplierId = labourSupplierId(admin);
        String mason = skillId(admin, MASON);

        saveDay(admin, """
                {"siteId":"%s","date":"%s",
                 "lines":[{"skillCategoryId":"%s","labourSupplierId":"%s","headCount":11}],
                 "suppliers":[{"labourSupplierId":"%s","supplierPresent":true}]}"""
                .formatted(SITE_A, LocalDate.now(), mason, supplierId, supplierId));

        JsonNode replayed = saveDay(admin, """
                {"siteId":"%s","date":"%s",
                 "lines":[{"skillCategoryId":"%s","labourSupplierId":"%s","headCount":11}]}"""
                .formatted(SITE_A, LocalDate.now(), mason, supplierId));

        assertThat(replayed.get("suppliers").get(0).get("supplierPresent").asBoolean())
                .as("a replay with nothing to say must not erase the answer")
                .isTrue();
    }

    /** The seeded labour contractor came across as a vendor, keeping his id and his name. */
    @Test
    @DisplayName("the old contractor register survives as a supplier with an account")
    void theContractorBecameASupplier() throws Exception {
        String admin = login("viplove");
        MvcResult result = mockMvc.perform(get("/api/v1/vendors")
                        .param("type", "SUBCONTRACTOR")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode found = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("content");
        assertThat(found).isNotEmpty();
        assertThat(found.toString()).contains("Kausani Labour Co-operative");

        // And he has the account the separate register never gave him.
        mockMvc.perform(get("/api/v1/vendors/" + labourSupplierId(admin) + "/account")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vendorName").value("Kausani Labour Co-operative"));
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode saveDay(String token, String body) throws Exception {
        MvcResult result = mockMvc.perform(put("/api/v1/labour-counts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void enableCounts(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/sites/" + SITE_A)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode site = objectMapper.readTree(result.getResponse().getContentAsString());
        mockMvc.perform(put("/api/v1/sites/" + SITE_A)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","status":"ACTIVE","standardShiftHours":7.00,
                                 "monthlyWageDays":26,"usesOutsourcedLabour":true,
                                 "siteEngineerIds":%s,"supervisorIds":%s,"version":%d}"""
                                .formatted(site.get("name").asText(),
                                        site.get("siteEngineerIds"), site.get("supervisorIds"),
                                        site.get("version").asLong())))
                .andExpect(status().isOk());
    }

    private String labourSupplierId(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/vendors")
                        .param("type", "SUBCONTRACTOR")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("content").get(0).get("id").asText();
    }

    private String skillId(String token, String code) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/skill-categories")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode category : objectMapper.readTree(result.getResponse().getContentAsString())) {
            if (code.equals(category.get("code").asText())) {
                return category.get("id").asText();
            }
        }
        throw new IllegalStateException("The dev seed has no trade " + code);
    }

    private String login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}

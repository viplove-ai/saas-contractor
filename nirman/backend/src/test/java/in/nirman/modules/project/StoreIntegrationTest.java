package in.nirman.modules.project;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stores: the one a site is given without being asked, and the register an administrator
 * keeps afterwards.
 *
 * <p>The rule under test is that a site is never without somewhere to put material. Before
 * this, a site added on Monday met the first lorry on Tuesday with an empty store picker on
 * the receive screen — a supervisor stuck at the gate over a record nobody knew was missing.</p>
 *
 * <p>Everything created here is cleaned up by deleting its project, which takes its sites and
 * their stores with it: the suite shares one database and a project left standing turns up in
 * another test's counts.</p>
 */
class StoreIntegrationTest extends AbstractIntegrationTest {

    /** Kausani Main Block, which the seed gives real records — its stores cannot be deleted. */
    private static final String SEEDED_SITE_A = "31000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void removeWhatTheTestLeftStanding() throws Exception {
        String token = adminToken();
        MvcResult result = mockMvc.perform(get("/api/v1/projects")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("content");
        for (JsonNode project : content) {
            if (project.get("code").asText().startsWith("STR")) {
                mockMvc.perform(delete("/api/v1/projects/" + project.get("id").asText())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"test fixture cleanup\"}"));
            }
        }
    }

    @Test
    @DisplayName("a new site is given a store named after it, without being asked")
    void newSiteGetsItsStore() throws Exception {
        String token = adminToken();
        String projectId = createProject(token, "STR-1", "Store fixture project");
        String siteId = createSite(token, projectId, "STR-SITE-1", "Ranikhet Block C");

        mockMvc.perform(get("/api/v1/sites/" + siteId + "/stores")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("site-STR-SITE-1"))
                .andExpect(jsonPath("$[0].name").value("site-Ranikhet Block C"))
                // Default, or the receive screen would open on nothing and ask the supervisor
                // to choose from a list of one.
                .andExpect(jsonPath("$[0].defaultStore").value(true))
                .andExpect(jsonPath("$[0].active").value(true));
    }

    @Test
    @DisplayName("the stores register lists every store the caller can reach, with its site")
    void registerNamesTheSite() throws Exception {
        String token = adminToken();
        String projectId = createProject(token, "STR-2", "Store fixture project");
        createSite(token, projectId, "STR-SITE-2", "Almora Depot");

        MvcResult result = mockMvc.perform(get("/api/v1/stores")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode row = findByCode(objectMapper.readTree(result.getResponse().getContentAsString()),
                "site-STR-SITE-2");
        // The site's own name rides along: "site-STR-SITE-2" on its own tells an administrator
        // nothing about which gate it is behind.
        assertThat(row.get("siteCode").asText()).isEqualTo("STR-SITE-2");
        assertThat(row.get("siteName").asText()).isEqualTo("Almora Depot");
    }

    @Test
    @DisplayName("a second store can be added, renamed and made the default in its site's place")
    void administratorKeepsTheRegister() throws Exception {
        String token = adminToken();
        String projectId = createProject(token, "STR-3", "Store fixture project");
        String siteId = createSite(token, projectId, "STR-SITE-3", "Bhowali Yard");

        MvcResult created = mockMvc.perform(post("/api/v1/sites/" + siteId + "/stores")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"STR-STEEL-3","name":"Steel yard",
                                 "location":"north gate","defaultStore":true}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode store = objectMapper.readTree(created.getResponse().getContentAsString());

        // One default per site, or the receive screen opens on a coin toss.
        JsonNode stores = storesAt(token, siteId);
        assertThat(findByCode(stores, "site-STR-SITE-3").get("defaultStore").asBoolean()).isFalse();
        assertThat(findByCode(stores, "STR-STEEL-3").get("defaultStore").asBoolean()).isTrue();

        mockMvc.perform(put("/api/v1/stores/" + store.get("id").asText())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"STR-STEEL-3A","name":"Steel yard (north)",
                                 "location":"north gate","defaultStore":true,"active":false,
                                 "version":%d}
                                """.formatted(store.get("version").asLong())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("STR-STEEL-3A"))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    @DisplayName("a store nothing was recorded against is deleted outright")
    void unusedStoreIsDeleted() throws Exception {
        String token = adminToken();
        String projectId = createProject(token, "STR-4", "Store fixture project");
        String siteId = createSite(token, projectId, "STR-SITE-4", "Kaladhungi Shed");

        MvcResult created = mockMvc.perform(post("/api/v1/sites/" + siteId + "/stores")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"STR-SPARE-4","name":"Spare shed","defaultStore":false}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        mockMvc.perform(delete("/api/v1/stores/"
                        + objectMapper.readTree(created.getResponse().getContentAsString())
                                .get("id").asText())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(storesAt(token, siteId)).hasSize(1);
    }

    /**
     * The reason the guard exists: a store the ledger has posted against cannot be removed
     * without leaving stock transactions pointing at a lockup that is not there.
     */
    @Test
    @DisplayName("a store the ledger has touched refuses to be deleted, and says why")
    void usedStoreCannotBeDeleted() throws Exception {
        String token = adminToken();
        JsonNode stores = storesAt(token, SEEDED_SITE_A);
        assertThat(stores).isNotEmpty();

        MvcResult result = mockMvc.perform(delete("/api/v1/stores/"
                        + stores.get(0).get("id").asText())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();

        String detail = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("detail").asText();
        assertThat(detail).contains("inactive");
    }

    @Test
    @DisplayName("a supervisor cannot keep the stores register")
    void registerIsAdminWork() throws Exception {
        String supervisorToken = loginToken("vivek");
        JsonNode stores = storesAt(supervisorToken, SEEDED_SITE_A);
        assertThat(stores).isNotEmpty();
        String storeId = stores.get(0).get("id").asText();

        mockMvc.perform(delete("/api/v1/stores/" + storeId)
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/stores/" + storeId)
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"NOPE","name":"Not mine to rename","defaultStore":true,
                                 "active":true,"version":0}
                                """))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode storesAt(String token, String siteId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/sites/" + siteId + "/stores")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode findByCode(JsonNode array, String code) {
        for (JsonNode row : array) {
            if (row.get("code").asText().equals(code)) {
                return row;
            }
        }
        throw new AssertionError(code + " is not in " + array);
    }

    private String createProject(String token, String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createSite(String token, String projectId, String code, String name)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/sites")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"%s","code":"%s","name":"%s",
                                 "standardShiftHours":8.00,"monthlyWageDays":26}
                                """.formatted(projectId, code, name)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    /** Viplove holds ADMIN, the only role granted site:write and site:delete. */
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

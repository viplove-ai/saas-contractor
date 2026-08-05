package in.nirman.modules.masterdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The master-data lists, fetched the way every screen actually fetches them: with no search
 * term at all.
 *
 * <p>This test exists because that case was never covered and was broken from Phase 2 until
 * Phase 6 found it. The three search queries all opened with a bare {@code :q IS NULL}, which
 * gives PostgreSQL nothing to infer the parameter's type from; a null term went across untyped,
 * the driver settled on {@code bytea}, and {@code lower(bytea)} does not exist. Every one of
 * these endpoints answered 500 to the request the UI makes on page load. Searching <em>for</em>
 * something worked fine, which is exactly why it survived four phases — the tests that existed
 * all passed a term.</p>
 *
 * <p>So the assertion that matters is the boring one: no term, 200, rows. Parameterised over the
 * three lists together, because they share one defect and would share its return.</p>
 */
class MasterDataListingIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @ParameterizedTest(name = "GET /{0} with no search term")
    @ValueSource(strings = {"vendors", "labour-contractors", "materials"})
    @DisplayName("a master-data list loads with no search term")
    void listsLoadWithoutASearchTerm(String path) throws Exception {
        mockMvc.perform(get("/api/v1/" + path)
                        .header("Authorization", "Bearer " + loginToken("viplove")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].id").exists());
    }

    /** The path that always worked, kept alongside so the cast cannot silently break it. */
    @ParameterizedTest(name = "GET /{0} with a search term")
    @ValueSource(strings = {"vendors", "labour-contractors", "materials"})
    @DisplayName("a master-data list still filters when a search term is given")
    void listsStillFilterWithASearchTerm(String path) throws Exception {
        mockMvc.perform(get("/api/v1/" + path)
                        .param("q", "zzz-matches-nothing")
                        .header("Authorization", "Bearer " + loginToken("viplove")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    /** An empty string is what a cleared search box sends, and it means "no filter". */
    @Test
    @DisplayName("an empty search term is treated as no filter rather than as a literal")
    void anEmptySearchTermListsEverything() throws Exception {
        mockMvc.perform(get("/api/v1/labour-contractors")
                        .param("q", "")
                        .header("Authorization", "Bearer " + loginToken("viplove")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").exists());
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

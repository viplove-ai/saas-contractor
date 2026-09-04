package in.nirman.modules.dpr;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The project's gallery: every photograph on every report, read the other way round. See
 * {@code DprGalleryService}.
 */
@Import(InMemoryStorageConfig.class)
class DprGalleryIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String PROJECT = "30000000-0000-0000-0000-000000000001";
    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";
    private static final String SITE_B = "31000000-0000-0000-0000-000000000002";
    /** March 2025: clear of V903's June reports and of the April days the workflow test writes. */
    private static final LocalDate DAY_A = LocalDate.of(2025, 3, 3);
    private static final LocalDate DAY_A2 = LocalDate.of(2025, 3, 4);
    private static final LocalDate DAY_B = LocalDate.of(2025, 3, 3);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> reportIds = new ArrayList<>();

    @AfterEach
    void removeCreatedRows() {
        for (String id : reportIds) {
            jdbc.update("DELETE FROM dpr_photos WHERE dpr_id = ?::uuid", id);
            jdbc.update("DELETE FROM daily_progress_reports WHERE id = ?::uuid", id);
        }
        jdbc.update("DELETE FROM attachments WHERE object_key LIKE 'test/gallery/%'");
    }

    @Test
    @DisplayName("the gallery reads every report's photographs, newest day first, naming the day and the site")
    void galleryReadsAcrossReports() throws Exception {
        String vivek = loginToken("vivek");
        String viplove = loginToken("viplove");
        String first = openDay(vivek, SITE_A, DAY_A);
        photograph(vivek, first, "north wall");
        photograph(vivek, first, "footing");
        String second = openDay(vivek, SITE_A, DAY_A2);
        photograph(vivek, second, "slab");
        String annexe = openDay(viplove, SITE_B, DAY_B);
        photograph(viplove, annexe, "annexe gate");

        JsonNode page = gallery(viplove, "projectId=" + PROJECT + "&from=" + DAY_A + "&to=" + DAY_A2);
        assertThat(page.get("totalElements").asInt()).isEqualTo(4);
        List<String> captions = new ArrayList<>();
        page.get("content").forEach(photo -> captions.add(photo.get("caption").asText()));
        // Newest day first; within a day, the report's own order.
        assertThat(captions).containsExactly("slab", "north wall", "footing", "annexe gate");

        JsonNode wall = page.get("content").get(1);
        assertThat(wall.get("dprId").asText()).isEqualTo(first);
        assertThat(wall.get("reportDate").asText()).isEqualTo(DAY_A.toString());
        assertThat(wall.get("siteCode").asText()).isEqualTo("KSN-A");
        assertThat(wall.get("siteName").asText()).isEqualTo("Kausani Main Block");
        assertThat(wall.get("workflowStatus").asText()).isEqualTo("DRAFT");
        assertThat(wall.get("fileName").asText()).isEqualTo("site.jpg");
        assertThat(wall.get("dprNumber").asText()).isNotBlank();

        // Narrowed to one site when asked.
        JsonNode annexeOnly = gallery(viplove, "projectId=" + PROJECT + "&siteId=" + SITE_B
                + "&from=" + DAY_A + "&to=" + DAY_A2);
        assertThat(annexeOnly.get("totalElements").asInt()).isEqualTo(1);
        assertThat(annexeOnly.get("content").get(0).get("caption").asText()).isEqualTo("annexe gate");
    }

    @Test
    @DisplayName("a supervisor sees his own sites' photographs and not the annexe's")
    void narrowedToTheCallersSites() throws Exception {
        String vivek = loginToken("vivek");
        String viplove = loginToken("viplove");
        String mine = openDay(vivek, SITE_A, DAY_A);
        photograph(vivek, mine, "my wall");
        String theirs = openDay(viplove, SITE_B, DAY_B);
        photograph(viplove, theirs, "their gate");

        JsonNode page = gallery(vivek, "projectId=" + PROJECT + "&from=" + DAY_A + "&to=" + DAY_A);
        List<String> captions = new ArrayList<>();
        page.get("content").forEach(photo -> captions.add(photo.get("caption").asText()));
        assertThat(captions).containsExactly("my wall");

        // Asking for the annexe outright is refused, not answered empty.
        mockMvc.perform(get("/api/v1/dprs/photos?projectId=" + PROJECT + "&siteId=" + SITE_B)
                        .header("Authorization", "Bearer " + vivek))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an unknown project is a 404, not an empty gallery")
    void unknownProjectIsNotFound() throws Exception {
        String viplove = loginToken("viplove");
        mockMvc.perform(get("/api/v1/dprs/photos?projectId=" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + viplove))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode gallery(String token, String query) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/dprs/photos?" + query)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String openDay(String token, String siteId, LocalDate day) throws Exception {
        String id = UUID.randomUUID().toString();
        reportIds.add(id);
        mockMvc.perform(post("/api/v1/dprs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","siteId":"%s","reportDate":"%s","weather":"CLEAR"}"""
                                .formatted(id, siteId, day)))
                .andExpect(status().isCreated());
        return id;
    }

    private void photograph(String token, String dprId, String caption) throws Exception {
        String attachmentId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO attachments (id, org_id, owner_entity_type, file_name, content_type,
                                         size_bytes, bucket, object_key, kind)
                VALUES (?::uuid, '10000000-0000-0000-0000-000000000001', 'DPR',
                        'site.jpg', 'image/jpeg', 4096, 'nirman', ?, 'PHOTO')""",
                attachmentId, "test/gallery/" + attachmentId);
        mockMvc.perform(post("/api/v1/dprs/" + dprId + "/photos")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentId\":\"%s\",\"caption\":\"%s\"}"
                                .formatted(attachmentId, caption)))
                .andExpect(status().isOk());
    }

    private String loginToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}"
                                .formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
}

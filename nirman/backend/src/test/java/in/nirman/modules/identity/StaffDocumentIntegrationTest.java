package in.nirman.modules.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.AbstractIntegrationTest;
import in.nirman.InMemoryStorageConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The papers behind a staff record (V51).
 *
 * <p>Every figure on the record was typed off one of these and the documents themselves lived
 * in a folder at head office, so the record could state a bank account and never show the
 * passbook it was read from. What this test pins down is the three properties that make the
 * register worth having: the papers hang off the person rather than off a profile row that
 * may not exist yet, a claimed file cannot be discarded out from under the record, and
 * removing a row really removes the file.</p>
 */
// No MinIO on the machine this runs on, and none needed: the bucket is a map. See
// InMemoryStorageConfig.
@Import(InMemoryStorageConfig.class)
class StaffDocumentIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    /** Vivek: the one seeded login who is a pure supervisor, and holds no staff permission. */
    private static final String SUPERVISOR_USER = "20000000-0000-0000-0000-000000000003";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("a scan goes on a member's record, is shown as a picture, and comes back on the list")
    void putsAPaperOnTheRecord() throws Exception {
        String office = loginToken("viplove");
        String attachmentId = upload(office, "aadhaar.jpg", "image/jpeg");

        JsonNode added = add(office, SUPERVISOR_USER, """
                {"attachmentId":"%s","docType":"AADHAAR","note":"Front and back on one page"}"""
                .formatted(attachmentId));

        assertThat(added.get("docType").asText()).isEqualTo("AADHAAR");
        assertThat(added.get("note").asText()).isEqualTo("Front and back on one page");
        assertThat(added.get("fileName").asText()).isEqualTo("aadhaar.jpg");
        // A picture is drawn on the screen and a PDF is offered to be opened; the row says
        // which, so a browser is never asked to render something it cannot.
        assertThat(added.get("image").asBoolean()).isTrue();

        mockMvc.perform(get("/api/v1/staff/" + SUPERVISOR_USER + "/documents")
                        .header("Authorization", "Bearer " + office))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(added.get("id").asText()))
                .andExpect(jsonPath("$[0].attachmentId").value(attachmentId));
    }

    /** A signed letter is a PDF, and nothing about the record demands a photograph. */
    @Test
    @DisplayName("a PDF is accepted and marked as something the browser cannot draw")
    void takesAPdfAsWellAsAPhotograph() throws Exception {
        String office = loginToken("viplove");
        JsonNode added = add(office, SUPERVISOR_USER,
                "{\"attachmentId\":\"%s\",\"docType\":\"APPOINTMENT\"}"
                        .formatted(upload(office, "letter.pdf", "application/pdf")));

        assertThat(added.get("image").asBoolean()).isFalse();
        assertThat(added.has("note")).isFalse();
    }

    /**
     * Claiming is the whole of what stops a stray-upload sweep taking a record's evidence
     * away, and the one thing this feature borrows rather than builds.
     */
    @Test
    @DisplayName("a file on a record can no longer be discarded as a draft upload")
    void claimsTheFileItPutsOnTheRecord() throws Exception {
        String office = loginToken("viplove");
        String attachmentId = upload(office, "passbook.jpg", "image/jpeg");
        add(office, SUPERVISOR_USER,
                "{\"attachmentId\":\"%s\",\"docType\":\"BANK\"}".formatted(attachmentId));

        mockMvc.perform(delete("/api/v1/attachments/" + attachmentId)
                        .header("Authorization", "Bearer " + office))
                .andExpect(status().isUnprocessableEntity());
    }

    /** One file, one row. The same scan listed twice is two answers to one question. */
    @Test
    @DisplayName("the same file cannot be put on the record twice")
    void refusesTheSameFileTwice() throws Exception {
        String office = loginToken("viplove");
        String attachmentId = upload(office, "pan.jpg", "image/jpeg");
        add(office, SUPERVISOR_USER,
                "{\"attachmentId\":\"%s\",\"docType\":\"PAN\"}".formatted(attachmentId));

        mockMvc.perform(post("/api/v1/staff/" + SUPERVISOR_USER + "/documents")
                        .header("Authorization", "Bearer " + office)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentId\":\"%s\",\"docType\":\"PAN\"}"
                                .formatted(attachmentId)))
                .andExpect(status().isConflict());
    }

    /**
     * Really deleted, and the file with it. Keeping a photograph of somebody's identity
     * document because the register cannot bear to lose a row is the worse of two failures,
     * and what was read off it is typed into the record and stays there.
     */
    @Test
    @DisplayName("removing a paper takes the file with it, and no link can be signed afterwards")
    void removesThePaperAndTheFileTogether() throws Exception {
        String office = loginToken("viplove");
        String attachmentId = upload(office, "wrong-man.jpg", "image/jpeg");
        String documentId = add(office, SUPERVISOR_USER,
                "{\"attachmentId\":\"%s\",\"docType\":\"AADHAAR\"}".formatted(attachmentId))
                .get("id").asText();

        mockMvc.perform(delete("/api/v1/staff/" + SUPERVISOR_USER + "/documents/" + documentId)
                        .header("Authorization", "Bearer " + office))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/attachments/" + attachmentId + "/url")
                        .header("Authorization", "Bearer " + office))
                .andExpect(status().isNotFound());
    }

    /** Holding the papers is the same custody as holding the figures read off them. */
    @Test
    @DisplayName("a supervisor may neither read nor add a member's papers")
    void staysBehindTheStaffPermissions() throws Exception {
        String supervisor = loginToken("vivek");

        mockMvc.perform(get("/api/v1/staff/" + SUPERVISOR_USER + "/documents")
                        .header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/staff/" + SUPERVISOR_USER + "/documents")
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentId\":\"%s\",\"docType\":\"AADHAAR\"}"
                                .formatted(upload(supervisor, "mine.jpg", "image/jpeg"))))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode add(String token, String userId, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/staff/" + userId + "/documents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** A file in the bucket, waiting for a record to claim it. No site: a person is not one. */
    private String upload(String token, String fileName, String contentType) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/attachments")
                        .file(new MockMultipartFile("file", fileName, contentType,
                                new byte[]{1, 2, 3, 4}))
                        .param("ownerEntityType", "STAFF_DOCUMENT")
                        .param("kind", "DOCUMENT")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
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

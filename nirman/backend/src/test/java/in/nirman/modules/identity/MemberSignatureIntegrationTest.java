package in.nirman.modules.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.AbstractIntegrationTest;
import in.nirman.InMemoryStorageConfig;
import in.nirman.SignatureImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A member's signature: his own to put on and take off, and the one thing the app asks for
 * at sign-in until he does.
 */
@Import(InMemoryStorageConfig.class)
class MemberSignatureIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        jdbc.update("UPDATE users SET signature_attachment_id = NULL WHERE username = 'vivek'");
    }

    @Test
    @DisplayName("a signature is outstanding until it is uploaded, and the file is claimed")
    void outstandingUntilUploaded() throws Exception {
        String vivek = login("vivek");

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + vivek))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signatureAttachmentId").doesNotExist())
                .andExpect(jsonPath("$.outstanding[0]").value("SIGNATURE"));

        String attachmentId = upload(vivek, "signature.png", "image/png", SignatureImages.png());
        mockMvc.perform(put("/api/v1/auth/me/signature")
                        .header("Authorization", "Bearer " + vivek)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentId\":\"" + attachmentId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signatureAttachmentId").value(attachmentId))
                .andExpect(jsonPath("$.outstanding").isEmpty());

        // Claimed: the ordinary draft-upload delete refuses it, so nothing can pull the picture
        // out from under the documents that draw it.
        mockMvc.perform(delete("/api/v1/attachments/" + attachmentId)
                        .header("Authorization", "Bearer " + vivek))
                .andExpect(status().isUnprocessableEntity());

        // And the login itself says so, because the prompt reads the profile the login returns.
        assertThat(loginBody("vivek").get("user").get("outstanding")).isEmpty();
    }

    @Test
    @DisplayName("replacing a signature discards the old file; removing it clears the account")
    void replacingDiscardsTheOldOne() throws Exception {
        String vivek = login("vivek");
        String first = upload(vivek, "one.png", "image/png", SignatureImages.png());
        String second = upload(vivek, "two.png", "image/png", SignatureImages.png());
        set(vivek, first);
        set(vivek, second);

        // The first is gone: no signed link can be minted for it any more.
        mockMvc.perform(get("/api/v1/attachments/" + first + "/url")
                        .header("Authorization", "Bearer " + vivek))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/attachments/" + second + "/url")
                        .header("Authorization", "Bearer " + vivek))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/auth/me/signature")
                        .header("Authorization", "Bearer " + vivek))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signatureAttachmentId").doesNotExist())
                .andExpect(jsonPath("$.outstanding[0]").value("SIGNATURE"));
        mockMvc.perform(get("/api/v1/attachments/" + second + "/url")
                        .header("Authorization", "Bearer " + vivek))
                .andExpect(status().isNotFound());
    }

    /** The documents draw it into an img, so a PDF is refused here rather than at render. */
    @Test
    @DisplayName("a signature has to be a picture")
    void mustBeAPicture() throws Exception {
        String vivek = login("vivek");
        String pdf = upload(vivek, "signature.pdf", "application/pdf", "%PDF-1.4".getBytes());

        mockMvc.perform(put("/api/v1/auth/me/signature")
                        .header("Authorization", "Bearer " + vivek)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentId\":\"" + pdf + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("has to be a picture")));
    }

    // ------------------------------------------------------------------ helpers

    private void set(String token, String attachmentId) throws Exception {
        mockMvc.perform(put("/api/v1/auth/me/signature")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentId\":\"" + attachmentId + "\"}"))
                .andExpect(status().isOk());
    }

    private String upload(String token, String fileName, String contentType, byte[] bytes)
            throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/attachments")
                        .file(new MockMultipartFile("file", fileName, contentType, bytes))
                        .param("ownerEntityType", "USER_SIGNATURE")
                        .param("kind", "PHOTO")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result).get("id").asText();
    }

    private JsonNode loginBody(String username) throws Exception {
        return read(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}"
                                .formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn());
    }

    private String login(String username) throws Exception {
        return loginBody(username).get("accessToken").asText();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}

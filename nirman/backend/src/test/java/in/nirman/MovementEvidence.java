package in.nirman;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The photographs V52 demands of a movement of stock, put on a test's request body.
 *
 * <p>A delivery cannot be booked without a picture of what came off the lorry and of the paper
 * that came with it, and an issue cannot be recorded without a picture of what went out — see
 * {@code MaterialEvidencePolicy} for why. Every test that moves stock has to satisfy that, and
 * none of them is about it, so the two lines it costs live here rather than in eight files.</p>
 *
 * <p>The bucket is a map: any test using this needs {@code @Import(InMemoryStorageConfig.class)}.</p>
 */
public final class MovementEvidence {

    private MovementEvidence() {
    }

    /** The body with a picture of the load and one of the paper added to it. */
    public static String onReceipt(MockMvc mockMvc, ObjectMapper mapper, String token,
                                   String body) throws Exception {
        ObjectNode node = (ObjectNode) mapper.readTree(body);
        node.put("materialPhotoId", upload(mockMvc, mapper, token, "GOODS_RECEIPT", "load.jpg"));
        node.put("invoicePhotoId", upload(mockMvc, mapper, token, "GOODS_RECEIPT", "challan.jpg"));
        return mapper.writeValueAsString(node);
    }

    /** The body with a picture of the material going out added to it. */
    public static String onIssue(MockMvc mockMvc, ObjectMapper mapper, String token,
                                 String body) throws Exception {
        ObjectNode node = (ObjectNode) mapper.readTree(body);
        node.put("materialPhotoId", upload(mockMvc, mapper, token, "MATERIAL_ISSUE", "out.jpg"));
        return mapper.writeValueAsString(node);
    }

    /** One picture in the bucket, waiting for the movement to claim it. */
    public static String upload(MockMvc mockMvc, ObjectMapper mapper, String token,
                                String ownerEntityType, String fileName) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/attachments")
                        .file(new MockMultipartFile("file", fileName, MediaType.IMAGE_JPEG_VALUE,
                                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}))
                        .param("ownerEntityType", ownerEntityType)
                        .param("kind", "PHOTO")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
}

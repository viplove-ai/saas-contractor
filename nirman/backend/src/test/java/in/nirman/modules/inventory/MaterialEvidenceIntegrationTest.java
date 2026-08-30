package in.nirman.modules.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.AbstractIntegrationTest;
import in.nirman.InMemoryStorageConfig;
import in.nirman.MovementEvidence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a movement of stock has to have a picture of (V52).
 *
 * <p>A delivery is the one document in the system where the thing and the paper are both
 * standing in front of one man for five minutes and never again. The lorry tips its load and
 * leaves, the challan goes into a pocket, and the moving average, the month's consumption and
 * a supplier's account all rest on what he typed in those five minutes.</p>
 *
 * <p>Two pictures on a delivery because they answer two questions and neither stands for the
 * other; one on an issue, because there is no third party and no paper.</p>
 */
@Import(InMemoryStorageConfig.class)
class MaterialEvidenceIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String STORE_A = "32000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("a delivery with no picture of the load is refused, and told which one is missing")
    void refusesADeliveryWithNoPictureOfTheLoad() throws Exception {
        String storekeeper = login("vivek");
        String body = receiptBody();
        String invoicePhoto = MovementEvidence.upload(mockMvc, objectMapper, storekeeper,
                "GOODS_RECEIPT", "challan.jpg");

        mockMvc.perform(post("/api/v1/inventory/goods-receipts")
                        .header("Authorization", "Bearer " + storekeeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withPhotos(body, null, invoicePhoto)))
                .andExpect(status().isUnprocessableEntity())
                // Named, not "evidence required": he has to know which camera to point where.
                .andExpect(jsonPath("$.detail").value(containsString("came off the lorry")));
    }

    @Test
    @DisplayName("a delivery with no picture of the paper is refused too")
    void refusesADeliveryWithNoPictureOfThePaper() throws Exception {
        String storekeeper = login("vivek");
        String materialPhoto = MovementEvidence.upload(mockMvc, objectMapper, storekeeper,
                "GOODS_RECEIPT", "load.jpg");

        mockMvc.perform(post("/api/v1/inventory/goods-receipts")
                        .header("Authorization", "Bearer " + storekeeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withPhotos(receiptBody(), materialPhoto, null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("invoice or challan")));
    }

    /**
     * The load and the paper are two claims, and when they disagree the disagreement is the
     * point. One photograph standing for both settles it in whichever direction the camera
     * was pointed.
     */
    @Test
    @DisplayName("one photograph cannot stand for both claims")
    void refusesOnePhotographForTwoClaims() throws Exception {
        String storekeeper = login("vivek");
        String photo = MovementEvidence.upload(mockMvc, objectMapper, storekeeper,
                "GOODS_RECEIPT", "one.jpg");

        mockMvc.perform(post("/api/v1/inventory/goods-receipts")
                        .header("Authorization", "Bearer " + storekeeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withPhotos(receiptBody(), photo, photo)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("two different pictures")));
    }

    @Test
    @DisplayName("both pictures come back on the delivery, and neither can be discarded after")
    void keepsThePicturesWithTheDelivery() throws Exception {
        String storekeeper = login("vivek");
        MvcResult result = mockMvc.perform(post("/api/v1/inventory/goods-receipts")
                        .header("Authorization", "Bearer " + storekeeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MovementEvidence.onReceipt(mockMvc, objectMapper, storekeeper,
                                receiptBody())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.photos.length()").value(2))
                .andReturn();

        JsonNode photos = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("photos");
        assertThat(photos).extracting(photo -> photo.get("docType").asText())
                .containsExactlyInAnyOrder("MATERIAL", "INVOICE");

        // Claimed, so the man who uploaded them cannot discard them as stray drafts.
        mockMvc.perform(delete("/api/v1/attachments/"
                        + photos.get(0).get("attachmentId").asText())
                        .header("Authorization", "Bearer " + storekeeper))
                .andExpect(status().isUnprocessableEntity());
    }

    /** Nothing but a camera can say what came off a lorry, so a PDF there is not an answer. */
    @Test
    @DisplayName("the picture of the load has to be a picture")
    void refusesAPdfAsThePictureOfTheLoad() throws Exception {
        String storekeeper = login("vivek");
        String pdf = uploadPdf(storekeeper);
        String invoicePhoto = MovementEvidence.upload(mockMvc, objectMapper, storekeeper,
                "GOODS_RECEIPT", "challan.jpg");

        mockMvc.perform(post("/api/v1/inventory/goods-receipts")
                        .header("Authorization", "Bearer " + storekeeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withPhotos(receiptBody(), pdf, invoicePhoto)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("not a picture")));
    }

    /**
     * The invoice may be one, though. Half the suppliers who send an invoice at all send a
     * PDF, and refusing it would teach the office to photograph its own screen.
     */
    @Test
    @DisplayName("the paper may be a PDF, because that is how half of them arrive")
    void takesAPdfAsThePaper() throws Exception {
        String storekeeper = login("vivek");
        String materialPhoto = MovementEvidence.upload(mockMvc, objectMapper, storekeeper,
                "GOODS_RECEIPT", "load.jpg");

        mockMvc.perform(post("/api/v1/inventory/goods-receipts")
                        .header("Authorization", "Bearer " + storekeeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withPhotos(receiptBody(), materialPhoto,
                                uploadPdf(storekeeper))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.photos.length()").value(2));
    }

    @Test
    @DisplayName("an issue with no picture of what went out is refused")
    void refusesAnIssueWithNoPicture() throws Exception {
        mockMvc.perform(post("/api/v1/inventory/issues")
                        .header("Authorization", "Bearer " + login("vivek"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("material going out")));
    }

    @Test
    @DisplayName("an issue carries its one picture, and it comes back on the slip")
    void keepsThePictureWithTheIssue() throws Exception {
        String storekeeper = login("vivek");
        mockMvc.perform(post("/api/v1/inventory/issues")
                        .header("Authorization", "Bearer " + storekeeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MovementEvidence.onIssue(mockMvc, objectMapper, storekeeper,
                                issueBody())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.photos.length()").value(1))
                .andExpect(jsonPath("$.photos[0].docType").value("MATERIAL"));
    }

    // ------------------------------------------------------------------ helpers

    private String receiptBody() {
        String material = jdbc.queryForObject(
                "SELECT id::text FROM materials WHERE deleted_at IS NULL ORDER BY code LIMIT 1",
                String.class);
        String unit = jdbc.queryForObject(
                "SELECT base_unit_id::text FROM materials WHERE id = ?::uuid",
                String.class, material);
        return """
                {"id":"%s","storeId":"%s","receiptDate":"%s",
                 "lines":[{"materialId":"%s","unitId":"%s","quantity":10}]}"""
                .formatted(UUID.randomUUID(), STORE_A, LocalDate.now(), material, unit);
    }

    /** A material the seeded store actually holds, so the issue is refused for evidence only. */
    private String issueBody() {
        String material = jdbc.queryForObject("""
                SELECT material_id::text FROM stock_balances
                 WHERE store_id = ?::uuid AND quantity_base > 5 LIMIT 1""",
                String.class, STORE_A);
        String unit = jdbc.queryForObject(
                "SELECT base_unit_id::text FROM materials WHERE id = ?::uuid",
                String.class, material);
        return """
                {"id":"%s","storeId":"%s","issueDate":"%s","purpose":"Column shuttering",
                 "lines":[{"materialId":"%s","unitId":"%s","quantity":1}]}"""
                .formatted(UUID.randomUUID(), STORE_A, LocalDate.now(), material, unit);
    }

    private String withPhotos(String body, String materialPhotoId, String invoicePhotoId)
            throws Exception {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(body);
        if (materialPhotoId != null) {
            node.put("materialPhotoId", materialPhotoId);
        }
        if (invoicePhotoId != null) {
            node.put("invoicePhotoId", invoicePhotoId);
        }
        return objectMapper.writeValueAsString(node);
    }

    private String uploadPdf(String token) throws Exception {
        MvcResult result = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .multipart("/api/v1/attachments")
                                .file(new org.springframework.mock.web.MockMultipartFile("file",
                                        "invoice.pdf", MediaType.APPLICATION_PDF_VALUE,
                                        "%PDF-1.4".getBytes()))
                                .param("ownerEntityType", "GOODS_RECEIPT")
                                .param("kind", "BILL")
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String login(String username) throws Exception {
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

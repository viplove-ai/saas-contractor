package in.nirman.modules.tender;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.nirman.AbstractIntegrationTest;
import in.nirman.InMemoryStorageConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two-step import, end to end.
 *
 * <p>The property worth defending is that the preview is genuinely a preview: an upload that
 * a user abandons must leave no project, no BOQ and no tender record behind. The second is
 * that a confirmed import writes all three together, with the schedule priced from the
 * quantities and rates rather than from any total the client sent.</p>
 */
@Import(InMemoryStorageConfig.class)
class NitImportIntegrationTest extends AbstractIntegrationTest {

    private static final Path FIXTURE =
            Path.of("src/test/resources/nit/pdf/almora-30-tile-work.pdf");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("previewing a NIT reads the schedule and writes no project data")
    void previewPersistsNothingButTheFile() throws Exception {
        assumeThat(Files.exists(FIXTURE)).as("fixture PDF present").isTrue();
        String token = loginToken("viplove");

        // Counted rather than asserted as zero: the suite shares one database, so what is
        // being checked is that this call adds nothing, not that nothing exists.
        long projectsBefore = count("projects");
        long boqBefore = count("boq_items");
        long tendersBefore = count("nit_documents");

        JsonNode preview = preview(token);

        assertThat(preview.get("pageCount").asInt()).isEqualTo(134);
        assertThat(preview.get("attachmentId").asText()).isNotBlank();
        assertThat(preview.get("suggestedName").asText())
                .contains("Tile work in Type-2 and Type-3 quarters");
        assertThat(preview.get("nitNumber").asText()).isEqualTo("30/EE/ACD/CPWD/Almora/2026-27");
        assertThat(new BigDecimal(preview.get("contractValue").asText()))
                .isEqualByComparingTo("4226546");
        assertThat(preview.get("boqLines")).isNotEmpty();

        // Item numbers are prefixed by work part so the civil and electrical schedules of a
        // composite tender cannot collide on uq_boq_project_number.
        assertThat(preview.get("boqLines").get(0).get("itemNumber").asText()).startsWith("C/");

        assertThat(count("projects")).isEqualTo(projectsBefore);
        assertThat(count("boq_items")).isEqualTo(boqBefore);
        assertThat(count("nit_documents")).isEqualTo(tendersBefore);
    }

    @Test
    @DisplayName("confirming writes the project, its schedule and the tender in one go")
    void confirmWritesEverything() throws Exception {
        assumeThat(Files.exists(FIXTURE)).as("fixture PDF present").isTrue();
        String token = loginToken("viplove");

        JsonNode preview = preview(token);
        String code = "NIT-IT-" + UUID.randomUUID().toString().substring(0, 8);

        MvcResult created = mockMvc.perform(post("/api/v1/nit-imports")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest(preview, code))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.project.code").value(code))
                .andReturn();

        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        UUID projectId = UUID.fromString(body.get("project").get("id").asText());
        int lineCount = body.get("boqLineCount").asInt();
        assertThat(lineCount).isEqualTo(preview.get("boqLines").size());

        // Every stored line is priced from its own quantity and rate, so the project's BOQ
        // value is arithmetic rather than a number the client asserted.
        BigDecimal storedValue = jdbc.queryForObject(
                "SELECT COALESCE(SUM(contract_amount), 0) FROM boq_items WHERE project_id = ?",
                BigDecimal.class, projectId);
        BigDecimal derived = jdbc.queryForObject(
                "SELECT COALESCE(SUM(ROUND(contract_quantity * contract_rate, 2)), 0) "
                        + "FROM boq_items WHERE project_id = ?", BigDecimal.class, projectId);
        assertThat(storedValue).isEqualByComparingTo(derived);
        assertThat(new BigDecimal(body.get("boqValue").asText())).isEqualByComparingTo(storedValue);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM boq_items WHERE project_id = ? AND source <> 'NIT_IMPORT'",
                Long.class, projectId)).isZero();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM nit_documents WHERE project_id = ?", Long.class, projectId))
                .isEqualTo(1);

        // The draft upload is now owned by the tender record, which is what stops it being
        // discarded as an orphan.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attachments WHERE id = ? AND owner_entity_id = ?",
                Long.class,
                UUID.fromString(preview.get("attachmentId").asText()), projectId)).isEqualTo(1);

        mockMvc.perform(get("/api/v1/nit-imports/projects/" + projectId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.nitNo").value("30/EE/ACD/CPWD/Almora/2026-27"))
                .andExpect(jsonPath("$.fields.division").value("Almora"));
    }

    @Test
    @DisplayName("Schedule F survives the round trip from preview to stored tender")
    void scheduleTermsAreReadPreviewedAndStored() throws Exception {
        assumeThat(Files.exists(FIXTURE)).as("fixture PDF present").isTrue();
        String token = loginToken("viplove");

        JsonNode preview = preview(token);
        JsonNode terms = preview.get("scheduleTerms");

        // This notice allows six months, reckons the start ten days after the acceptance
        // letter, and stipulates six milestones at 15% intervals of the tendered amount.
        assertThat(terms.get("completionValue").asInt()).isEqualTo(6);
        assertThat(terms.get("completionUnit").asText()).isEqualTo("MONTHS");
        assertThat(terms.get("startReckoningDays").asInt()).isEqualTo(10);
        assertThat(terms.get("clause7aApplicable").asBoolean()).isTrue();
        assertThat(terms.get("milestones")).hasSize(6);
        assertThat(terms.get("milestones").get(0).get("financialPercent").asDouble())
                .isEqualTo(15.0);
        assertThat(terms.get("milestones").get(5).get("financialPercent").asDouble())
                .isEqualTo(100.0);

        // Clause 7: no running account bill below seven lakh of gross work.
        assertThat(terms.get("interimMinimums")).hasSize(1);
        assertThat(new BigDecimal(terms.get("interimMinimums").get(0).get("amount").asText()))
                .isEqualByComparingTo("700000");

        String code = "NIT-SF-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult created = mockMvc.perform(post("/api/v1/nit-imports")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest(preview, code))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID projectId = UUID.fromString(objectMapper
                .readTree(created.getResponse().getContentAsString())
                .get("project").get("id").asText());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM nit_milestones m JOIN nit_documents d ON d.id = "
                        + "m.nit_document_id WHERE d.project_id = ?", Long.class, projectId))
                .isEqualTo(6);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM nit_interim_minimums i JOIN nit_documents d ON d.id = "
                        + "i.nit_document_id WHERE d.project_id = ?", Long.class, projectId))
                .isEqualTo(1);

        // Read back through the API, in the order the milestones fall due.
        mockMvc.perform(get("/api/v1/nit-imports/projects/" + projectId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleTerms.completionValue").value(6))
                .andExpect(jsonPath("$.scheduleTerms.completionUnit").value("MONTHS"))
                .andExpect(jsonPath("$.scheduleTerms.startReckoningDays").value(10))
                .andExpect(jsonPath("$.scheduleTerms.milestones.length()").value(6))
                .andExpect(jsonPath("$.scheduleTerms.milestones[0].sequence").value(1))
                .andExpect(jsonPath("$.scheduleTerms.milestones[5].sequence").value(6))
                .andExpect(jsonPath("$.scheduleTerms.milestones[5].timeAllowedValue").value(6));
    }

    @Test
    @DisplayName("a repeated milestone in the confirm payload is dropped, not fatal")
    void repeatedMilestonesAreDeduplicated() throws Exception {
        assumeThat(Files.exists(FIXTURE)).as("fixture PDF present").isTrue();
        String token = loginToken("viplove");

        JsonNode preview = preview(token);
        ObjectNode request = confirmRequest(preview,
                "NIT-MS-" + UUID.randomUUID().toString().substring(0, 8));
        // A client echoing the list back twice must not lose the whole import to a unique
        // index; the second copy of a milestone is a client bug, not a reason to refuse a
        // tender somebody has just spent ten minutes reviewing.
        ArrayNode milestones = (ArrayNode) request.get("scheduleTerms").get("milestones");
        milestones.add(milestones.get(0).deepCopy());

        MvcResult created = mockMvc.perform(post("/api/v1/nit-imports")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID projectId = UUID.fromString(objectMapper
                .readTree(created.getResponse().getContentAsString())
                .get("project").get("id").asText());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM nit_milestones m JOIN nit_documents d ON d.id = "
                        + "m.nit_document_id WHERE d.project_id = ?", Long.class, projectId))
                .isEqualTo(6);
    }

    @Test
    @DisplayName("two lines claiming the same item number are refused")
    void duplicateItemNumbersAreRejected() throws Exception {
        assumeThat(Files.exists(FIXTURE)).as("fixture PDF present").isTrue();
        String token = loginToken("viplove");

        JsonNode preview = preview(token);
        ObjectNode request = confirmRequest(preview,
                "NIT-DUP-" + UUID.randomUUID().toString().substring(0, 8));
        ArrayNode lines = (ArrayNode) request.get("boqLines");
        assumeThat(lines.size()).isGreaterThan(1);
        ((ObjectNode) lines.get(1)).put("itemNumber", lines.get(0).get("itemNumber").asText());

        mockMvc.perform(post("/api/v1/nit-imports")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a supervisor cannot import a tender")
    void supervisorCannotImport() throws Exception {
        assumeThat(Files.exists(FIXTURE)).as("fixture PDF present").isTrue();
        String token = loginToken("vivek");

        mockMvc.perform(multipart("/api/v1/nit-imports/preview")
                        .file(pdfPart())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a file that is not a PDF is rejected with a reason")
    void nonPdfIsRejected() throws Exception {
        String token = loginToken("viplove");
        MockMultipartFile notAPdf = new MockMultipartFile("file", "notes.pdf",
                MediaType.APPLICATION_PDF_VALUE, "This is not a tender.".getBytes());

        mockMvc.perform(multipart("/api/v1/nit-imports/preview")
                        .file(notAPdf)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://nirman/errors/nit.unreadable"));
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode preview(String token) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/nit-imports/preview")
                        .file(pdfPart())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static MockMultipartFile pdfPart() throws Exception {
        return new MockMultipartFile("file", "almora-30-tile-work.pdf",
                MediaType.APPLICATION_PDF_VALUE, Files.readAllBytes(FIXTURE));
    }

    /** Turns a preview into the confirm request a reviewing user would submit unchanged. */
    private ObjectNode confirmRequest(JsonNode preview, String code) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("attachmentId", preview.get("attachmentId").asText());
        request.put("pageCount", preview.get("pageCount").asInt());
        request.set("fields", preview.get("fields"));
        request.set("scheduleTerms", preview.get("scheduleTerms"));
        request.set("warnings", preview.get("warnings"));

        ObjectNode project = objectMapper.createObjectNode();
        project.put("code", code);
        project.put("name", preview.get("suggestedName").asText());
        project.put("nitNumber", preview.get("nitNumber").asText());
        project.put("tenderReference", preview.get("tenderReference").asText());
        project.put("contractValue", preview.get("contractValue").asText());
        request.set("project", project);

        ArrayNode lines = objectMapper.createArrayNode();
        preview.get("boqLines").forEach(line -> {
            ObjectNode confirmed = objectMapper.createObjectNode();
            confirmed.put("itemNumber", line.get("itemNumber").asText());
            confirmed.put("description", line.get("description").asText());
            confirmed.put("unitCode", line.get("unitCode").asText());
            confirmed.put("quantity", line.get("quantity").asText());
            confirmed.put("rate", line.get("rate").asText());
            confirmed.put("workPart", line.get("workPart").asText());
            confirmed.put("category", line.get("category").asText());
            confirmed.put("synthetic", line.get("synthetic").asBoolean());
            lines.add(confirmed);
        });
        request.set("boqLines", lines);
        return request;
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
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

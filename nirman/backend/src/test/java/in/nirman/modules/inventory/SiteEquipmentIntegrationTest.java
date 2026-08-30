package in.nirman.modules.inventory;

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
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The plant register, and the split it exists for.
 *
 * <p>Anybody standing at the site may say a machine is there — a supervisor who cannot record
 * the mixer in front of him will not record it at all, and the register becomes a list of
 * what the office remembered. But an entry nobody checked is a claim, so it waits as PENDING
 * until an administrator agrees, and correcting or removing a row is the office's alone.</p>
 */
@Import(InMemoryStorageConfig.class)
class SiteEquipmentIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Nirman@123";
    private static final String STORE_A = "32000000-0000-0000-0000-000000000001";
    private static final String SITE_A = "31000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("a supervisor enters a machine and it waits for the office")
    void theSiteEntersAndTheOfficeDecides() throws Exception {
        String supervisor = loginToken("vivek");

        JsonNode entered = enter(supervisor, body(UUID.randomUUID(), "Concrete Mixer 10/7", null));

        assertThat(entered.get("status").asText()).isEqualTo("PENDING");
        assertThat(entered.get("quantity").asInt()).isEqualTo(1);
        // Nothing about the ledger moved: plant is held, not consumed.
        mockMvc.perform(get("/api/v1/inventory/stock")
                        .param("storeId", STORE_A)
                        .header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.materialName == 'Concrete Mixer 10/7')]")
                        .isEmpty());
    }

    @Test
    @DisplayName("an administrator accepts it, and the register says so")
    void theOfficeAcceptsIt() throws Exception {
        String id = UUID.randomUUID().toString();
        enter(loginToken("vivek"), body(UUID.fromString(id), "Needle Vibrator", "VIB-114"));

        mockMvc.perform(post("/api/v1/inventory/equipment/" + id + "/decision")
                        .header("Authorization", "Bearer " + loginToken("viplove"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"ACCEPT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.decidedAt").isNotEmpty());
    }

    /**
     * The rule the whole feature turns on. A supervisor who could accept his own entry would
     * make the workflow decoration.
     */
    @Test
    @DisplayName("the site may not accept its own entry")
    void theSiteMayNotAcceptItsOwnEntry() throws Exception {
        String supervisor = loginToken("vivek");
        String id = UUID.randomUUID().toString();
        enter(supervisor, body(UUID.fromString(id), "Bar Bending Machine", null));

        mockMvc.perform(post("/api/v1/inventory/equipment/" + id + "/decision")
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"ACCEPT\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * Removing stays the office's, and correcting is no longer restricted to the author.
     *
     * <p>Authorship was the rule until the shift roster made a nonsense of it: the man who
     * entered the machine in March is on another site in June, and the one who can see that
     * its jaw has come off was refused the row. So a supervisor corrects any machine standing
     * at a site he is posted to — and the correction re-opens it, which is what keeps the
     * office's acceptance meaning something.</p>
     */
    @Test
    @DisplayName("the site may not remove a row, and corrects anybody's")
    void theSiteMayNotDeleteButCorrectsAnothersRow() throws Exception {
        String supervisor = loginToken("vivek");
        String id = UUID.randomUUID().toString();
        JsonNode entered = enter(supervisor, body(UUID.fromString(id), "Plate Compactor", null));

        mockMvc.perform(delete("/api/v1/inventory/equipment/" + id)
                        .header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isForbidden());

        // The administrator's own entry, accepted on the way in — and the supervisor may
        // still say what has become of the machine, which sends it back to be read again.
        String officeRow = UUID.randomUUID().toString();
        JsonNode office = enter(loginToken("viplove"),
                body(UUID.fromString(officeRow), "Office Tower Light", null));

        mockMvc.perform(put("/api/v1/inventory/equipment/" + officeRow)
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correction("Office Tower Light (bulb gone)", "UNDER_REPAIR",
                                office.get("version").asLong())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(put("/api/v1/inventory/equipment/" + id)
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correction("Plate Compactor 3T", "WORKING",
                                entered.get("version").asLong())))
                .andExpect(status().isOk());
    }

    /**
     * The change V25 did not allow for: the mixer the office accepted in March has broken, and
     * the man looking at it is the only one who can say so.
     *
     * <p>What keeps V25's invariant intact is that the correction does not sneak onto the
     * register — it puts the row back in the queue and takes the old decision off it, so the
     * office reads the changed entry before it counts again.</p>
     */
    @Test
    @DisplayName("a correction by the site re-opens the entry and drops the old decision")
    void aSiteCorrectionSendsTheRowBackForApproval() throws Exception {
        String supervisor = loginToken("vivek");
        String admin = loginToken("viplove");
        String id = UUID.randomUUID().toString();
        enter(supervisor, body(UUID.fromString(id), "Site Mixer", null));

        // The decision is itself a write, so it moves the version on. The correction has to
        // carry the version as it stands after the acceptance, which is what the screen holds
        // — every mutation there refetches the row.
        JsonNode accepted = readTree(mockMvc.perform(
                        post("/api/v1/inventory/equipment/" + id + "/decision")
                                .header("Authorization", "Bearer " + admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"action\":\"ACCEPT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andReturn());

        JsonNode corrected = readTree(mockMvc.perform(
                        put("/api/v1/inventory/equipment/" + id)
                                .header("Authorization", "Bearer " + supervisor)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(correction("Site Mixer", "UNDER_REPAIR",
                                        accepted.get("version").asLong())))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(corrected.get("condition").asText()).isEqualTo("UNDER_REPAIR");
        assertThat(corrected.get("status").asText()).isEqualTo("PENDING");
        // Dropped with the status: a decidedBy left behind would name the administrator as
        // having agreed to wording he never read.
        assertThat(corrected.hasNonNull("decidedAt")).isFalse();
    }

    /** The office correcting its own register is the office deciding, not asking itself. */
    @Test
    @DisplayName("a correction by the office leaves the entry on the register")
    void anOfficeCorrectionDoesNotReopen() throws Exception {
        String admin = loginToken("viplove");
        String id = UUID.randomUUID().toString();
        JsonNode entered = enter(admin, body(UUID.fromString(id), "Office Mixer", null));
        assertThat(entered.get("status").asText()).isEqualTo("ACCEPTED");

        JsonNode corrected = readTree(mockMvc.perform(
                        put("/api/v1/inventory/equipment/" + id)
                                .header("Authorization", "Bearer " + admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(correction("Office Mixer", "IDLE",
                                        entered.get("version").asLong())))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(corrected.get("status").asText()).isEqualTo("ACCEPTED");
    }

    private String correction(String name, String condition, long version) {
        return """
                {"storeId":"%s","name":"%s","quantity":1,
                 "ownership":"OWNED","condition":"%s","version":%d}"""
                .formatted(STORE_A, name, condition, version);
    }

    /**
     * Nobody checks his own work here either — but there is nobody left to check the person
     * who does the checking, and a queue full of his own entries is one he learns to click
     * through without reading.
     */
    @Test
    @DisplayName("an administrator's own entry is on the register the moment he makes it")
    void anAdministratorsOwnEntryNeedsNoApproval() throws Exception {
        JsonNode entered = enter(loginToken("viplove"),
                body(UUID.randomUUID(), "Tower Light", null));

        assertThat(entered.get("status").asText()).isEqualTo("ACCEPTED");
    }

    /** Two rows carrying one registration are two machines that are one machine. */
    @Test
    @DisplayName("a number already on the register is refused, and named")
    void refusesADuplicateAssetNumber() throws Exception {
        String token = loginToken("viplove");
        enter(token, body(UUID.randomUUID(), "Hired JCB", "UK-04-CA-1120"));

        mockMvc.perform(post("/api/v1/inventory/equipment")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), "Second JCB", " uk-04-ca-1120 ")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Hired JCB")));
    }

    /** A hired machine costs money every day it stands there, and somebody is owed it. */
    @Test
    @DisplayName("a hired machine has to say who it is hired from")
    void hiredPlantNeedsASupplier() throws Exception {
        mockMvc.perform(post("/api/v1/inventory/equipment")
                        .header("Authorization", "Bearer " + loginToken("vivek"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","storeId":"%s","name":"Hired Breaker",
                                 "ownership":"HIRED","condition":"WORKING"}"""
                                .formatted(UUID.randomUUID(), STORE_A)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("the register lists what is at the site, pending entries and all")
    void listsTheRegisterForASite() throws Exception {
        String token = loginToken("viplove");
        enter(token, body(UUID.randomUUID(), "Site Register Mixer", null));

        MvcResult result = mockMvc.perform(get("/api/v1/inventory/equipment")
                        .param("siteId", SITE_A)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode rows = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(rows.isArray()).isTrue();
        assertThat(rows.toString()).contains("Site Register Mixer");
        // The store's name rather than its id: nobody knows a yard by uuid.
        assertThat(rows.get(0).hasNonNull("storeName")).isTrue();
    }

    /** Re-sending the same entry from a phone that lost the answer is one machine, not two. */
    @Test
    @DisplayName("the same entry sent twice is one machine")
    void isIdempotentOnTheClientId() throws Exception {
        String token = loginToken("vivek");
        UUID id = UUID.randomUUID();

        JsonNode first = enter(token, body(id, "Winch", null));
        JsonNode again = enter(token, body(id, "Winch", null));

        assertThat(again.get("id").asText()).isEqualTo(first.get("id").asText());
    }

    /** Deleted is off the register but not out of the history: the row is kept. */
    @Test
    @DisplayName("an administrator removes a row and it leaves the register")
    void theOfficeRemovesARow() throws Exception {
        String token = loginToken("viplove");
        String id = UUID.randomUUID().toString();
        enter(token, body(UUID.fromString(id), "Mistaken Entry", null));

        mockMvc.perform(delete("/api/v1/inventory/equipment/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/inventory/equipment/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ the photographs

    /**
     * The half the register turns on. "Concrete mixer, no number on it", typed from a yard
     * forty kilometres away, is a sentence; the picture is the evidence the office is short
     * of — and it arrives on a different day from the entry, because the machine is written
     * down at the gate in the rain and photographed on Thursday.
     */
    @Test
    @DisplayName("the man who entered a machine photographs it afterwards, while it still waits")
    void theSitePhotographsItsOwnPendingEntry() throws Exception {
        String supervisor = loginToken("vivek");
        String id = UUID.randomUUID().toString();
        JsonNode entered = enter(supervisor, body(UUID.fromString(id), "Plate Compactor", null));
        // Entered without one, which is the ordinary case and never a reason to refuse it.
        assertThat(entered.get("photos")).isEmpty();

        String attachmentId = uploadPhoto(supervisor, "mixer.jpg");
        addPhotos(supervisor, id, attachmentId)
                .andExpect(jsonPath("$.photos.length()").value(1))
                .andExpect(jsonPath("$.photos[0].attachmentId").value(attachmentId))
                // The entry itself is untouched: a photograph is not a decision about it.
                .andExpect(jsonPath("$.status").value("PENDING"));

        // And the file is now the machine's, so nothing can discard it as a stray upload.
        mockMvc.perform(delete("/api/v1/attachments/" + attachmentId)
                        .header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isUnprocessableEntity());
    }

    /**
     * The reason one column was the wrong number. The plate identifies the machine and the
     * cracked jaw is why anybody is looking at it, and they are not in the same frame — so the
     * second picture used to replace the first with nothing on the screen to say so.
     */
    @Test
    @DisplayName("a machine carries as many pictures as it takes, oldest first")
    void aMachineCarriesSeveralPictures() throws Exception {
        String supervisor = loginToken("vivek");
        String id = UUID.randomUUID().toString();
        enter(supervisor, body(UUID.fromString(id), "Rock Breaker", null));

        String plate = uploadPhoto(supervisor, "plate.jpg");
        String damage = uploadPhoto(supervisor, "jaw.jpg");
        // Both in one request: somebody standing at the machine takes them in one go, and
        // sending them one at a time is where half of them are lost on a site connection.
        addPhotos(supervisor, id, plate, damage)
                .andExpect(jsonPath("$.photos.length()").value(2))
                .andExpect(jsonPath("$.photos[0].attachmentId").value(plate))
                .andExpect(jsonPath("$.photos[1].attachmentId").value(damage));

        // A third, later. Nothing replaces anything.
        addPhotos(supervisor, id, uploadPhoto(supervisor, "hoses.jpg"))
                .andExpect(jsonPath("$.photos.length()").value(3));
    }

    /**
     * The photograph usually arrives on a later day than the entry, and the office may well
     * have accepted the line of text in between. So the man who entered the machine may still
     * photograph it — but adding to a row that already carries pictures is a new claim about
     * what the office already read, and it goes back in the queue like any other.
     */
    @Test
    @DisplayName("the site may photograph an accepted entry; a further picture re-opens it")
    void theSiteMayPhotographADecidedEntry() throws Exception {
        String supervisor = loginToken("vivek");
        String office = loginToken("viplove");
        String id = UUID.randomUUID().toString();
        enter(supervisor, body(UUID.fromString(id), "Decided Mixer", null));
        mockMvc.perform(post("/api/v1/inventory/equipment/" + id + "/decision")
                        .header("Authorization", "Bearer " + office)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"ACCEPT\"}"))
                .andExpect(status().isOk());

        // The first picture on a row that had none: nothing the office read has changed, so
        // the acceptance stands. Punishing him for finally photographing it teaches him not to.
        addPhotos(supervisor, id, uploadPhoto(supervisor, "late.jpg"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        // A second one is a further claim about a machine the office has already agreed to.
        addPhotos(supervisor, id, uploadPhoto(supervisor, "second.jpg"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // The office may, on the same row, at any time — and its own picture is a decision
        // rather than a claim, so the row does not go back to itself.
        addPhotos(office, id, uploadPhoto(office, "office.jpg"))
                .andExpect(jsonPath("$.photos.length()").value(3))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    /**
     * Taking evidence away from an entry the office accepted always changes what it agreed to,
     * so removal has no equivalent of the "first picture" exemption above.
     */
    @Test
    @DisplayName("a picture comes off by its own id, and takes its file with it")
    void aPictureComesOffAndTakesItsFile() throws Exception {
        String supervisor = loginToken("vivek");
        String id = UUID.randomUUID().toString();
        enter(supervisor, body(UUID.fromString(id), "Rephotographed Mixer", null));
        String keep = uploadPhoto(supervisor, "keep.jpg");
        String thumb = uploadPhoto(supervisor, "thumb-over-lens.jpg");
        JsonNode withBoth = objectMapper.readTree(addPhotos(supervisor, id, keep, thumb)
                .andReturn().getResponse().getContentAsString());
        String badPhotoId = withBoth.get("photos").get(1).get("id").asText();

        mockMvc.perform(delete("/api/v1/inventory/equipment/" + id + "/photos/" + badPhotoId)
                        .header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photos.length()").value(1))
                .andExpect(jsonPath("$.photos[0].attachmentId").value(keep));

        // The file went with it: no further signed link can be minted for a picture nobody
        // meant to keep, which is the rule V51 drew for a staff document.
        mockMvc.perform(get("/api/v1/attachments/" + thumb + "/url")
                        .header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isNotFound());
    }

    /** A photograph belongs to a machine, and cannot be unpicked from one it never joined. */
    @Test
    @DisplayName("a picture cannot be removed through a machine it does not belong to")
    void aPictureBelongsToItsOwnMachine() throws Exception {
        String office = loginToken("viplove");
        String mine = UUID.randomUUID().toString();
        String theirs = UUID.randomUUID().toString();
        enter(office, body(UUID.fromString(mine), "Mine Mixer", null));
        enter(office, body(UUID.fromString(theirs), "Their Mixer", null));
        JsonNode withPhoto = objectMapper.readTree(
                addPhotos(office, mine, uploadPhoto(office, "mine.jpg"))
                        .andReturn().getResponse().getContentAsString());
        String photoId = withPhoto.get("photos").get(0).get("id").asText();

        mockMvc.perform(delete("/api/v1/inventory/equipment/" + theirs + "/photos/" + photoId)
                        .header("Authorization", "Bearer " + office))
                .andExpect(status().isNotFound());
    }

    /** A machine is identified by a picture of it. A PDF identifies nothing. */
    @Test
    @DisplayName("a file that is not a picture is refused as a photograph")
    void onlyAPictureIsAPhotograph() throws Exception {
        String office = loginToken("viplove");
        String id = UUID.randomUUID().toString();
        enter(office, body(UUID.fromString(id), "Documented Mixer", null));

        MvcResult uploaded = mockMvc.perform(multipart("/api/v1/attachments")
                        .file(new MockMultipartFile("file", "invoice.pdf", "application/pdf",
                                "%PDF-1.4".getBytes(StandardCharsets.UTF_8)))
                        .param("ownerEntityType", "SITE_EQUIPMENT")
                        .param("kind", "DOCUMENT")
                        .header("Authorization", "Bearer " + office))
                .andExpect(status().isCreated())
                .andReturn();
        String attachmentId = objectMapper.readTree(uploaded.getResponse().getContentAsString())
                .get("id").asText();

        addPhotosRaw(office, id, attachmentId)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("is not one")));
    }

    // ------------------------------------------------------------------ helpers

    /** A picture in the bucket, waiting for a record to claim it. */
    /** Adds pictures and asserts the call succeeded, returning the actions for chaining. */
    private ResultActions addPhotos(String token, String equipmentId, String... attachmentIds)
            throws Exception {
        return addPhotosRaw(token, equipmentId, attachmentIds).andExpect(status().isOk());
    }

    /** The same call without the success expectation, for the refusals. */
    private ResultActions addPhotosRaw(String token, String equipmentId, String... attachmentIds)
            throws Exception {
        String ids = java.util.Arrays.stream(attachmentIds)
                .map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        return mockMvc.perform(post("/api/v1/inventory/equipment/" + equipmentId + "/photos")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"attachmentIds\":[" + ids + "]}"));
    }

    private String uploadPhoto(String token, String fileName) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/attachments")
                        .file(new MockMultipartFile("file", fileName, "image/jpeg",
                                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}))
                        .param("ownerEntityType", "SITE_EQUIPMENT")
                        .param("kind", "PHOTO")
                        .param("siteId", SITE_A)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String body(UUID id, String name, String assetCode) {
        return """
                {"id":"%s","storeId":"%s","name":"%s"%s,
                 "ownership":"OWNED","condition":"WORKING"}"""
                .formatted(id, STORE_A, name,
                        assetCode == null ? "" : ",\"assetCode\":\"" + assetCode + "\"");
    }

    private JsonNode enter(String token, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/inventory/equipment")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode readTree(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
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

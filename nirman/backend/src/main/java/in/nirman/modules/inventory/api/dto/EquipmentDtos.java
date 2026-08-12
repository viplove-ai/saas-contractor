package in.nirman.modules.inventory.api.dto;

import in.nirman.modules.inventory.domain.SiteEquipment.Condition;
import in.nirman.modules.inventory.domain.SiteEquipment.Ownership;
import in.nirman.modules.inventory.domain.SiteEquipment.Status;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** Request and response shapes for the equipment held at a site. */
public final class EquipmentDtos {

    private EquipmentDtos() {
    }

    /**
     * @param status       PENDING until the office accepts it. The screens show accepted
     *                     plant as the register and everything else as a question.
     * @param supplierName resolved here rather than left as an id, because a hired machine
     *                     is read as "whose is it" and nobody knows a vendor by uuid.
     * @param photoAttachmentId the picture of the machine, or null. Left as an id rather than
     *                     a URL: a download link is signed, short-lived and re-checked against
     *                     the caller's sites when it is asked for, so baking one into every
     *                     row of a register would issue forty links nobody opens.
     */
    public record EquipmentResponse(
            UUID id,
            UUID siteId,
            UUID storeId,
            String storeName,
            String name,
            String assetCode,
            int quantity,
            Ownership ownership,
            Condition condition,
            UUID supplierId,
            String supplierName,
            String remarks,
            UUID photoAttachmentId,
            Status status,
            Instant decidedAt,
            String decisionRemarks,
            Instant createdAt,
            /**
             * Who entered it. Carried because one act on this row belongs to that person and
             * to nobody else at the site: photographing the machine while the office has not
             * yet decided. A screen that cannot tell whose row it is would have to offer the
             * camera on every row and let the server refuse most of them.
             */
            UUID createdBy,
            Long version) {
    }

    /**
     * Entering a machine that is standing at the site.
     *
     * <p>{@code id} is the client's, like every other document here: a phone that re-sends
     * the same entry three times must not put three mixers on the register.</p>
     *
     * <p>There is no status on the way in. Whether the entry is accepted is not the field's
     * to say, and a request that could name its own status would make the whole workflow
     * advisory.</p>
     */
    public record CreateEquipmentRequest(
            @NotNull UUID id,
            @NotNull UUID storeId,
            @NotBlank @Size(max = 150) String name,
            @Size(max = 60) String assetCode,
            // A site with more than a hundred of one thing has a stock problem, not a plant
            // register; the ceiling is there to catch a mistyped serial number.
            @Positive @Max(999) Integer quantity,
            @NotNull Ownership ownership,
            @NotNull Condition condition,
            UUID supplierId,
            @Size(max = 1000) String remarks) {
    }

    /**
     * The office correcting an entry. Everything except which site it is at, which would make
     * it a different machine's row — a machine that moved is entered at the site it moved to.
     */
    public record UpdateEquipmentRequest(
            @NotNull UUID storeId,
            @NotBlank @Size(max = 150) String name,
            @Size(max = 60) String assetCode,
            @Positive @Max(999) Integer quantity,
            @NotNull Ownership ownership,
            @NotNull Condition condition,
            UUID supplierId,
            @Size(max = 1000) String remarks,
            @NotNull Long version) {
    }

    /**
     * The photograph of the machine, put on or taken off.
     *
     * <p>Its own request rather than a field on the other two, and deliberately so. The
     * picture arrives on a different day from the entry — the mixer is written down at the
     * gate in the rain and photographed on Thursday — and folding it into the correction
     * request would mean the office clearing a photograph every time it fixed a spelling,
     * because that request replaces every field it carries.</p>
     *
     * <p>A null {@code attachmentId} removes the picture, which is the same act as replacing
     * it and needs no second endpoint.</p>
     */
    public record SetEquipmentPhotoRequest(UUID attachmentId) {
    }

    /** Accepting the machine onto the register, or saying it is not there. */
    public record DecideEquipmentRequest(
            @NotNull Action action,
            @Size(max = 500) String remarks) {

        public enum Action {
            ACCEPT,
            REJECT
        }
    }
}

package in.nirman.modules.billing.api.dto;

import in.nirman.modules.billing.domain.AgreementDocument;
import in.nirman.modules.billing.domain.ReferenceDocument;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for the reference document vault. */
public final class VaultDtos {

    private VaultDtos() {
    }

    public record DocumentRequest(
            @NotNull ReferenceDocument.Kind kind,
            @NotBlank @Size(max = 60) String code,
            @NotBlank @Size(max = 300) String title,
            Integer editionYear,
            @Size(max = 120) String station,
            /** Cost index only. Everything else must leave it null. */
            BigDecimal indexPercent,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            /** Null while the edition is registered but the copy has not been found yet. */
            UUID attachmentId,
            @Size(max = 2000) String notes) {
    }

    public record DocumentResponse(
            UUID id,
            ReferenceDocument.Kind kind,
            String code,
            String title,
            Integer editionYear,
            String station,
            BigDecimal indexPercent,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            UUID attachmentId,
            UUID supersedesId,
            ReferenceDocument.Status status,
            String notes,
            Long version) {
    }

    public record SupersedeRequest(@NotNull UUID replacedBy) {
    }

    public record AttachRequest(@NotNull UUID attachmentId) {
    }

    /** One edition a tender is priced under, and what it does for it. */
    public record TenderDocumentRequest(
            @NotNull UUID documentId,
            @NotNull AgreementDocument.Role role,
            @Size(max = 40) String workPart,
            @Size(max = 2000) String notes) {
    }

    public record TenderDocumentResponse(
            UUID id,
            UUID documentId,
            AgreementDocument.Role role,
            String workPart,
            String code,
            String title,
            Integer editionYear,
            ReferenceDocument.Status status,
            UUID attachmentId) {
    }

    /**
     * What the notice said, offered to the agreement form so the office confirms rather than
     * transcribes.
     *
     * @param suggestedDocuments editions already on the shelf whose year matches the notice.
     *                           Empty is a real answer — it means somebody has to add the
     *                           edition before it can be cited.
     */
    public record AgreementSuggestion(
            Integer civilDsrYear,
            BigDecimal civilCostIndexPercent,
            Integer electricalDsrYear,
            BigDecimal electricalCostIndexPercent,
            List<DocumentResponse> suggestedDocuments) {
    }
}

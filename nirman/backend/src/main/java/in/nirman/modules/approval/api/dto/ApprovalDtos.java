package in.nirman.modules.approval.api.dto;

import in.nirman.modules.approval.domain.Approval;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Request and response shapes for the generic approval engine. */
public final class ApprovalDtos {

    private ApprovalDtos() {
    }

    /**
     * {@code RETURN} is not a softer rejection. Rejected is a decision — the money is not
     * being spent; returned means "this is probably fine but the bill number is missing",
     * and the record goes back to its author editable. Collapsing the two makes every
     * correction look like a refusal in the history.
     */
    public record ActionRequest(
            @NotNull Action action,
            @Size(max = 2000) String remarks) {

        public enum Action {
            APPROVE, REJECT, RETURN;

            public Approval.Status toStatus() {
                return switch (this) {
                    case APPROVE -> Approval.Status.APPROVED;
                    case REJECT -> Approval.Status.REJECTED;
                    case RETURN -> Approval.Status.RETURNED;
                };
            }
        }
    }

    public record ApprovalResponse(
            UUID id,
            String entityType,
            UUID entityId,
            UUID siteId,
            int level,
            String assignedRole,
            Approval.Status status,
            UUID actionBy,
            Instant actionAt,
            String remarks,
            String previousStatus,
            String nextStatus,
            Instant createdAt) {
    }

    public record ApprovalRuleResponse(
            UUID id,
            String entityType,
            int level,
            String roleCode,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            boolean active) {
    }
}

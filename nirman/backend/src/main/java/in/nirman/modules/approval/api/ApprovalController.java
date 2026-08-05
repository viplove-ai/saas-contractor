package in.nirman.modules.approval.api;

import in.nirman.modules.approval.api.dto.ApprovalDtos.ActionRequest;
import in.nirman.modules.approval.api.dto.ApprovalDtos.ApprovalResponse;
import in.nirman.modules.approval.api.dto.ApprovalDtos.ApprovalRuleResponse;
import in.nirman.modules.approval.repository.ApprovalRuleRepository;
import in.nirman.modules.approval.service.ApprovalService;
import in.nirman.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * One queue for everything waiting on the caller, whatever module raised it.
 *
 * <p>That is the point of a generic engine: an engineer opening his morning does not want
 * an expenses queue and a settlements queue and a corrections queue, he wants the list of
 * things waiting on him. The per-module endpoints — {@code /expenses/{id}/approve} and its
 * siblings — still exist because a decision usually gets made while looking at the record,
 * and they route to exactly the same service.</p>
 */
@RestController
@RequestMapping("/api/v1/approvals")
@Tag(name = "Approvals", description = "The one queue, and the decisions taken from it")
public class ApprovalController {

    private final ApprovalService approvals;
    private final ApprovalRuleRepository rules;
    private final CurrentUserProvider currentUser;

    public ApprovalController(ApprovalService approvals, ApprovalRuleRepository rules,
                              CurrentUserProvider currentUser) {
        this.approvals = approvals;
        this.rules = rules;
        this.currentUser = currentUser;
    }

    @GetMapping("/pending")
    @Operation(summary = "Everything waiting on the caller's roles, narrowed to their sites")
    public List<ApprovalResponse> pending(@RequestParam(required = false) String entityType,
                                          @RequestParam(required = false) UUID siteId) {
        return approvals.pendingForMe(entityType, siteId);
    }

    @PostMapping("/{id}/action")
    @Operation(summary = "Approve, reject or return. Only the role the level is assigned to may act.")
    public ApprovalResponse act(@PathVariable UUID id, @Valid @RequestBody ActionRequest request) {
        return ApprovalService.toResponse(
                approvals.act(id, request.action().toStatus(), request.remarks()));
    }

    @GetMapping("/history")
    @Operation(summary = "Every level a record passed through, decided or not")
    public List<ApprovalResponse> history(@RequestParam String entityType,
                                          @RequestParam UUID entityId) {
        return approvals.history(entityType, entityId);
    }

    @GetMapping("/rules")
    @Operation(summary = "The configured levels and thresholds, so a screen can explain who is next")
    public List<ApprovalRuleResponse> rules() {
        return rules.findByOrgIdOrderByEntityTypeAscLevelAsc(currentUser.currentOrgId()).stream()
                .map(rule -> new ApprovalRuleResponse(rule.getId(), rule.getEntityType(),
                        rule.getLevel(), rule.getRoleCode(), rule.getMinAmount(),
                        rule.getMaxAmount(), rule.isActive()))
                .toList();
    }
}

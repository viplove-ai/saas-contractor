package in.nirman.modules.approval.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.approval.api.dto.ApprovalDtos.ApprovalResponse;
import in.nirman.modules.approval.domain.Approval;
import in.nirman.modules.approval.domain.ApprovalRule;
import in.nirman.modules.approval.repository.ApprovalRepository;
import in.nirman.modules.approval.repository.ApprovalRuleRepository;
import in.nirman.modules.audit.AuditService;
import in.nirman.security.CurrentUserProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link ApprovalEngine}, and the only thing in the codebase that decides an approval.
 *
 * <p>Three properties it is responsible for.</p>
 *
 * <p><b>Silence is not consent.</b> An organisation with no rules configured gets one level
 * assigned to the administrator, not an automatic pass. The failure mode of an empty
 * settings table has to be "somebody has to look at this", never "nobody did".</p>
 *
 * <p><b>The queue belongs to the job.</b> A level is assigned to a role, and whoever holds
 * that role picks it up. Assigning to a person means an engineer on leave takes his site's
 * approvals with him.</p>
 *
 * <p><b>The decision and the record move together.</b> The event fires inside the deciding
 * transaction, so the approval row and the business module's status column commit as one.
 * Anything looser leaves a window where the two disagree, and a crash in that window makes
 * the disagreement permanent.</p>
 */
@Service
@Transactional
public class ApprovalService implements ApprovalEngine {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    /** Where an unconfigured organisation's approvals go. Never nowhere. */
    private static final String FALLBACK_ROLE = "ADMIN";

    private final ApprovalRepository approvals;
    private final ApprovalRuleRepository rules;
    private final ApplicationEventPublisher events;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public ApprovalService(ApprovalRepository approvals, ApprovalRuleRepository rules,
                           ApplicationEventPublisher events, CurrentUserProvider currentUser,
                           AuditService audit) {
        this.approvals = approvals;
        this.rules = rules;
        this.events = events;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @Override
    public Chain submit(Request request) {
        List<ApprovalRule> applicable = applicableRules(request.entityType(), request.amount());
        cancelChain(request.entityType(), request.entityId(), "resubmitted");

        ApprovalRule first = applicable.getFirst();
        Approval raised = approvals.save(new Approval(orgId(), request.entityType(),
                request.entityId(), request.siteId(), first.getLevel(), first.getRoleCode(),
                request.amount(), request.currentStatus()));

        audit.record(request.entityType(), request.entityId(), "APPROVAL_RAISED", null,
                Map.of("level", first.getLevel(), "assignedRole", first.getRoleCode(),
                        "levels", applicable.size()), null);
        return new Chain(raised.getLevel(), raised.getAssignedRole(), applicable.size());
    }

    @Override
    public Approval act(UUID approvalId, Approval.Status outcome, String remarks) {
        Approval approval = approvals.findByIdAndOrgId(approvalId, orgId())
                .orElseThrow(() -> BusinessException.notFound("Approval", approvalId));
        if (!approval.isPending()) {
            throw new BusinessException("approval.already-decided",
                    "This approval was already " + approval.getStatus().name().toLowerCase()
                            + ".");
        }
        assertHoldsRole(approval.getAssignedRole());

        List<ApprovalRule> chain = applicableRulesForOpenChain(approval);
        ApprovalRule next = nextAfter(chain, approval.getLevel());
        boolean finalLevel = outcome != Approval.Status.APPROVED || next == null;

        approval.decide(outcome, currentUser.currentUserIdOrNull(), Instant.now(), remarks,
                finalLevel ? outcome.name() : "LEVEL_" + next.getLevel());

        if (outcome == Approval.Status.APPROVED && next != null) {
            approvals.save(new Approval(orgId(), approval.getEntityType(), approval.getEntityId(),
                    approval.getSiteId(), next.getLevel(), next.getRoleCode(),
                    approval.getEntityAmount(), approval.getStatus().name()));
        }

        // Inside this transaction on purpose — see ApprovalDecided.
        events.publishEvent(new ApprovalDecided(approval.getEntityType(), approval.getEntityId(),
                approval.getLevel(), outcome, finalLevel, approval.getActionBy(), remarks));

        audit.record(approval.getEntityType(), approval.getEntityId(), "APPROVAL_" + outcome.name(),
                null, Map.of("level", approval.getLevel(), "finalLevel", finalLevel), remarks);
        return approval;
    }

    @Override
    @Transactional(readOnly = true)
    public Approval requirePending(String entityType, UUID entityId) {
        return approvals.findPending(entityType, entityId)
                .orElseThrow(() -> new BusinessException("approval.nothing-pending",
                        "Nothing is waiting for a decision on this record."));
    }

    @Override
    @Transactional(readOnly = true)
    public Approval findPendingOrNull(String entityType, UUID entityId) {
        return approvals.findPending(entityType, entityId).orElse(null);
    }

    @Override
    public void cancelChain(String entityType, UUID entityId, String reason) {
        approvals.findPending(entityType, entityId)
                .ifPresent(pending -> pending.cancel(Instant.now(), reason));
    }

    // ------------------------------------------------------------------ reads

    /** What is waiting on the caller right now, narrowed to their sites. */
    @Transactional(readOnly = true)
    public List<ApprovalResponse> pendingForMe(String entityType, UUID siteId) {
        Set<String> roles = currentUser.roles();
        if (roles.isEmpty()) {
            return List.of();
        }
        boolean restricted = !currentUser.seesAllSites();
        Collection<UUID> visible = restricted ? currentUser.assignedSiteIds() : List.of();
        if (restricted && visible.isEmpty()) {
            // Posted nowhere: only the records that belong to no site are theirs to see.
            visible = List.of(new UUID(0, 0));
        }
        return approvals.findPendingFor(orgId(), roles, entityType, siteId, restricted, visible)
                .stream().map(ApprovalService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<ApprovalResponse> history(String entityType, UUID entityId) {
        return approvals
                .findByEntityTypeAndEntityIdOrderByLevelAscCreatedAtAsc(entityType, entityId)
                .stream().map(ApprovalService::toResponse).toList();
    }

    // ------------------------------------------------------------------ internals

    /**
     * The levels a record of this size has to pass, in order.
     *
     * <p>Falls back to a single administrator level when nothing is configured. That is the
     * whole reason this returns a non-empty list rather than an empty one: every caller
     * would otherwise need its own answer to "and what if there are no rules", and one of
     * them would eventually answer "then it is approved".</p>
     */
    private List<ApprovalRule> applicableRules(String entityType, BigDecimal amount) {
        List<ApprovalRule> matched = rules
                .findByOrgIdAndEntityTypeAndActiveTrueOrderByLevelAsc(orgId(), entityType).stream()
                .filter(rule -> rule.appliesTo(amount))
                .sorted(Comparator.comparingInt(ApprovalRule::getLevel))
                .toList();
        if (matched.isEmpty()) {
            log.warn("No approval rules for {} at amount {} — falling back to {}",
                    entityType, amount, FALLBACK_ROLE);
            return List.of(new ApprovalRule(orgId(), entityType, 1, FALLBACK_ROLE, null, null));
        }
        return matched;
    }

    /**
     * The levels still ahead of a record already in flight.
     *
     * <p>Routed on the amount frozen onto the chain at submission, not on anything read
     * back from the business module. That is the whole point of {@code entity_amount}: an
     * expense of ₹1,000 that only ever needed the engineer must not acquire an
     * administrator level because the engine forgot how big it was, and one of ₹60,000 must
     * not lose one because somebody raised the threshold this morning.</p>
     *
     * <p>Levels already decided are excluded, so a chain cannot re-raise a level somebody
     * has answered.</p>
     */
    private List<ApprovalRule> applicableRulesForOpenChain(Approval approval) {
        Set<Integer> alreadyDecided = approvals
                .findByEntityTypeAndEntityIdOrderByLevelAscCreatedAtAsc(approval.getEntityType(),
                        approval.getEntityId()).stream()
                .filter(row -> !row.isPending())
                .map(Approval::getLevel)
                .collect(java.util.stream.Collectors.toSet());
        return rules
                .findByOrgIdAndEntityTypeAndActiveTrueOrderByLevelAsc(orgId(),
                        approval.getEntityType()).stream()
                .filter(rule -> rule.appliesTo(approval.getEntityAmount()))
                .filter(rule -> !alreadyDecided.contains(rule.getLevel()))
                .sorted(Comparator.comparingInt(ApprovalRule::getLevel))
                .toList();
    }

    private static ApprovalRule nextAfter(List<ApprovalRule> chain, int currentLevel) {
        return chain.stream()
                .filter(rule -> rule.getLevel() > currentLevel)
                .findFirst()
                .orElse(null);
    }

    private void assertHoldsRole(String roleCode) {
        if (!currentUser.roles().contains(roleCode)) {
            throw BusinessException.forbidden(
                    "This decision is the " + roleCode.toLowerCase() + "'s to make.");
        }
    }

    public static ApprovalResponse toResponse(Approval approval) {
        return new ApprovalResponse(approval.getId(), approval.getEntityType(),
                approval.getEntityId(), approval.getSiteId(), approval.getLevel(),
                approval.getAssignedRole(), approval.getStatus(), approval.getActionBy(),
                approval.getActionAt(), approval.getRemarks(), approval.getPreviousStatus(),
                approval.getNextStatus(), approval.getCreatedAt());
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }
}

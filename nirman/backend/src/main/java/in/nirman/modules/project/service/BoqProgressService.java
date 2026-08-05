package in.nirman.modules.project.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.project.api.dto.BoqDtos.ProgressEntryResponse;
import in.nirman.modules.project.api.dto.BoqDtos.ProgressLedger;
import in.nirman.modules.project.api.dto.BoqDtos.RecordProgressRequest;
import in.nirman.modules.project.domain.BoqItem;
import in.nirman.modules.project.domain.BoqProgressEntry;
import in.nirman.modules.project.repository.BoqItemRepository;
import in.nirman.modules.project.repository.BoqProgressEntryRepository;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Measured progress against the contract — the measurement-book half of Phase 6.
 *
 * <p>Three properties this class is responsible for.</p>
 *
 * <p><b>The total is a cache and the entries are the truth.</b> {@code completed_quantity}
 * moves only through {@link BoqItem#claimProgress}, and every movement leaves a dated row
 * behind it. A disputed percentage is settled by reading the entries, not by arguing about
 * the total — the same reason the stock ledger sits under the stock balance.</p>
 *
 * <p><b>A correction is an entry, never an edit.</b> Claimed 40 cum and measured 35? The fix
 * is an entry of −5 with a reason, and both rows stay. Anything else means the running bill
 * quietly changes after somebody signed it.</p>
 *
 * <p><b>Verifying a DPR twice does not claim the work twice.</b> Idempotent on
 * {@code (dpr_id, boq_item_id)}, checked here and enforced by a unique index underneath.
 * Exactly the rule the labour module already lives by for wages.</p>
 */
@Service
@Transactional
public class BoqProgressService implements BoqProgress {

    private final BoqProgressEntryRepository entries;
    private final BoqItemRepository items;
    private final SiteAccessGuard siteAccessGuard;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public BoqProgressService(BoqProgressEntryRepository entries, BoqItemRepository items,
                              SiteAccessGuard siteAccessGuard, CurrentUserProvider currentUser,
                              AuditService audit) {
        this.entries = entries;
        this.items = items;
        this.siteAccessGuard = siteAccessGuard;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ direct recording

    /**
     * A measurement taken against a line directly, outside any DPR — a joint measurement with
     * the department, or a correction to something a DPR claimed.
     */
    @PreAuthorize("hasAuthority('boq:progress:record')")
    public ProgressEntryResponse record(UUID boqItemId, RecordProgressRequest request) {
        BoqItem item = requireLive(boqItemId);
        UUID siteId = request.siteId() != null ? request.siteId() : item.getSiteId();
        if (siteId == null) {
            throw new BusinessException("boq.progress-site-required",
                    "Item " + item.getItemNumber() + " spans the whole project, so a measurement "
                            + "against it has to name the site the work was done at.");
        }
        siteAccessGuard.assertCanAccess(siteId);
        assertClaimable(item);

        BoqProgressEntry entry = post(item, siteId, request.entryDate(), request.quantity(),
                request.remarks(), null);
        audit.record("BOQ_PROGRESS", entry.getId(), "RECORD", null,
                Map.of("boqItemId", boqItemId.toString(), "itemNumber", item.getItemNumber(),
                        "quantity", request.quantity(),
                        "completedQuantity", item.getCompletedQuantity()), request.remarks());
        return toResponse(entry, item);
    }

    /** The measurement book for one line: every dated claim, and the total they come to. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('boq:read')")
    public ProgressLedger ledger(UUID boqItemId, LocalDate from, LocalDate to) {
        BoqItem item = requireLive(boqItemId);
        List<BoqProgressEntry> found = entries.findForItem(orgId(), boqItemId, from, to);
        BigDecimal claimedInRange = found.stream().map(BoqProgressEntry::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new ProgressLedger(item.getId(), item.getItemNumber(), item.getDescription(),
                item.getUnitId(), item.getContractQuantity(), item.getCompletedQuantity(),
                percentComplete(item), item.overClaimedQuantity(), claimedInRange,
                item.getStatus(), item.getActualStartDate(), item.getActualCompletionDate(),
                found.stream().map(entry -> toResponse(entry, item)).toList());
    }

    // ------------------------------------------------------------------ BoqProgress

    @Override
    public PostResult postFromDpr(UUID dprId, UUID siteId, LocalDate entryDate,
                                  List<Claim> claims) {
        int posted = 0;
        int skipped = 0;
        List<UUID> overClaimed = new ArrayList<>();

        for (Claim claim : claims) {
            if (claim.quantity() == null || claim.quantity().signum() == 0) {
                continue;   // a work item described in words but never measured claims nothing
            }
            // The idempotency check. A second verification of the same report must leave the
            // contract claiming what was measured once, not twice.
            if (entries.existsByDprIdAndBoqItemId(dprId, claim.boqItemId())) {
                skipped++;
                continue;
            }
            BoqItem item = requireLive(claim.boqItemId());
            assertClaimable(item);
            post(item, siteId, entryDate, claim.quantity(), claim.remarks(), dprId);
            posted++;
            if (item.overClaimedQuantity().signum() > 0) {
                overClaimed.add(item.getId());
            }
        }

        if (posted > 0 || skipped > 0) {
            audit.record("BOQ_PROGRESS", dprId, "POST_FROM_DPR", null,
                    Map.of("posted", posted, "skipped", skipped,
                            "overClaimed", overClaimed.stream().map(UUID::toString).toList()), null);
        }
        return new PostResult(posted, skipped, overClaimed);
    }

    // ------------------------------------------------------------------ internals

    private BoqProgressEntry post(BoqItem item, UUID siteId, LocalDate entryDate,
                                  BigDecimal quantity, String remarks, UUID dprId) {
        try {
            item.claimProgress(quantity, entryDate);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("boq.progress-below-zero",
                    "That correction would leave %s with less than nothing built: %s is claimed "
                            .formatted(item.getItemNumber(),
                                    item.getCompletedQuantity().toPlainString())
                            + "so far.");
        }
        BoqProgressEntry entry = new BoqProgressEntry(orgId(), item.getId(), siteId, entryDate,
                quantity, dprId, remarks, currentUser.currentUserIdOrNull());
        return entries.save(entry);
    }

    /**
     * A cancelled line is not work and a placeholder is not work either. Both refuse a
     * measurement for the same reason nothing may be costed to them.
     */
    private static void assertClaimable(BoqItem item) {
        if (item.isSynthetic()) {
            throw new BusinessException("boq.synthetic",
                    "Item " + item.getItemNumber() + " is a reconciliation placeholder, not work. "
                            + "Progress cannot be claimed against it.");
        }
        if (item.getStatus() == BoqItem.Status.CANCELLED) {
            throw new BusinessException("boq.progress-cancelled",
                    "Item " + item.getItemNumber() + " is cancelled, so no work can be claimed "
                            + "against it.");
        }
    }

    private BoqItem requireLive(UUID boqItemId) {
        return items.findByIdAndOrgIdAndDeletedAtIsNull(boqItemId, orgId())
                .orElseThrow(() -> BusinessException.notFound("BOQ item", boqItemId));
    }

    /**
     * Null rather than a number when the contract quantified nothing. A line with no contract
     * quantity is not 100% complete and it is not 0% either — the question has no answer, and
     * inventing one puts a false figure on a progress bar.
     */
    private static BigDecimal percentComplete(BoqItem item) {
        if (item.getContractQuantity().signum() <= 0) {
            return null;
        }
        return item.getCompletedQuantity().multiply(BigDecimal.valueOf(100))
                .divide(item.getContractQuantity(), 2, RoundingMode.HALF_UP);
    }

    private static ProgressEntryResponse toResponse(BoqProgressEntry entry, BoqItem item) {
        return new ProgressEntryResponse(entry.getId(), entry.getBoqItemId(), item.getItemNumber(),
                entry.getSiteId(), entry.getEntryDate(), entry.getQuantity(), entry.getDprId(),
                entry.getRemarks(), entry.getRecordedBy(), entry.getCreatedAt());
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }
}

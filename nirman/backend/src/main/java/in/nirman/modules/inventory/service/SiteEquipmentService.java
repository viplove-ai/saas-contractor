package in.nirman.modules.inventory.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.attachment.service.AttachmentLookup;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.inventory.api.dto.EquipmentDtos.CreateEquipmentRequest;
import in.nirman.modules.inventory.api.dto.EquipmentDtos.DecideEquipmentRequest;
import in.nirman.modules.inventory.api.dto.EquipmentDtos.EquipmentResponse;
import in.nirman.modules.inventory.api.dto.EquipmentDtos.UpdateEquipmentRequest;
import in.nirman.modules.inventory.domain.SiteEquipment;
import in.nirman.modules.inventory.repository.SiteEquipmentRepository;
import in.nirman.modules.masterdata.service.VendorLookup;
import in.nirman.modules.project.service.SiteLookup;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The plant standing at a site: who may say it is there, and who may agree.
 *
 * <p><b>Anybody at the site enters it; only the office accepts it.</b> That split is the
 * whole feature. A supervisor who cannot record the mixer he is looking at will not record it
 * at all, and the register becomes a list of what the office remembered. But an entry nobody
 * checked is a claim — a hired breaker that went back on Tuesday would otherwise sit there as
 * plant for a year — so it is {@code PENDING} until an administrator says otherwise, and the
 * screens keep the two apart.</p>
 *
 * <p><b>An administrator's own entry arrives accepted.</b> Asking him to approve himself is a
 * ceremony with no second pair of eyes in it, and a workflow everybody learns to click
 * through is worse than no workflow.</p>
 *
 * <p><b>Nothing here touches the ledger.</b> Equipment is held, not consumed: a stock posting
 * would report the mixer as used up by the raft slab and leave the store believing there is
 * nothing to pour with. For the same reason there is no {@code PeriodLockGuard} call — a
 * closed accounting period is about what a month cost, and saying which machine is standing
 * in the yard today costs nothing.</p>
 */
@Service
@Transactional
public class SiteEquipmentService {

    private final SiteEquipmentRepository equipment;
    private final SiteLookup sites;
    private final VendorLookup vendors;
    private final AttachmentLookup attachments;
    private final SiteAccessGuard siteAccessGuard;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public SiteEquipmentService(SiteEquipmentRepository equipment, SiteLookup sites,
                                VendorLookup vendors, AttachmentLookup attachments,
                                SiteAccessGuard siteAccessGuard,
                                CurrentUserProvider currentUser, AuditService audit) {
        this.equipment = equipment;
        this.sites = sites;
        this.vendors = vendors;
        this.attachments = attachments;
        this.siteAccessGuard = siteAccessGuard;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('equipment:read')")
    public List<EquipmentResponse> list(UUID siteId, UUID storeId, SiteEquipment.Status status) {
        if (siteId != null) {
            siteAccessGuard.assertCanAccess(siteId);
        }
        if (storeId != null) {
            // Resolving the store is the access check for it: requireStore refuses a store
            // whose site the caller is not posted to.
            siteAccessGuard.assertCanAccess(sites.requireStore(storeId).siteId());
        }
        boolean restricted = !currentUser.seesAllSites();
        Collection<UUID> visible = restricted ? currentUser.assignedSiteIds() : List.of();
        if (restricted && visible.isEmpty()) {
            return List.of();
        }
        return toResponses(equipment.search(orgId(), siteId, storeId, status, restricted, visible));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('equipment:read')")
    public EquipmentResponse get(UUID id) {
        SiteEquipment machine = require(id);
        siteAccessGuard.assertCanAccess(machine.getSiteId());
        return toResponses(List.of(machine)).get(0);
    }

    /**
     * Enters a machine standing at the site.
     *
     * <p>Idempotent on the client-generated id, like every other entry here: an entry typed
     * in a yard with no signal and synced three times is one machine.</p>
     */
    @PreAuthorize("hasAuthority('equipment:create')")
    public EquipmentResponse create(CreateEquipmentRequest request) {
        SiteLookup.StoreInfo store = sites.requireStore(request.storeId());

        var replay = equipment.findByIdAndOrgId(request.id(), orgId());
        if (replay.isPresent()) {
            return toResponses(List.of(replay.get())).get(0);
        }

        String assetCode = trimToNull(request.assetCode());
        assertAssetCodeFree(assetCode, null);
        UUID supplierId = requireSupplierWhenHired(request.ownership(), request.supplierId());

        SiteEquipment machine = new SiteEquipment(request.id(), orgId(), store.siteId(),
                store.id(), request.name().trim());
        machine.setAssetCode(assetCode);
        machine.setQuantity(request.quantity() == null ? 1 : request.quantity());
        machine.setOwnership(request.ownership());
        machine.setCondition(request.condition());
        machine.setSupplierId(supplierId);
        machine.setRemarks(trimToNull(request.remarks()));

        /*
          Entered by the office is entered and accepted. The workflow exists so that somebody
          other than the person who saw the machine agrees it is there; when those are the
          same person there is nothing to wait for, and a pending row on the administrator's
          own screen only teaches him to click through the queue without reading it.
        */
        boolean acceptsItself = currentUser.hasPermission("equipment:approve");
        if (acceptsItself) {
            machine.accept(Instant.now(), currentUser.currentUserIdOrNull(), null);
        }
        equipment.save(machine);

        audit.record("SITE_EQUIPMENT", machine.getId(), "CREATE", null,
                Map.of("name", machine.getName(), "storeId", store.id().toString(),
                        "status", machine.getStatus().name()), null);
        return toResponses(List.of(machine)).get(0);
    }

    /**
     * The office accepting the machine onto the register, or saying it is not there.
     *
     * <p>A rejected row stays and carries the reason. Somebody entered it in good faith and
     * is owed an answer; a row that quietly disappears is entered again next week.</p>
     */
    @PreAuthorize("hasAuthority('equipment:approve')")
    public EquipmentResponse decide(UUID id, DecideEquipmentRequest request) {
        SiteEquipment machine = require(id);
        siteAccessGuard.assertCanAccess(machine.getSiteId());
        if (machine.getStatus() != SiteEquipment.Status.PENDING) {
            throw new BusinessException("equipment.already-decided",
                    machine.getName() + " has already been "
                            + machine.getStatus().name().toLowerCase() + ".");
        }

        Instant now = Instant.now();
        UUID by = currentUser.currentUserIdOrNull();
        if (request.action() == DecideEquipmentRequest.Action.REJECT) {
            machine.reject(now, by, trimToNull(request.remarks()));
        } else {
            machine.accept(now, by, trimToNull(request.remarks()));
        }
        audit.record("SITE_EQUIPMENT", id, machine.getStatus().name(), null,
                Map.of("name", machine.getName()), request.remarks());
        return toResponses(List.of(machine)).get(0);
    }

    /**
     * Correcting an entry — the office's, and only the office's.
     *
     * <p>Not the site's, even for the row the site typed. Once an entry is on the register
     * somebody has read it and decided about it, and an entry that can be quietly rewritten
     * afterwards makes the acceptance mean nothing.</p>
     */
    @PreAuthorize("hasAuthority('equipment:write')")
    public EquipmentResponse update(UUID id, UpdateEquipmentRequest request) {
        SiteEquipment machine = require(id);
        siteAccessGuard.assertCanAccess(machine.getSiteId());
        if (!machine.getVersion().equals(request.version())) {
            throw new OptimisticLockingFailureException(
                    "Equipment " + id + " was changed by someone else");
        }

        SiteLookup.StoreInfo store = sites.requireStore(request.storeId());
        if (!store.siteId().equals(machine.getSiteId())) {
            throw new BusinessException("equipment.store-elsewhere",
                    "That store is at another site. A machine that has moved is entered at "
                            + "the site it moved to, so the register at each site says what "
                            + "is actually standing there.");
        }
        String assetCode = trimToNull(request.assetCode());
        assertAssetCodeFree(assetCode, id);

        machine.setStoreId(store.id());
        machine.setName(request.name().trim());
        machine.setAssetCode(assetCode);
        machine.setQuantity(request.quantity() == null ? 1 : request.quantity());
        machine.setOwnership(request.ownership());
        machine.setCondition(request.condition());
        machine.setSupplierId(requireSupplierWhenHired(request.ownership(), request.supplierId()));
        machine.setRemarks(trimToNull(request.remarks()));

        audit.record("SITE_EQUIPMENT", id, "UPDATE", null,
                Map.of("name", machine.getName(), "condition", machine.getCondition().name()),
                null);
        return toResponses(List.of(machine)).get(0);
    }

    /**
     * The photograph of the machine, put on or taken off.
     *
     * <p>Its own act, and the only one on this register that both the field and the office
     * may perform. <b>The man who entered the machine may photograph it while the office has
     * not yet decided</b> — he is standing next to it, and the picture is the evidence the
     * office is short of when it reads "concrete mixer, no number on it" typed from a yard
     * forty kilometres away. The moment the entry is accepted or rejected it is the office's
     * row like every other field on it, because a decided entry that can still be changed by
     * the man who made it makes the decision mean nothing.</p>
     *
     * <p>That rule is also why the picture is not part of {@code create}: it arrives on a
     * different day from the entry. The mixer is written down at the gate in the rain and
     * photographed on Thursday, and a register that could not accept it on Thursday would be
     * one where nobody bothers on Thursday either.</p>
     *
     * <p>No version check. The photograph does not race the office's corrections — it is one
     * field written by one person, and the alternative is a supervisor whose picture is
     * refused because somebody in the office fixed a spelling while he was in the yard.</p>
     */
    @PreAuthorize("hasAnyAuthority('equipment:create', 'equipment:write')")
    public EquipmentResponse setPhoto(UUID id, UUID attachmentId) {
        SiteEquipment machine = require(id);
        siteAccessGuard.assertCanAccess(machine.getSiteId());
        assertMayPhotograph(machine);

        if (attachmentId == null) {
            machine.setPhotoAttachmentId(null);
        } else {
            AttachmentLookup.FileInfo file = attachments.require(attachmentId);
            if (!file.image()) {
                throw new BusinessException("equipment.photo-not-an-image",
                        "A machine is identified by a picture of it. " + file.fileName()
                                + " is not one.");
            }
            // Claimed, so that nothing can delete the file out from under the register: an
            // unclaimed upload is a draft anybody who made it may still discard.
            attachments.claimFor(attachmentId, machine.getId());
            machine.setPhotoAttachmentId(attachmentId);
        }

        audit.record("SITE_EQUIPMENT", id, attachmentId == null ? "PHOTO_REMOVE" : "PHOTO", null,
                Map.of("name", machine.getName(),
                        "photoAttachmentId", String.valueOf(attachmentId)), null);
        return toResponses(List.of(machine)).get(0);
    }

    /**
     * Who may put the picture on this row.
     *
     * <p>The office, always. The man who entered it, until somebody has decided about it —
     * after which changing what the entry shows is a correction, and corrections are
     * {@code equipment:write} by the argument on {@link #update}.</p>
     */
    private void assertMayPhotograph(SiteEquipment machine) {
        if (currentUser.hasPermission("equipment:write")) {
            return;
        }
        if (machine.getStatus() != SiteEquipment.Status.PENDING) {
            throw BusinessException.forbidden(
                    "The office has already decided about this entry, so changing its "
                            + "photograph is theirs now. Ask them, or enter the machine again "
                            + "if it is a different one.");
        }
        UUID me = currentUser.currentUserIdOrNull();
        if (me == null || !me.equals(machine.getCreatedBy())) {
            throw BusinessException.forbidden(
                    "This entry is somebody else's. You can photograph the machines you "
                            + "entered yourself while the office has not decided about them.");
        }
    }

    /**
     * Takes a machine off the register. Soft, like every other register here: a row deleted
     * in June must not take its own history out of March with it.
     */
    @PreAuthorize("hasAuthority('equipment:write')")
    public void delete(UUID id) {
        SiteEquipment machine = require(id);
        siteAccessGuard.assertCanAccess(machine.getSiteId());
        machine.delete(Instant.now(), currentUser.currentUserIdOrNull());
        audit.record("SITE_EQUIPMENT", id, "DELETE", null,
                Map.of("name", machine.getName()), null);
    }

    // ------------------------------------------------------------------ internals

    /**
     * A hired machine with nobody to pay is half a record. The whole reason ownership is on
     * the row is that hired plant costs money every day it stands there, and the question
     * that follows is always "whose".
     */
    private UUID requireSupplierWhenHired(SiteEquipment.Ownership ownership, UUID supplierId) {
        if (ownership != SiteEquipment.Ownership.HIRED) {
            // An owned machine has no supplier, and one carried over from a corrected entry
            // would be read as a hire charge somebody owes.
            return null;
        }
        if (supplierId == null) {
            throw new BusinessException("equipment.hired-needs-supplier",
                    "A hired machine costs money every day it stands here. Say who it is "
                            + "hired from.");
        }
        // Resolving the name is the check that the supplier exists in this organisation.
        vendors.requireName(supplierId);
        return supplierId;
    }

    /** Two rows carrying one registration are two machines that are one machine. */
    private void assertAssetCodeFree(String assetCode, UUID excludeId) {
        if (assetCode == null) {
            return;
        }
        List<SiteEquipment> clashes = equipment.findByAssetCode(orgId(), assetCode, excludeId);
        if (!clashes.isEmpty()) {
            throw BusinessException.conflict("equipment.code-taken",
                    "Number " + assetCode + " is already on the register as "
                            + clashes.get(0).getName() + ".");
        }
    }

    /**
     * Names resolved in one batch rather than per row — the store's and the supplier's are
     * the two things a register of forty machines would otherwise ask eighty questions for.
     */
    private List<EquipmentResponse> toResponses(List<SiteEquipment> machines) {
        Set<UUID> supplierIds = machines.stream().map(SiteEquipment::getSupplierId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> supplierNames = vendors.names(supplierIds);

        Set<UUID> siteIds = machines.stream().map(SiteEquipment::getSiteId)
                .collect(Collectors.toCollection(HashSet::new));
        Map<UUID, String> storeNames = sites.storesAtSites(siteIds).stream()
                .collect(Collectors.toMap(SiteLookup.StoreInfo::id, SiteLookup.StoreInfo::name,
                        (first, second) -> first));

        return machines.stream()
                .map(machine -> new EquipmentResponse(machine.getId(), machine.getSiteId(),
                        machine.getStoreId(), storeNames.get(machine.getStoreId()),
                        machine.getName(), machine.getAssetCode(), machine.getQuantity(),
                        machine.getOwnership(), machine.getCondition(), machine.getSupplierId(),
                        // Guarded rather than looked up blind: an owned machine has no
                        // supplier, and Map.of() answers a null key with a NullPointerException.
                        machine.getSupplierId() == null
                                ? null : supplierNames.get(machine.getSupplierId()),
                        machine.getRemarks(), machine.getPhotoAttachmentId(),
                        machine.getStatus(), machine.getDecidedAt(), machine.getDecisionRemarks(),
                        machine.getCreatedAt(), machine.getCreatedBy(), machine.getVersion()))
                .toList();
    }

    private SiteEquipment require(UUID id) {
        return equipment.findByIdAndOrgIdAndDeletedAtIsNull(id, orgId())
                .orElseThrow(() -> BusinessException.notFound("Equipment", id));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }
}

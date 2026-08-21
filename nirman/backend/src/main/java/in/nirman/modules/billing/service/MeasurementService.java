package in.nirman.modules.billing.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.billing.api.dto.BillingDtos.CreateSheetRequest;
import in.nirman.modules.billing.api.dto.BillingDtos.MeasurementLineInput;
import in.nirman.modules.billing.api.dto.BillingDtos.MeasurementLineResponse;
import in.nirman.modules.billing.api.dto.BillingDtos.SheetResponse;
import in.nirman.modules.billing.api.dto.BillingDtos.UpdateSheetRequest;
import in.nirman.modules.billing.domain.MeasurementLine;
import in.nirman.modules.billing.domain.MeasurementSheet;
import in.nirman.modules.billing.repository.MeasurementLineRepository;
import in.nirman.modules.billing.repository.MeasurementSheetRepository;
import in.nirman.modules.project.service.BoqLookup;
import in.nirman.modules.project.service.SiteLookup;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Recording and signing measurement sheets.
 *
 * <p>Three rules this class exists to keep.</p>
 *
 * <p><b>A sheet is signed only when its two totals agree.</b> The engineer worked out a total
 * by hand at the foot of the paper; the system multiplies the rows and arrives at one of its
 * own. Signing while they differ is refused. It costs one number and catches a typing slip, a
 * skipped row, a transposed 5.8 for 8.5, and his own arithmetic — and it needs no cleverness,
 * only subtraction. A draft may disagree, because that is what a draft is for.</p>
 *
 * <p><b>A signed sheet does not change, and a billed one cannot.</b> Corrections after
 * signature are a fresh sheet with negative rows, dated when the error was found. That is the
 * discipline the stock ledger, the wage ledger and the progress ledger already keep, and the
 * reason is the same: a figure somebody signed must not move underneath them.</p>
 *
 * <p><b>The pre-printed serial is a duplicate guard.</b> One serial, one sheet. Entering the
 * same page twice — two people each thinking the other had not — is the error that puts a
 * quantity into a bill twice, and it is answered with the row that already exists.</p>
 */
@Service
@Transactional
public class MeasurementService {

    private static final String ENTITY_TYPE = "MEASUREMENT_SHEET";

    private final MeasurementSheetRepository sheets;
    private final MeasurementLineRepository lines;
    private final BoqLookup boqItems;
    private final SiteLookup sites;
    private final SiteAccessGuard siteAccessGuard;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public MeasurementService(MeasurementSheetRepository sheets, MeasurementLineRepository lines,
                              BoqLookup boqItems, SiteLookup sites,
                              SiteAccessGuard siteAccessGuard, CurrentUserProvider currentUser,
                              AuditService audit) {
        this.sheets = sheets;
        this.lines = lines;
        this.boqItems = boqItems;
        this.sites = sites;
        this.siteAccessGuard = siteAccessGuard;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ writing

    @PreAuthorize("hasAuthority('billing:measure')")
    public SheetResponse create(CreateSheetRequest request) {
        UUID orgId = currentUser.currentOrgId();
        BoqLookup.BoqItemInfo item = boqItems.requireChargeable(request.boqItemId());
        if (!item.projectId().equals(request.projectId())) {
            throw new BusinessException("billing.item-not-on-project",
                    "Item " + item.itemNumber() + " does not belong to the project this sheet "
                            + "names, so a measurement against it would be filed under the wrong "
                            + "contract.");
        }
        UUID siteId = resolveSite(request.projectId(), request.siteId(), item.siteId());
        siteAccessGuard.assertCanAccess(siteId);

        if (request.sheetSerial() != null && !request.sheetSerial().isBlank()) {
            sheets.findByOrgIdAndSheetSerialAndDeletedAtIsNull(orgId, request.sheetSerial().trim())
                    .ifPresent(existing -> {
                        throw BusinessException.conflict("billing.sheet-already-entered",
                                "Sheet " + existing.getSheetSerial() + " was already entered on "
                                        + existing.getMeasuredOn() + ". Open that one rather than "
                                        + "entering the page a second time.");
                    });
        }

        MeasurementSheet sheet = new MeasurementSheet(orgId, request.projectId(), siteId,
                request.boqItemId(), request.sheetType(), request.measuredOn(),
                currentUser.currentUserIdOrNull());
        sheet.setSheetSerial(trimToNull(request.sheetSerial()));
        sheet.setLocationNote(request.locationNote());
        sheet.setWrittenTotal(request.writtenTotal());
        sheet.setUnitWeight(request.unitWeight());
        sheet.setAttachmentId(request.attachmentId());
        sheet.setRemarks(request.remarks());
        sheets.save(sheet);

        List<MeasurementLine> saved = replaceLines(sheet, request.lines());
        audit.record(ENTITY_TYPE, sheet.getId(), "CREATE", null,
                Map.of("itemNumber", item.itemNumber(), "lines", saved.size(),
                        "computedTotal", sheet.getComputedTotal()), null);
        return toResponse(sheet, saved, item);
    }

    @PreAuthorize("hasAuthority('billing:measure')")
    public SheetResponse update(UUID id, UpdateSheetRequest request) {
        MeasurementSheet sheet = requireLive(id);
        siteAccessGuard.assertCanAccess(sheet.getSiteId());
        assertOpen(sheet);
        if (!sheet.getVersion().equals(request.version())) {
            throw BusinessException.conflict("billing.sheet-stale",
                    "This sheet was changed by somebody else while it was open. Reload it and "
                            + "make the change again.");
        }

        sheet.setSheetSerial(trimToNull(request.sheetSerial()));
        sheet.setMeasuredOn(request.measuredOn());
        sheet.setLocationNote(request.locationNote());
        sheet.setWrittenTotal(request.writtenTotal());
        sheet.setUnitWeight(request.unitWeight());
        sheet.setAttachmentId(request.attachmentId());
        sheet.setRemarks(request.remarks());

        List<MeasurementLine> saved = replaceLines(sheet, request.lines());
        BoqLookup.BoqItemInfo item = boqItems.requireChargeable(sheet.getBoqItemId());
        audit.record(ENTITY_TYPE, sheet.getId(), "UPDATE", null,
                Map.of("lines", saved.size(), "computedTotal", sheet.getComputedTotal()), null);
        return toResponse(sheet, saved, item);
    }

    /**
     * The signature, and the only place the checksum is enforced in code.
     *
     * <p>{@code ck_sheet_signed_agrees} says the same thing in the schema, and both are
     * wanted: the constraint is what makes a bad row impossible, and this is what tells the
     * engineer which of his rows to look at.</p>
     */
    @PreAuthorize("hasAuthority('billing:measure')")
    public SheetResponse sign(UUID id) {
        MeasurementSheet sheet = requireLive(id);
        siteAccessGuard.assertCanAccess(sheet.getSiteId());
        assertOpen(sheet);

        List<MeasurementLine> sheetLines = lines.findBySheetIdOrderByLineNo(id);
        if (sheetLines.isEmpty()) {
            throw new BusinessException("billing.sheet-empty",
                    "A sheet with no measurements on it claims nothing. Add the rows, or delete "
                            + "the sheet.");
        }
        if (!sheet.totalsAgree()) {
            BigDecimal difference = sheet.getWrittenTotal().subtract(sheet.getComputedTotal());
            throw new BusinessException("billing.totals-disagree",
                    "The total written on the sheet is " + sheet.getWrittenTotal()
                            + " but the rows come to " + sheet.getComputedTotal()
                            + " — a difference of " + difference.abs() + ". One of the rows is "
                            + "wrong, or the total is. Check before signing.");
        }

        sheet.sign(Instant.now(), currentUser.currentUserIdOrNull());
        BoqLookup.BoqItemInfo item = boqItems.requireChargeable(sheet.getBoqItemId());
        audit.record(ENTITY_TYPE, sheet.getId(), "SIGN", null,
                Map.of("itemNumber", item.itemNumber(), "quantity", sheet.claimedQuantity()), null);
        return toResponse(sheet, sheetLines, item);
    }

    @PreAuthorize("hasAuthority('billing:measure')")
    public void delete(UUID id) {
        MeasurementSheet sheet = requireLive(id);
        siteAccessGuard.assertCanAccess(sheet.getSiteId());
        assertOpen(sheet);
        sheet.markDeleted(Instant.now());
        audit.record(ENTITY_TYPE, sheet.getId(), "DELETE", null, Map.of(), null);
    }

    // ------------------------------------------------------------------ reading

    @PreAuthorize("hasAuthority('billing:read')")
    @Transactional(readOnly = true)
    public SheetResponse get(UUID id) {
        MeasurementSheet sheet = requireLive(id);
        siteAccessGuard.assertCanAccess(sheet.getSiteId());
        return toResponse(sheet, lines.findBySheetIdOrderByLineNo(id),
                boqItems.requireChargeable(sheet.getBoqItemId()));
    }

    @PreAuthorize("hasAuthority('billing:read')")
    @Transactional(readOnly = true)
    public List<SheetResponse> list(UUID projectId, UUID boqItemId, Boolean billed) {
        List<MeasurementSheet> found = sheets.findForProject(currentUser.currentOrgId(),
                projectId, boqItemId == null, boqItemId == null ? projectId : boqItemId,
                billed == null, billed != null && billed);
        List<SheetResponse> out = new ArrayList<>(found.size());
        for (MeasurementSheet sheet : found) {
            if (!siteAccessGuard.canAccess(sheet.getSiteId())) {
                continue;   // a register is narrowed to what the reader may see, not refused
            }
            out.add(toResponse(sheet, lines.findBySheetIdOrderByLineNo(sheet.getId()),
                    boqItems.requireChargeable(sheet.getBoqItemId())));
        }
        return out;
    }

    // ------------------------------------------------------------------ internals

    /**
     * Which site a measurement belongs to.
     *
     * <p>A full project's caller names one. A BILLING_ONLY project has exactly one — created
     * for it because authorisation is site-scoped — so the caller never had to choose and this
     * finds it. More than one and no choice made is an error rather than a guess: filing a
     * measurement under the wrong site is not something to be silently decided.</p>
     */
    private UUID resolveSite(UUID projectId, UUID requested, UUID itemSiteId) {
        if (requested != null) {
            return requested;
        }
        if (itemSiteId != null) {
            return itemSiteId;
        }
        List<SiteLookup.SiteInfo> projectSites = sites.forProject(projectId);
        if (projectSites.size() == 1) {
            return projectSites.get(0).id();
        }
        if (projectSites.isEmpty()) {
            throw new BusinessException("billing.project-has-no-site",
                    "This project has no site to record a measurement against. A project "
                            + "imported for billing is given one; this one has none, which "
                            + "needs an administrator.");
        }
        throw new BusinessException("billing.site-required",
                "This project runs " + projectSites.size() + " sites, so a measurement has to "
                        + "say which one the work was done at.");
    }

    /**
     * Rows are replaced wholesale rather than merged. A sheet is a page: the engineer is
     * copying it as a whole, and a partial update would leave rows on the screen that are not
     * on the paper.
     */
    private List<MeasurementLine> replaceLines(MeasurementSheet sheet, List<MeasurementLineInput> inputs) {
        lines.deleteBySheetId(sheet.getId());
        lines.flush();
        List<MeasurementLine> built = new ArrayList<>();
        int lineNo = 1;
        if (inputs != null) {
            for (MeasurementLineInput input : inputs) {
                built.add(new MeasurementLine(sheet.getId(), lineNo++, input.location(),
                        input.nos(), input.mult(), input.length(), input.breadth(),
                        input.height(), input.deduction(), input.barDia()));
            }
        }
        List<MeasurementLine> saved = lines.saveAll(built);
        sheet.setComputedTotal(saved.stream()
                .map(MeasurementLine::getContents)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, RoundingMode.HALF_UP));
        return saved;
    }

    private MeasurementSheet requireLive(UUID id) {
        return sheets.findByIdAndOrgIdAndDeletedAtIsNull(id, currentUser.currentOrgId())
                .orElseThrow(() -> BusinessException.notFound("Measurement sheet", id));
    }

    private void assertOpen(MeasurementSheet sheet) {
        if (sheet.isBilled()) {
            throw new BusinessException("billing.sheet-billed",
                    "This sheet has already been paid on a bill. A correction is a new sheet "
                            + "with negative rows, dated when the error was found — not a change "
                            + "to what somebody signed.");
        }
        if (sheet.getStatus() == MeasurementSheet.Status.SIGNED) {
            throw new BusinessException("billing.sheet-signed",
                    "This sheet has been signed. A correction is a new sheet with negative "
                            + "rows, not a change to a signed one.");
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private SheetResponse toResponse(MeasurementSheet sheet, List<MeasurementLine> sheetLines,
                                     BoqLookup.BoqItemInfo item) {
        List<MeasurementLineResponse> lineResponses = sheetLines.stream()
                .map(l -> new MeasurementLineResponse(l.getId(), l.getLineNo(), l.getLocation(),
                        l.getNos(), l.getMult(), l.getLength(), l.getBreadth(), l.getHeight(),
                        l.getContents(), l.isDeduction(), l.getBarDia()))
                .toList();
        return new SheetResponse(sheet.getId(), sheet.getProjectId(), sheet.getSiteId(),
                sheet.getBoqItemId(), item.itemNumber(), item.description(),
                sheet.getSheetSerial(), sheet.getSheetType(), sheet.getMeasuredOn(),
                sheet.getMeasuredBy(), sheet.getLocationNote(), sheet.getWrittenTotal(),
                sheet.getComputedTotal(), sheet.claimedQuantity(), sheet.totalsAgree(),
                sheet.getUnitWeight(), sheet.getStatus(), sheet.getSignedAt(),
                sheet.getSignedBy(), sheet.getRaBillId(), sheet.getAttachmentId(),
                sheet.getRemarks(), sheet.getVersion(), lineResponses);
    }
}

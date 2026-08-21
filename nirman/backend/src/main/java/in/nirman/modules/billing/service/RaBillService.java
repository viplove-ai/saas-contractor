package in.nirman.modules.billing.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.billing.api.dto.BillingDtos.BillItemResponse;
import in.nirman.modules.billing.api.dto.BillingDtos.BillResponse;
import in.nirman.modules.billing.api.dto.BillingDtos.BillSummary;
import in.nirman.modules.billing.api.dto.BillingDtos.CreateBillRequest;
import in.nirman.modules.billing.api.dto.BillingDtos.DecideBillRequest;
import in.nirman.modules.billing.api.dto.BillingDtos.UnbilledItem;
import in.nirman.modules.billing.api.dto.BillingDtos.UnbilledSummary;
import in.nirman.modules.billing.domain.MeasurementSheet;
import in.nirman.modules.billing.domain.RaBill;
import in.nirman.modules.billing.domain.RaBillItem;
import in.nirman.modules.billing.repository.AgreementRepository;
import in.nirman.modules.billing.repository.MeasurementSheetRepository;
import in.nirman.modules.billing.repository.RaBillItemRepository;
import in.nirman.modules.billing.repository.RaBillRepository;
import in.nirman.modules.project.service.BoqLookup;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The running account bill: sweeping measured work into a claim, and freezing what was paid.
 *
 * <p><b>What a bill is.</b> Every signed measurement sheet measured on or before the cutoff
 * date that no earlier bill has claimed. "Since previous bill" is therefore this bill's own
 * sheets and "up to date" is those plus every earlier bill's — both derived on read, which is
 * why nobody types a hundred and fifteen previous-bill figures off the last printout ever
 * again.</p>
 *
 * <p><b>Why a sheet carries the bill id and not the other way round.</b> The column can hold
 * one value, so a quantity cannot be paid twice. That is the expensive error in a billing
 * system and it is closed by the schema rather than by a rule somebody has to keep
 * remembering.</p>
 *
 * <p><b>Why passing writes a snapshot.</b> {@code ra_bill_items} is written once, at that
 * moment, and read for ever after. Open the 2nd RA bill next year and it shows what was
 * actually paid, not what today's measurements would now produce. A correction found later is
 * a fresh sheet with negative rows in the next bill — never an edit to a bill that has gone
 * out.</p>
 */
@Service
@Transactional
public class RaBillService {

    private static final String ENTITY_TYPE = "RA_BILL";

    private final RaBillRepository bills;
    private final RaBillItemRepository billItems;
    private final MeasurementSheetRepository sheets;
    private final AgreementRepository agreements;
    private final BoqLookup boqItems;
    private final SiteAccessGuard siteAccessGuard;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public RaBillService(RaBillRepository bills, RaBillItemRepository billItems,
                         MeasurementSheetRepository sheets, AgreementRepository agreements,
                         BoqLookup boqItems, SiteAccessGuard siteAccessGuard,
                         CurrentUserProvider currentUser, AuditService audit) {
        this.bills = bills;
        this.billItems = billItems;
        this.sheets = sheets;
        this.agreements = agreements;
        this.boqItems = boqItems;
        this.siteAccessGuard = siteAccessGuard;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ the unbilled queue

    /**
     * Measured work no bill has claimed, by item — the screen the engineer lives in, and what
     * the next bill will sweep.
     *
     * <p>Items whose measured total now exceeds the contract quantity are named rather than
     * refused. A deviation is a real thing and the department decides it; what would be wrong
     * is for the excess to be silent.</p>
     */
    @PreAuthorize("hasAuthority('billing:read')")
    @Transactional(readOnly = true)
    public UnbilledSummary unbilled(UUID projectId, LocalDate cutoff) {
        List<MeasurementSheet> pending = visible(sheets.findUnbilled(
                currentUser.currentOrgId(), projectId, cutoff == null, orToday(cutoff)));

        Map<UUID, BigDecimal> quantityByItem = new LinkedHashMap<>();
        Map<UUID, Integer> countByItem = new LinkedHashMap<>();
        for (MeasurementSheet sheet : pending) {
            quantityByItem.merge(sheet.getBoqItemId(), sheet.claimedQuantity(), BigDecimal::add);
            countByItem.merge(sheet.getBoqItemId(), 1, Integer::sum);
        }

        List<UnbilledItem> items = new ArrayList<>();
        List<String> overClaimed = new ArrayList<>();
        BigDecimal totalValue = BigDecimal.ZERO;
        for (Map.Entry<UUID, BigDecimal> entry : quantityByItem.entrySet()) {
            BoqLookup.BoqItemInfo item = boqItems.requireChargeable(entry.getKey());
            BigDecimal paidQty = orZero(billItems.quantityPaidToDate(projectId, entry.getKey()));
            BigDecimal rate = orZero(item.contractRate());
            BigDecimal value = rate.multiply(entry.getValue()).setScale(2, RoundingMode.HALF_UP);
            totalValue = totalValue.add(value);
            if (paidQty.add(entry.getValue()).compareTo(orZero(item.contractQuantity())) > 0) {
                overClaimed.add(item.itemNumber());
            }
            items.add(new UnbilledItem(entry.getKey(), item.itemNumber(), item.description(),
                    item.contractQuantity(), paidQty, entry.getValue(), rate, value,
                    countByItem.getOrDefault(entry.getKey(), 0)));
        }
        return new UnbilledSummary(projectId, cutoff, items, totalValue, pending.size(), overClaimed);
    }

    // ------------------------------------------------------------------ writing

    /**
     * Opens the next bill in the series and claims everything measured up to its cutoff.
     *
     * <p>Refused while another bill on the project is still open: two open bills would each be
     * sweeping from the same pool of unbilled sheets, and whichever was passed second would
     * find its own contents already paid.</p>
     */
    @PreAuthorize("hasAuthority('billing:prepare')")
    public BillResponse create(CreateBillRequest request) {
        UUID orgId = currentUser.currentOrgId();
        requireAgreement(request.projectId());

        bills.latest(request.projectId()).ifPresent(latest -> {
            if (latest.isOpen()) {
                throw new BusinessException("billing.bill-already-open",
                        latest.getTitle() + " is still open. Pass it or discard it before "
                                + "starting the next one — two open bills would both claim the "
                                + "same measurements.");
            }
        });

        int serial = bills.highestSerial(request.projectId()) + 1;
        UUID previousId = bills.latest(request.projectId()).map(RaBill::getId).orElse(null);
        String title = request.title() == null || request.title().isBlank()
                ? RaBill.defaultTitle(serial) : request.title().trim();

        RaBill bill = new RaBill(orgId, request.projectId(), serial, title,
                request.cutoffDate(), previousId);
        bill.setRemarks(request.remarks());
        bills.save(bill);

        List<MeasurementSheet> swept = visible(sheets.findUnbilled(
                orgId, request.projectId(), false, request.cutoffDate()));
        if (swept.isEmpty()) {
            throw new BusinessException("billing.nothing-to-bill",
                    "No signed measurement sheets are waiting on this project up to "
                            + request.cutoffDate() + ". Measure and sign the work first — a bill "
                            + "for nothing is not a bill.");
        }
        for (MeasurementSheet sheet : swept) {
            sheet.setRaBillId(bill.getId());
        }

        audit.record(ENTITY_TYPE, bill.getId(), "CREATE", null,
                Map.of("serialNo", serial, "title", title, "cutoffDate", request.cutoffDate(),
                        "sheetsClaimed", swept.size()), request.remarks());
        return toResponse(bill);
    }

    /**
     * Moving a bill along its chain, and back.
     *
     * <p>Passing is the one that matters: it writes the snapshot and freezes the figures.
     * Re-opening is refused after that, because past the signature money has moved — the same
     * line an expense draws when it refuses to be revised after payment.</p>
     */
    @PreAuthorize("hasAuthority('billing:prepare')")
    public BillResponse decide(UUID id, DecideBillRequest request) {
        RaBill bill = requireLive(id);
        Instant now = Instant.now();
        UUID by = currentUser.currentUserIdOrNull();

        switch (request.action()) {
            case SUBMIT -> {
                assertStatus(bill, RaBill.Status.DRAFT, "submitted");
                bill.submit();
            }
            case CHECK -> {
                assertStatus(bill, RaBill.Status.SUBMITTED, "checked");
                bill.check();
            }
            case PASS -> {
                if (!currentUser.hasPermission("billing:sign")) {
                    throw BusinessException.forbidden(
                            "Passing a bill sends a figure to the department, which is a "
                                    + "different act from preparing it. That needs billing:sign.");
                }
                if (bill.getStatus() != RaBill.Status.CHECKED
                        && bill.getStatus() != RaBill.Status.SUBMITTED) {
                    throw new BusinessException("billing.not-ready-to-pass",
                            bill.getTitle() + " is " + bill.getStatus() + ". A bill is passed "
                                    + "after it has been submitted, not before.");
                }
                freeze(bill, now, by);
            }
            case REOPEN -> {
                if (bill.getStatus() == RaBill.Status.PASSED) {
                    throw new BusinessException("billing.passed-bill-final",
                            bill.getTitle() + " has been passed, so its figures are what was "
                                    + "paid. A correction is a fresh measurement sheet with "
                                    + "negative rows on the next bill.");
                }
                if (request.remarks() == null || request.remarks().isBlank()) {
                    throw new BusinessException("billing.reopen-reason-required",
                            "Sending a bill back needs a reason the preparer can act on.");
                }
                bill.reopen();
            }
        }

        audit.record(ENTITY_TYPE, bill.getId(), request.action().name(), null,
                Map.of("title", bill.getTitle(), "status", bill.getStatus().name()),
                request.remarks());
        return toResponse(bill);
    }

    /**
     * Throws a draft bill away and returns its sheets to the unbilled queue.
     *
     * <p>Only while it is open. Once passed the bill is a record of what was paid, and a
     * record of a payment is not something that gets discarded.</p>
     */
    @PreAuthorize("hasAuthority('billing:prepare')")
    public void discard(UUID id) {
        RaBill bill = requireLive(id);
        if (!bill.isOpen()) {
            throw new BusinessException("billing.passed-bill-final",
                    bill.getTitle() + " has been passed and cannot be discarded.");
        }
        for (MeasurementSheet sheet : sheets.findByRaBillIdOrderByBoqItemIdAscMeasuredOnAsc(id)) {
            sheet.releaseFromBill();
        }
        bill.markDeleted(Instant.now());
        audit.record(ENTITY_TYPE, id, "DISCARD", null, Map.of("title", bill.getTitle()), null);
    }

    // ------------------------------------------------------------------ reading

    @PreAuthorize("hasAuthority('billing:read')")
    @Transactional(readOnly = true)
    public BillResponse get(UUID id) {
        return toResponse(requireLive(id));
    }

    @PreAuthorize("hasAuthority('billing:read')")
    @Transactional(readOnly = true)
    public List<BillSummary> list(UUID projectId) {
        return bills.findByOrgIdAndProjectIdAndDeletedAtIsNullOrderBySerialNoDesc(
                        currentUser.currentOrgId(), projectId).stream()
                .map(b -> new BillSummary(b.getId(), b.getProjectId(), b.getSerialNo(),
                        b.getTitle(), b.getCutoffDate(), b.getStatus(), b.getGrossWorkDone(),
                        b.getRevision()))
                .toList();
    }

    // ------------------------------------------------------------------ internals

    /** Writes the snapshot. The one moment a bill's figures stop being derived. */
    private void freeze(RaBill bill, Instant now, UUID by) {
        List<BillItemResponse> computed = computeItems(bill);
        int order = 0;
        BigDecimal gross = BigDecimal.ZERO;
        for (BillItemResponse row : computed) {
            billItems.save(new RaBillItem(bill.getId(), row.boqItemId(), row.itemNumber(),
                    row.description(), null, row.contractQuantity(), row.qtySincePrevious(),
                    row.qtyToDate(), row.rate(), row.amountToDate(), row.amountPrevious(),
                    row.amountSince(), order++));
            gross = gross.add(row.amountToDate());
        }
        bill.pass(now, by, gross.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * A bill's lines, computed from the sheets it claims.
     *
     * <p>Used while the bill is open, and once more at the moment it is passed to produce the
     * snapshot. After that the snapshot is read instead, and this is never called for that
     * bill again.</p>
     */
    private List<BillItemResponse> computeItems(RaBill bill) {
        List<MeasurementSheet> claimed =
                sheets.findByRaBillIdOrderByBoqItemIdAscMeasuredOnAsc(bill.getId());
        Map<UUID, BigDecimal> sinceByItem = new LinkedHashMap<>();
        for (MeasurementSheet sheet : claimed) {
            sinceByItem.merge(sheet.getBoqItemId(), sheet.claimedQuantity(), BigDecimal::add);
        }

        List<BillItemResponse> rows = new ArrayList<>();
        int order = 0;
        for (Map.Entry<UUID, BigDecimal> entry : sinceByItem.entrySet()) {
            BoqLookup.BoqItemInfo item = boqItems.requireChargeable(entry.getKey());
            BigDecimal since = entry.getValue();
            BigDecimal previousQty = orZero(
                    billItems.quantityPaidToDate(bill.getProjectId(), entry.getKey()));
            BigDecimal previousAmount = orZero(
                    billItems.amountPaidToDate(bill.getProjectId(), entry.getKey()));
            BigDecimal rate = orZero(item.contractRate());
            BigDecimal toDate = previousQty.add(since);
            BigDecimal amountSince = rate.multiply(since).setScale(2, RoundingMode.HALF_UP);
            rows.add(new BillItemResponse(entry.getKey(), item.itemNumber(), item.description(),
                    item.contractQuantity(), since, toDate, rate,
                    previousAmount.add(amountSince), previousAmount, amountSince, order++));
        }
        return rows;
    }

    /** A passed bill reads its snapshot; an open one is computed from the sheets it holds. */
    private BillResponse toResponse(RaBill bill) {
        boolean frozen = bill.getStatus() == RaBill.Status.PASSED;
        List<BillItemResponse> items = frozen
                ? billItems.findByRaBillIdOrderBySortOrder(bill.getId()).stream()
                        .map(i -> new BillItemResponse(i.getBoqItemId(), i.getItemNumber(),
                                i.getDescription(), i.getContractQuantity(),
                                i.getQtySincePrevious(), i.getQtyToDate(), i.getRate(),
                                i.getAmountToDate(), i.getAmountPrevious(), i.getAmountSince(),
                                i.getSortOrder()))
                        .toList()
                : computeItems(bill);
        BigDecimal since = items.stream().map(BillItemResponse::amountSince)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        BigDecimal gross = frozen ? bill.getGrossWorkDone()
                : items.stream().map(BillItemResponse::amountToDate)
                        .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        return new BillResponse(bill.getId(), bill.getProjectId(), bill.getSerialNo(),
                bill.getTitle(), bill.getCutoffDate(), bill.getPreviousBillId(), bill.getStatus(),
                frozen, bill.getFrozenAt(), bill.getFrozenBy(), gross, since, bill.getRevision(),
                bill.getRemarks(), bill.getVersion(), items);
    }

    /**
     * The tender's own details — contractor, the officer who measured, the division — are
     * asked for once, when the first bill of that tender is prepared, and stand for every bill
     * after it. Refusing here rather than printing a bill with blanks on it is the point: a
     * CPWA-26 form with no contractor named on it is not a document anybody can pass.
     */
    private void requireAgreement(UUID projectId) {
        agreements.findByOrgIdAndProjectId(currentUser.currentOrgId(), projectId)
                .orElseThrow(() -> new BusinessException("billing.agreement-required",
                        "Before the first bill of this tender, the details that print on every "
                                + "page of it are needed once — the agreement number, the "
                                + "contractor, the officer who measured, and the rate "
                                + "adjustments. Fill those in and the bills after this one will "
                                + "not ask again."));
    }

    private RaBill requireLive(UUID id) {
        return bills.findByIdAndOrgIdAndDeletedAtIsNull(id, currentUser.currentOrgId())
                .orElseThrow(() -> BusinessException.notFound("Running account bill", id));
    }

    private static void assertStatus(RaBill bill, RaBill.Status expected, String verb) {
        if (bill.getStatus() != expected) {
            throw new BusinessException("billing.wrong-status",
                    bill.getTitle() + " is " + bill.getStatus() + ", so it cannot be " + verb
                            + " now.");
        }
    }

    /** A register is narrowed to what the reader may see, never refused outright. */
    private List<MeasurementSheet> visible(List<MeasurementSheet> found) {
        List<MeasurementSheet> out = new ArrayList<>(found.size());
        for (MeasurementSheet sheet : found) {
            if (siteAccessGuard.canAccess(sheet.getSiteId())) {
                out.add(sheet);
            }
        }
        return out;
    }

    /** Any bound date will do when the flag says to ignore it; today is the honest one. */
    private static LocalDate orToday(LocalDate cutoff) {
        return cutoff == null ? LocalDate.now() : cutoff;
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}

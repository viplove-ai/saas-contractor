package in.nirman.modules.inventory.service;

import in.nirman.common.BusinessException;
import in.nirman.common.DocumentNumberService;
import in.nirman.common.PageResponse;
import in.nirman.common.PeriodLockGuard;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.inventory.api.dto.InventoryDtos.CreateTransferRequest;
import in.nirman.modules.inventory.api.dto.InventoryDtos.ReceiveTransferLine;
import in.nirman.modules.inventory.api.dto.InventoryDtos.ReceiveTransferRequest;
import in.nirman.modules.inventory.api.dto.InventoryDtos.TransferLine;
import in.nirman.modules.inventory.api.dto.InventoryDtos.TransferResponse;
import in.nirman.modules.inventory.domain.StockTransaction;
import in.nirman.modules.inventory.domain.StockTransaction.SourceType;
import in.nirman.modules.inventory.domain.StockTransaction.TxnType;
import in.nirman.modules.inventory.domain.StockTransfer;
import in.nirman.modules.inventory.domain.StockTransferItem;
import in.nirman.modules.inventory.repository.StockTransferItemRepository;
import in.nirman.modules.inventory.repository.StockTransferRepository;
import in.nirman.modules.masterdata.service.MaterialLookup;
import in.nirman.modules.project.service.SiteLookup;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Moving material between two of our own stores.
 *
 * <p><b>The rule the whole three-step lifecycle exists for:</b> between dispatch and
 * receipt the material is counted at neither store. Dispatch posts a {@code TRANSFER_OUT}
 * that takes it out of the sender; receipt posts a {@code TRANSFER_IN} that puts what
 * actually arrived into the receiver; in between it is a quantity on a lorry, visible as
 * the destination's in-transit figure and issuable by nobody.</p>
 *
 * <p>The obvious alternative — leave it at the sender until it arrives — is what lets two
 * storekeepers promise the same forty bags to two different work faces on the same morning.
 * The other alternative, credit the receiver on dispatch, lets him issue material that is
 * still on the road.</p>
 *
 * <p>A transfer is not a purchase, so it never reprices anything: the sending store's
 * moving average is frozen onto each line at dispatch and carried across unchanged.</p>
 */
@Service
@Transactional
public class StockTransferService {

    private final StockTransferRepository transfers;
    private final StockTransferItemRepository lines;
    private final StockLedgerService ledger;
    private final MaterialLookup materials;
    private final SiteLookup sites;
    private final SiteAccessGuard siteAccessGuard;
    private final PeriodLockGuard periodLockGuard;
    private final DocumentNumberService documentNumbers;
    private final InventoryResponses responses;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public StockTransferService(StockTransferRepository transfers, StockTransferItemRepository lines,
                                StockLedgerService ledger, MaterialLookup materials,
                                SiteLookup sites, SiteAccessGuard siteAccessGuard,
                                PeriodLockGuard periodLockGuard,
                                DocumentNumberService documentNumbers, InventoryResponses responses,
                                CurrentUserProvider currentUser, AuditService audit) {
        this.transfers = transfers;
        this.lines = lines;
        this.ledger = ledger;
        this.materials = materials;
        this.sites = sites;
        this.siteAccessGuard = siteAccessGuard;
        this.periodLockGuard = periodLockGuard;
        this.documentNumbers = documentNumbers;
        this.responses = responses;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('inventory:read')")
    public PageResponse<TransferResponse> list(UUID fromStoreId, UUID toStoreId,
                                               StockTransfer.Status status, LocalDate from,
                                               LocalDate to, Pageable pageable) {
        boolean restricted = !currentUser.seesAllSites();
        Collection<UUID> visibleStores = restricted ? myStoreIds() : List.of();
        if (restricted && visibleStores.isEmpty()) {
            return new PageResponse<>(List.of(), 0, pageable.getPageSize(), 0, 0, true, true);
        }
        return PageResponse.from(
                transfers.search(orgId(), fromStoreId, toStoreId, status, from, to,
                        restricted, visibleStores, pageable),
                responses::toTransferResponse);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('inventory:read')")
    public TransferResponse get(UUID id) {
        StockTransfer transfer = require(id);
        assertAtEitherEnd(transfer);
        return responses.toTransferResponse(transfer);
    }

    /**
     * Raises a transfer. Nothing moves yet — the lorry has not left.
     *
     * <p>The sending store's site is the one that has to be the caller's. Receiving is
     * somebody else's job at the other end, and requiring access to both would mean no
     * transfer between two sites could ever be raised by anybody.</p>
     */
    @PreAuthorize("hasAuthority('inventory:transfer')")
    public TransferResponse create(CreateTransferRequest request) {
        if (request.fromStoreId().equals(request.toStoreId())) {
            throw new BusinessException("transfer.same-store",
                    "A transfer needs two different stores.");
        }
        SiteLookup.StoreInfo from = sites.requireStore(request.fromStoreId());
        SiteLookup.StoreInfo to = sites.findStore(request.toStoreId())
                .orElseThrow(() -> BusinessException.notFound("Store", request.toStoreId()));
        periodLockGuard.assertOpen(from.siteId(), request.transferDate(),
                PeriodLockGuard.Module.INVENTORY);

        var existing = transfers.findByIdAndOrgId(request.id(), orgId());
        if (existing.isPresent()) {
            return responses.toTransferResponse(existing.get());   // the offline replay
        }

        String number = documentNumbers.next(orgId(), DocumentNumberService.DocType.STOCK_TRANSFER,
                request.transferDate());
        StockTransfer transfer = new StockTransfer(request.id(), orgId(), from.id(), to.id(),
                number, request.transferDate());
        transfer.setVehicleNumber(request.vehicleNumber());
        transfer.setRemarks(request.remarks());
        // Header before lines: the lines carry a foreign key to it.
        transfers.save(transfer);

        List<StockTransferItem> saved = saveLines(transfer, request.lines());

        audit.record("STOCK_TRANSFER", transfer.getId(), "CREATE", null,
                Map.of("transferNumber", number, "fromStoreId", from.id().toString(),
                        "toStoreId", to.id().toString(), "lines", saved.size()), null);
        return responses.toTransferResponse(transfer);
    }

    /**
     * The lorry leaves. Takes the material out of the sending store and records it as on
     * its way to the receiving one, where nobody can issue it.
     */
    @PreAuthorize("hasAuthority('inventory:transfer')")
    public TransferResponse dispatch(UUID id) {
        StockTransfer transfer = require(id);
        SiteLookup.StoreInfo from = sites.requireStore(transfer.getFromStoreId());
        periodLockGuard.assertOpen(from.siteId(), transfer.getTransferDate(),
                PeriodLockGuard.Module.INVENTORY);
        if (transfer.getStatus() != StockTransfer.Status.CREATED) {
            throw new BusinessException("transfer.not-dispatchable",
                    "Transfer " + transfer.getTransferNumber() + " is already "
                            + transfer.getStatus().name().toLowerCase().replace('_', ' ') + ".");
        }

        transfer.dispatch(Instant.now(), currentUser.currentUserIdOrNull());
        for (StockTransferItem line : lines.findByTransferId(transfer.getId())) {
            StockTransaction posted = ledger.post(new StockLedgerService.Movement(
                    transfer.getOrgId(), from.projectId(), from.siteId(), from.id(),
                    line.getMaterialId(), TxnType.TRANSFER_OUT, transfer.getTransferDate(),
                    line.getQuantityBase(), null, SourceType.TRANSFER, transfer.getId(),
                    line.getId(), null, "Transfer " + transfer.getTransferNumber()));
            if (posted != null) {
                line.dispatchAt(posted.getRateBase());
                ledger.markInTransit(transfer.getOrgId(), transfer.getToStoreId(),
                        line.getMaterialId(), line.getQuantityBase());
            }
        }

        audit.record("STOCK_TRANSFER", id, "DISPATCH", null,
                Map.of("transferNumber", transfer.getTransferNumber()), null);
        return responses.toTransferResponse(transfer);
    }

    /**
     * The lorry arrives. Credits the receiving store with what actually turned up, at the
     * rate frozen at dispatch, and records the difference as a shortage.
     *
     * <p>The permission check is on the <b>receiving</b> store: the person signing for a
     * delivery is the one standing at the destination gate.</p>
     */
    @PreAuthorize("hasAuthority('inventory:transfer')")
    public TransferResponse receive(UUID id, ReceiveTransferRequest request) {
        StockTransfer transfer = require(id);
        SiteLookup.StoreInfo to = sites.requireStore(transfer.getToStoreId());
        periodLockGuard.assertOpen(to.siteId(), transfer.getTransferDate(),
                PeriodLockGuard.Module.INVENTORY);
        if (transfer.getStatus() != StockTransfer.Status.IN_TRANSIT) {
            throw new BusinessException("transfer.not-receivable",
                    "Transfer " + transfer.getTransferNumber() + " is "
                            + transfer.getStatus().name().toLowerCase().replace('_', ' ')
                            + " and cannot be received.");
        }

        Map<UUID, ReceiveTransferLine> declared = request.lines() == null ? Map.of()
                : request.lines().stream().collect(Collectors.toMap(
                        ReceiveTransferLine::lineId, Function.identity(), (a, b) -> a));

        transfer.receive(Instant.now(), currentUser.currentUserIdOrNull());
        BigDecimal totalShortage = BigDecimal.ZERO;
        for (StockTransferItem line : lines.findByTransferId(transfer.getId())) {
            ReceiveTransferLine said = declared.get(line.getId());
            line.receiveQuantity(said == null ? null : said.receivedQuantityBase());
            if (said != null && said.remarks() != null) {
                line.setRemarks(said.remarks());
            }
            totalShortage = totalShortage.add(line.getShortageQtyBase());

            // The in-transit figure clears by what left, not by what arrived: the shortage
            // never reaches this store, and leaving it on the road for ever is worse than
            // recording that it went missing.
            ledger.clearInTransit(transfer.getOrgId(), to.id(), line.getMaterialId(),
                    line.getQuantityBase());
            if (line.getReceivedQtyBase().signum() > 0) {
                ledger.post(new StockLedgerService.Movement(transfer.getOrgId(), to.projectId(),
                        to.siteId(), to.id(), line.getMaterialId(), TxnType.TRANSFER_IN,
                        transfer.getTransferDate(), line.getReceivedQtyBase(), line.getRateBase(),
                        SourceType.TRANSFER, transfer.getId(), line.getId(), null,
                        "Transfer " + transfer.getTransferNumber()));
            }
        }
        if (request.remarks() != null) {
            transfer.setRemarks(request.remarks());
        }

        audit.record("STOCK_TRANSFER", id, "RECEIVE", null,
                Map.of("transferNumber", transfer.getTransferNumber(),
                        "shortage", totalShortage), request.remarks());
        return responses.toTransferResponse(transfer);
    }

    @PreAuthorize("hasAuthority('inventory:transfer')")
    public TransferResponse cancel(UUID id, String reason) {
        StockTransfer transfer = require(id);
        sites.requireStore(transfer.getFromStoreId());
        if (transfer.getStatus() != StockTransfer.Status.CREATED) {
            throw new BusinessException("transfer.already-dispatched",
                    "Transfer " + transfer.getTransferNumber() + " has already left. Receive it "
                            + "and send it back, or post an adjustment at each end.");
        }
        transfer.cancel(reason);
        audit.record("STOCK_TRANSFER", id, "CANCEL", null,
                Map.of("transferNumber", transfer.getTransferNumber()), reason);
        return responses.toTransferResponse(transfer);
    }

    // ------------------------------------------------------------------ internals

    private List<StockTransferItem> saveLines(StockTransfer transfer, List<TransferLine> requested) {
        List<StockTransferItem> built = new ArrayList<>();
        for (TransferLine line : requested) {
            BigDecimal factor = materials.factorToBase(line.materialId(), line.unitId());
            StockTransferItem item = new StockTransferItem(transfer.getId(), line.materialId(),
                    line.unitId(), line.quantity(), factor);
            item.setRemarks(line.remarks());
            BigDecimal available = ledger.available(transfer.getFromStoreId(), line.materialId());
            if (available.compareTo(item.getQuantityBase()) < 0) {
                throw new BusinessException("stock.insufficient",
                        "The sending store holds %s of %s and this transfer moves %s."
                                .formatted(available.toPlainString(),
                                        materials.require(line.materialId()).name(),
                                        item.getQuantityBase().toPlainString()));
            }
            built.add(item);
        }
        return lines.saveAll(built);
    }

    /** A transfer is readable by whoever runs either end of it. */
    private void assertAtEitherEnd(StockTransfer transfer) {
        if (currentUser.seesAllSites()) {
            return;
        }
        boolean atSender = sites.findStore(transfer.getFromStoreId())
                .map(store -> siteAccessGuard.canAccess(store.siteId())).orElse(false);
        boolean atReceiver = sites.findStore(transfer.getToStoreId())
                .map(store -> siteAccessGuard.canAccess(store.siteId())).orElse(false);
        if (!atSender && !atReceiver) {
            throw BusinessException.forbidden(
                    "This transfer is between two stores you do not run.");
        }
    }

    private Collection<UUID> myStoreIds() {
        return sites.storesAtSites(currentUser.assignedSiteIds()).stream()
                .map(SiteLookup.StoreInfo::id)
                .toList();
    }

    private StockTransfer require(UUID id) {
        return transfers.findByIdAndOrgId(id, orgId())
                .orElseThrow(() -> BusinessException.notFound("Stock transfer", id));
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }
}

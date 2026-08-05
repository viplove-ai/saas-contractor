package in.nirman.modules.inventory.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.inventory.domain.StockBalance;
import in.nirman.modules.inventory.domain.StockTransaction;
import in.nirman.modules.inventory.domain.StockTransaction.SourceType;
import in.nirman.modules.inventory.domain.StockTransaction.TxnType;
import in.nirman.modules.inventory.repository.StockBalanceRepository;
import in.nirman.modules.inventory.repository.StockTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The single write path into stock. Every receipt, issue, transfer, wastage, count
 * difference and opening declaration comes through here and nowhere else.
 *
 * <p>That is not a style preference. It is what makes four separate guarantees hold at
 * once, none of which can be enforced by a document service that writes its own balance
 * row:</p>
 *
 * <ol>
 *   <li><b>Stock cannot go negative.</b> Checked here, before the ledger row exists, with
 *       the balance row already locked so the answer cannot go stale between the check and
 *       the write. The rejected attempt is logged in its own transaction, because the one
 *       that made it is about to roll back.</li>
 *   <li><b>The balance always matches the ledger.</b> They are written together, under the
 *       same lock, in the same transaction. The cache cannot drift because there is no
 *       moment at which one exists without the other.</li>
 *   <li><b>The moving average is arithmetic, not bookkeeping.</b> It lives on the balance
 *       and moves only on the way in; the rate an issue is valued at is whatever the
 *       average is when the material leaves, frozen onto the ledger row.</li>
 *   <li><b>A document line posts once.</b> Verifying a receipt twice, or a request retried
 *       after the first one committed, is a quiet no-op rather than a doubled delivery —
 *       the same guarantee attendance verification gives a worker's wage.</li>
 * </ol>
 *
 * <p>Everything runs {@code MANDATORY}: a ledger row must commit with the document that
 * caused it, or a crash between the two leaves a store holding material no paper explains.</p>
 */
@Service
public class StockLedgerService {

    private static final Logger log = LoggerFactory.getLogger(StockLedgerService.class);

    private final StockTransactionRepository transactions;
    private final StockBalanceRepository balances;
    private final StockViolationRecorder violations;

    public StockLedgerService(StockTransactionRepository transactions,
                              StockBalanceRepository balances,
                              StockViolationRecorder violations) {
        this.transactions = transactions;
        this.balances = balances;
        this.violations = violations;
    }

    /**
     * Everything one movement needs. A record rather than eleven parameters, because the
     * callers differ in which fields they fill and a positional list of that length is how
     * a store id ends up in a material id.
     *
     * @param rateBase      what the material cost per base unit, on the way in. Ignored on
     *                      the way out, where the store's own average decides.
     * @param sourceLineId  the document line. Null only for movements that have no line —
     *                      opening stock and a bare adjustment.
     */
    public record Movement(
            UUID orgId,
            UUID projectId,
            UUID siteId,
            UUID storeId,
            UUID materialId,
            TxnType txnType,
            LocalDate txnDate,
            BigDecimal quantityBase,
            BigDecimal rateBase,
            SourceType sourceType,
            UUID sourceId,
            UUID sourceLineId,
            UUID boqItemId,
            String reason) {
    }

    /**
     * Posts one movement and updates the store's balance.
     *
     * @return the ledger row, or {@code null} when this document line has already been
     *         posted — the offline replay, which must not move stock a second time
     * @throws BusinessException 422 when the store cannot cover an outward movement
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockTransaction post(Movement movement) {
        return post(movement, StockTransaction.directionOf(movement.txnType()));
    }

    /**
     * Posts a movement whose direction does not follow from its type. Only adjustments
     * qualify: a physical count can find more than the ledger expected as easily as less,
     * and guessing which from the type would be guessing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockTransaction post(Movement movement, short direction) {
        if (movement.quantityBase() == null || movement.quantityBase().signum() <= 0) {
            throw new IllegalArgumentException("a movement must carry a positive quantity");
        }
        if (alreadyPosted(movement)) {
            log.debug("Line {} already posted as {} — skipping", movement.sourceLineId(),
                    movement.txnType());
            return null;
        }

        StockBalance balance = lockBalance(movement);
        if (direction == StockTransaction.OUT && balance.cannotCover(movement.quantityBase())) {
            reject(movement, balance);
        }

        // The balance moves first, and hands back the rate it valued the movement at. On the
        // way out that is the store's own average, which the caller had no way to know — so
        // the ledger row is built afterwards, from the answer rather than from the request.
        BigDecimal appliedRate = balance.apply(direction, movement.quantityBase(),
                movement.rateBase());

        StockTransaction posted = new StockTransaction(movement.orgId(), movement.projectId(),
                movement.siteId(), movement.storeId(), movement.materialId(), movement.txnType(),
                direction, movement.txnDate(), movement.quantityBase(), appliedRate,
                movement.sourceType(), movement.sourceId(), movement.sourceLineId(),
                movement.boqItemId(), movement.reason());
        posted.snapshot(balance.getQuantityBase(), balance.getMovingAvgRate());

        transactions.save(posted);
        balances.save(balance);
        return posted;
    }

    /**
     * What one store holds of one material right now. Zero when the store has never seen it
     * — a material nobody has ever received is not an error, it is an empty shelf.
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public BigDecimal available(UUID storeId, UUID materialId) {
        return balances.findByStoreIdAndMaterialId(storeId, materialId)
                .map(StockBalance::getQuantityBase)
                .orElse(BigDecimal.ZERO);
    }

    /** The rate an issue from this store would be valued at, without moving anything. */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public BigDecimal currentRate(UUID storeId, UUID materialId) {
        return balances.findByStoreIdAndMaterialId(storeId, materialId)
                .map(StockBalance::getMovingAvgRate)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Records material as dispatched towards a store without adding it to that store's
     * issuable quantity. See {@link in.nirman.modules.inventory.domain.StockTransfer} for
     * why it is counted at neither end while it is on the lorry.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markInTransit(UUID orgId, UUID toStoreId, UUID materialId, BigDecimal quantity) {
        balanceFor(orgId, toStoreId, materialId).addInTransit(quantity);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void clearInTransit(UUID orgId, UUID toStoreId, UUID materialId, BigDecimal quantity) {
        balanceFor(orgId, toStoreId, materialId).clearInTransit(quantity);
    }

    // ------------------------------------------------------------------ internals

    private boolean alreadyPosted(Movement movement) {
        return movement.sourceLineId() != null
                && transactions.existsBySourceTypeAndSourceLineIdAndTxnType(
                        movement.sourceType(), movement.sourceLineId(), movement.txnType());
    }

    private StockBalance lockBalance(Movement movement) {
        return balances.findForUpdate(movement.storeId(), movement.materialId())
                .orElseGet(() -> balances.save(new StockBalance(movement.orgId(),
                        movement.storeId(), movement.materialId())));
    }

    private StockBalance balanceFor(UUID orgId, UUID storeId, UUID materialId) {
        return balances.findForUpdate(storeId, materialId)
                .orElseGet(() -> balances.save(new StockBalance(orgId, storeId, materialId)));
    }

    /**
     * Refuses the movement and logs the attempt.
     *
     * <p>The message names both figures on purpose. "Insufficient stock" sends a
     * storekeeper to a screen to find out by how much; saying it here means he can correct
     * the slip without leaving the one he is on.</p>
     */
    private void reject(Movement movement, StockBalance balance) {
        violations.record(movement.orgId(), movement.storeId(), movement.materialId(),
                movement.quantityBase(), balance.getQuantityBase(),
                movement.sourceType().name());
        throw new BusinessException("stock.insufficient",
                "This store holds %s and the entry takes out %s. Stock cannot go negative."
                        .formatted(balance.getQuantityBase().toPlainString(),
                                movement.quantityBase().toPlainString()));
    }
}

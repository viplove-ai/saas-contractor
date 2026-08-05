package in.nirman.modules.inventory.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Material moving from one store to another.
 *
 * <p>Carries its own states rather than the shared {@link DocumentWorkflow}, because a
 * transfer is not approved — it is dispatched and it is received, and those are two
 * physical events at two different places with two different people answering for them.</p>
 *
 * <p><b>The rule the whole lifecycle exists for:</b> between dispatch and receipt the
 * material is counted at neither store. Dispatch takes it out of the sending store, receipt
 * puts it into the receiving one, and in between it is a quantity on a lorry, recorded as
 * the destination's {@code in_transit_qty_base} so somebody can see it coming without being
 * able to issue it. The alternative — leaving it at the sender until it arrives — means two
 * stores can both promise the same forty bags to two different work faces.</p>
 *
 * <p>A shortage is therefore a real outcome, not an error: forty bags leave and thirty-nine
 * arrive. The difference is recorded on the line, and the receiving store is credited with
 * what actually turned up.</p>
 */
@Entity
@Table(name = "stock_transfers")
public class StockTransfer extends BaseEntity {

    public enum Status {
        CREATED,
        /** Loaded and gone. Out of the sending store, into nobody's. */
        DISPATCHED,
        IN_TRANSIT,
        RECEIVED,
        CLOSED,
        CANCELLED;

        public boolean isEditable() {
            return this == CREATED;
        }
    }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "transfer_number", nullable = false, length = 50, updatable = false)
    private String transferNumber;

    @Column(name = "from_store_id", nullable = false, updatable = false)
    private UUID fromStoreId;

    @Column(name = "to_store_id", nullable = false, updatable = false)
    private UUID toStoreId;

    @Column(name = "transfer_date", nullable = false)
    private LocalDate transferDate;

    @Column(name = "vehicle_number", length = 25)
    private String vehicleNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.CREATED;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "dispatched_by")
    private UUID dispatchedBy;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "received_by")
    private UUID receivedBy;

    @Column(name = "remarks")
    private String remarks;

    protected StockTransfer() {
    }

    public StockTransfer(UUID id, UUID orgId, UUID fromStoreId, UUID toStoreId, String transferNumber,
                         LocalDate transferDate) {
        setId(id);
        this.orgId = orgId;
        this.fromStoreId = fromStoreId;
        this.toStoreId = toStoreId;
        this.transferNumber = transferNumber;
        this.transferDate = transferDate;
    }

    public void dispatch(Instant at, UUID by) {
        this.status = Status.IN_TRANSIT;
        this.dispatchedAt = at;
        this.dispatchedBy = by;
    }

    public void receive(Instant at, UUID by) {
        this.status = Status.RECEIVED;
        this.receivedAt = at;
        this.receivedBy = by;
    }

    public void cancel(String reason) {
        this.status = Status.CANCELLED;
        this.remarks = reason;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getTransferNumber() {
        return transferNumber;
    }

    public UUID getFromStoreId() {
        return fromStoreId;
    }

    public UUID getToStoreId() {
        return toStoreId;
    }

    public LocalDate getTransferDate() {
        return transferDate;
    }

    public String getVehicleNumber() {
        return vehicleNumber;
    }

    public void setVehicleNumber(String vehicleNumber) {
        this.vehicleNumber = vehicleNumber;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getDispatchedAt() {
        return dispatchedAt;
    }

    public UUID getDispatchedBy() {
        return dispatchedBy;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public UUID getReceivedBy() {
        return receivedBy;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }
}

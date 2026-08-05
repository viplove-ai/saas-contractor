package in.nirman.modules.project.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One priced line of work from the contract — <i>RCC M25 in columns, 5.94 cum</i>.
 *
 * <p>It lives in the project module rather than a module of its own because it is what
 * labour, material and cash all point at. That shared pair, {@code site_id} plus
 * {@code boq_item_id}, is the single thing that makes "cost incurred against work item X"
 * answerable across three modules in one query (docs/02).</p>
 *
 * <p>Phase 4 needs it read-mostly: a material issue charges consumption to a line, and a
 * material estimate is scoped to one. Progress recording and the measurement-book structure
 * that {@code parentId} is there for arrive with Phase 6.</p>
 */
@Entity
@Table(name = "boq_items")
public class BoqItem extends BaseEntity {

    public enum Status { NOT_STARTED, IN_PROGRESS, COMPLETED, ON_HOLD, CANCELLED }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /** Null means the line applies to the whole project rather than one site. */
    @Column(name = "site_id")
    private UUID siteId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "item_number", nullable = false, length = 40, updatable = false)
    private String itemNumber;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "unit_id", nullable = false)
    private UUID unitId;

    @Column(name = "contract_quantity", nullable = false, precision = 18, scale = 4)
    private BigDecimal contractQuantity = BigDecimal.ZERO;

    @Column(name = "contract_rate", nullable = false, precision = 18, scale = 4)
    private BigDecimal contractRate = BigDecimal.ZERO;

    @Column(name = "contract_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal contractAmount = BigDecimal.ZERO;

    @Column(name = "completed_quantity", nullable = false, precision = 18, scale = 4)
    private BigDecimal completedQuantity = BigDecimal.ZERO;

    @Column(name = "budget_material_cost", nullable = false, precision = 18, scale = 2)
    private BigDecimal budgetMaterialCost = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.NOT_STARTED;

    /** Civil Works | E&M Works, as the tender parser classifies it. */
    @Column(name = "work_part", length = 40)
    private String workPart;

    /** Matches {@code material_consumption_norms.work_category}, so a norm can be found. */
    @Column(name = "category", length = 80)
    private String category;

    @Column(name = "source", nullable = false, length = 20)
    private String source = "MANUAL";

    /**
     * A reconciliation placeholder the tender parser emits when the extracted lines do not
     * sum to the stated BOQ total. Labour, material and cash must never be charged to one:
     * it is a rounding gap, not work.
     */
    @Column(name = "is_synthetic", nullable = false)
    private boolean synthetic;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "planned_start_date")
    private LocalDate plannedStartDate;

    @Column(name = "planned_completion_date")
    private LocalDate plannedCompletionDate;

    /** Stamped by the first progress entry, so "started" is a measured fact, not a promise. */
    @Column(name = "actual_start_date")
    private LocalDate actualStartDate;

    @Column(name = "actual_completion_date")
    private LocalDate actualCompletionDate;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected BoqItem() {
    }

    public BoqItem(UUID orgId, UUID projectId, String itemNumber, String description, UUID unitId) {
        this.orgId = orgId;
        this.projectId = projectId;
        this.itemNumber = itemNumber;
        this.description = description;
        this.unitId = unitId;
    }

    /** Contract amount is derived from quantity and rate, never typed independently of them. */
    public void priceAt(BigDecimal quantity, BigDecimal rate) {
        this.contractQuantity = quantity;
        this.contractRate = rate;
        this.contractAmount = quantity.multiply(rate).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Applies a measurement to the line's running total.
     *
     * <p>{@code completedQuantity} is a <b>cache of the sum of {@code boq_progress_entries}</b>,
     * moved only by this method and only by the progress service, so the two can never say
     * different things. The same discipline as the stock balance under the stock ledger.</p>
     *
     * <p>Deliberately permits a total above the contract quantity. Over-measurement against a
     * tendered figure is ordinary on a construction site — the drawings demand more than the
     * NIT quantified, or the line absorbs work nobody itemised — and a system that refused it
     * would simply be lied to. It is reported as an over-claim instead, which is a
     * conversation somebody can have.</p>
     *
     * @param on the measurement date, which is what dates the start and completion of the work
     * @throws IllegalArgumentException if the claim would drive the total below zero: less
     *                                  than nothing has never been built
     */
    public void claimProgress(BigDecimal delta, LocalDate on) {
        BigDecimal claimed = completedQuantity.add(delta);
        if (claimed.signum() < 0) {
            throw new IllegalArgumentException("completed quantity cannot fall below zero");
        }
        this.completedQuantity = claimed;
        if (claimed.signum() > 0 && actualStartDate == null) {
            this.actualStartDate = on;
        }
        if (status == Status.NOT_STARTED || status == Status.IN_PROGRESS
                || status == Status.COMPLETED) {
            if (contractQuantity.signum() > 0 && claimed.compareTo(contractQuantity) >= 0) {
                this.status = Status.COMPLETED;
                this.actualCompletionDate = on;
            } else if (claimed.signum() > 0) {
                this.status = Status.IN_PROGRESS;
                // A negative correction that reopens a finished line must clear the date too,
                // or the report says the work finished on a day it demonstrably had not.
                this.actualCompletionDate = null;
            } else {
                this.status = Status.NOT_STARTED;
                this.actualStartDate = null;
                this.actualCompletionDate = null;
            }
        }
    }

    /** Claimed beyond what the contract quantified. Zero when the line is inside its scope. */
    public BigDecimal overClaimedQuantity() {
        if (contractQuantity.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal over = completedQuantity.subtract(contractQuantity);
        return over.signum() > 0 ? over : BigDecimal.ZERO;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getSiteId() {
        return siteId;
    }

    public void setSiteId(UUID siteId) {
        this.siteId = siteId;
    }

    public UUID getParentId() {
        return parentId;
    }

    public void setParentId(UUID parentId) {
        this.parentId = parentId;
    }

    public String getItemNumber() {
        return itemNumber;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getUnitId() {
        return unitId;
    }

    public void setUnitId(UUID unitId) {
        this.unitId = unitId;
    }

    public BigDecimal getContractQuantity() {
        return contractQuantity;
    }

    public BigDecimal getContractRate() {
        return contractRate;
    }

    public BigDecimal getContractAmount() {
        return contractAmount;
    }

    public BigDecimal getCompletedQuantity() {
        return completedQuantity;
    }

    public BigDecimal getBudgetMaterialCost() {
        return budgetMaterialCost;
    }

    public void setBudgetMaterialCost(BigDecimal budgetMaterialCost) {
        this.budgetMaterialCost = budgetMaterialCost;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getWorkPart() {
        return workPart;
    }

    public void setWorkPart(String workPart) {
        this.workPart = workPart;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public boolean isSynthetic() {
        return synthetic;
    }

    public void setSynthetic(boolean synthetic) {
        this.synthetic = synthetic;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public LocalDate getPlannedStartDate() {
        return plannedStartDate;
    }

    public void setPlannedStartDate(LocalDate plannedStartDate) {
        this.plannedStartDate = plannedStartDate;
    }

    public LocalDate getPlannedCompletionDate() {
        return plannedCompletionDate;
    }

    public void setPlannedCompletionDate(LocalDate plannedCompletionDate) {
        this.plannedCompletionDate = plannedCompletionDate;
    }

    public LocalDate getActualStartDate() {
        return actualStartDate;
    }

    public LocalDate getActualCompletionDate() {
        return actualCompletionDate;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}

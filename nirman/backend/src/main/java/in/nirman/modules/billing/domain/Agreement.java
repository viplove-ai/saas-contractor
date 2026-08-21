package in.nirman.modules.billing.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What the contract is called, and the arithmetic that turns a published schedule rate into
 * the rate this contract actually pays.
 *
 * <p>A CPWD rate is not a number, it is three steps: the schedule rate times a coefficient
 * factor, plus the station's cost index, less the percentage the contractor tendered below.
 * In the spreadsheet this reads {@code =D4*0.973}, then {@code =E4+(E4*23%)}, then
 * {@code =F4-(F4*11.11%)} — forty times over, with the three constants retyped into every
 * one of them.</p>
 *
 * <p><b>The chain is stored, never the answer.</b> A cost index revised by circular then
 * reprices every derived rate instead of needing forty cells found and changed; and when the
 * Assistant Engineer questions a rate, the derivation can be shown rather than asserted. It
 * is the same reason the expense module stores one half of a split rather than two.</p>
 */
@Entity
@Table(name = "agreements")
public class Agreement extends BaseEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "agreement_no", length = 120)
    private String agreementNo;

    @Column(name = "division", length = 200)
    private String division;

    @Column(name = "sub_division", length = 200)
    private String subDivision;

    @Column(name = "dsr_schedule_id")
    private UUID dsrScheduleId;

    /** Step one. 1 means the schedule rate is taken as printed, which is a real case. */
    @Column(name = "dsr_coefficient", nullable = false, precision = 9, scale = 6)
    private BigDecimal dsrCoefficient = BigDecimal.ONE;

    /** Step two, as a percentage added. 23 for a station 23% above Delhi. */
    @Column(name = "cost_index_pct", nullable = false, precision = 9, scale = 4)
    private BigDecimal costIndexPct = BigDecimal.ZERO;

    /** Step three. Signed, and negative is the usual direction: -11.11 is 11.11% below. */
    @Column(name = "tender_pct", nullable = false, precision = 9, scale = 4)
    private BigDecimal tenderPct = BigDecimal.ZERO;

    @Column(name = "deviation_limit_pct", nullable = false, precision = 9, scale = 4)
    private BigDecimal deviationLimitPct = new BigDecimal("100");

    /**
     * The names and figures that print on every page of every bill for this tender and change
     * only between tenders. Asked once when the NIT is imported, rather than retyped into
     * forty-three sheets — which is how the source workbook came to say "3rd RA Bill" on its
     * measurement pages and "4th RA Bill" on its abstract.
     */
    @Column(name = "contractor_name", length = 200)
    private String contractorName;

    @Column(name = "measured_by_name", length = 200)
    private String measuredByName;

    @Column(name = "measured_by_designation", length = 120)
    private String measuredByDesignation;

    @Column(name = "prepared_by_name", length = 200)
    private String preparedByName;

    @Column(name = "prepared_by_designation", length = 120)
    private String preparedByDesignation;

    @Column(name = "checked_by_name", length = 200)
    private String checkedByName;

    @Column(name = "checked_by_designation", length = 120)
    private String checkedByDesignation;

    @Column(name = "executive_engineer", length = 200)
    private String executiveEngineer;

    @Column(name = "cmb_no", length = 60)
    private String cmbNo;

    @Column(name = "estimated_cost", precision = 18, scale = 2)
    private BigDecimal estimatedCost;

    @Column(name = "tendered_cost", precision = 18, scale = 2)
    private BigDecimal tenderedCost;

    @Column(name = "date_of_start")
    private LocalDate dateOfStart;

    @Column(name = "stipulated_completion")
    private LocalDate stipulatedCompletion;

    protected Agreement() {
    }

    public Agreement(UUID orgId, UUID projectId) {
        this.orgId = orgId;
        this.projectId = projectId;
    }

    /**
     * The three steps, in order, each rounded to two places as the printed analysis rounds
     * them.
     *
     * <p>Rounding at every step rather than only at the end is not a detail: the analysis of
     * rate is a printed document with a line per step, and a total that does not equal the
     * lines above it is the first thing a checker notices.</p>
     */
    public BigDecimal deriveRate(BigDecimal scheduleRate) {
        if (scheduleRate == null) {
            return null;
        }
        BigDecimal afterCoefficient = scheduleRate.multiply(dsrCoefficient)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal afterCostIndex = afterCoefficient
                .add(afterCoefficient.multiply(costIndexPct).divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP))
                .setScale(2, RoundingMode.HALF_UP);
        return afterCostIndex
                .add(afterCostIndex.multiply(tenderPct).divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getAgreementNo() {
        return agreementNo;
    }

    public void setAgreementNo(String agreementNo) {
        this.agreementNo = agreementNo;
    }

    public String getDivision() {
        return division;
    }

    public void setDivision(String division) {
        this.division = division;
    }

    public String getSubDivision() {
        return subDivision;
    }

    public void setSubDivision(String subDivision) {
        this.subDivision = subDivision;
    }

    public UUID getDsrScheduleId() {
        return dsrScheduleId;
    }

    public void setDsrScheduleId(UUID dsrScheduleId) {
        this.dsrScheduleId = dsrScheduleId;
    }

    public BigDecimal getDsrCoefficient() {
        return dsrCoefficient;
    }

    public void setDsrCoefficient(BigDecimal dsrCoefficient) {
        this.dsrCoefficient = dsrCoefficient;
    }

    public BigDecimal getCostIndexPct() {
        return costIndexPct;
    }

    public void setCostIndexPct(BigDecimal costIndexPct) {
        this.costIndexPct = costIndexPct;
    }

    public BigDecimal getTenderPct() {
        return tenderPct;
    }

    public void setTenderPct(BigDecimal tenderPct) {
        this.tenderPct = tenderPct;
    }

    public BigDecimal getDeviationLimitPct() {
        return deviationLimitPct;
    }

    public void setDeviationLimitPct(BigDecimal deviationLimitPct) {
        this.deviationLimitPct = deviationLimitPct;
    }

    public LocalDate getDateOfStart() {
        return dateOfStart;
    }

    public void setDateOfStart(LocalDate dateOfStart) {
        this.dateOfStart = dateOfStart;
    }

    public LocalDate getStipulatedCompletion() {
        return stipulatedCompletion;
    }

    public void setStipulatedCompletion(LocalDate stipulatedCompletion) {
        this.stipulatedCompletion = stipulatedCompletion;
    }

    public String getContractorName() {
        return contractorName;
    }

    public void setContractorName(String contractorName) {
        this.contractorName = contractorName;
    }

    public String getMeasuredByName() {
        return measuredByName;
    }

    public void setMeasuredByName(String measuredByName) {
        this.measuredByName = measuredByName;
    }

    public String getMeasuredByDesignation() {
        return measuredByDesignation;
    }

    public void setMeasuredByDesignation(String measuredByDesignation) {
        this.measuredByDesignation = measuredByDesignation;
    }

    public String getPreparedByName() {
        return preparedByName;
    }

    public void setPreparedByName(String preparedByName) {
        this.preparedByName = preparedByName;
    }

    public String getPreparedByDesignation() {
        return preparedByDesignation;
    }

    public void setPreparedByDesignation(String preparedByDesignation) {
        this.preparedByDesignation = preparedByDesignation;
    }

    public String getCheckedByName() {
        return checkedByName;
    }

    public void setCheckedByName(String checkedByName) {
        this.checkedByName = checkedByName;
    }

    public String getCheckedByDesignation() {
        return checkedByDesignation;
    }

    public void setCheckedByDesignation(String checkedByDesignation) {
        this.checkedByDesignation = checkedByDesignation;
    }

    public String getExecutiveEngineer() {
        return executiveEngineer;
    }

    public void setExecutiveEngineer(String executiveEngineer) {
        this.executiveEngineer = executiveEngineer;
    }

    public String getCmbNo() {
        return cmbNo;
    }

    public void setCmbNo(String cmbNo) {
        this.cmbNo = cmbNo;
    }

    public BigDecimal getEstimatedCost() {
        return estimatedCost;
    }

    public void setEstimatedCost(BigDecimal estimatedCost) {
        this.estimatedCost = estimatedCost;
    }

    public BigDecimal getTenderedCost() {
        return tenderedCost;
    }

    public void setTenderedCost(BigDecimal tenderedCost) {
        this.tenderedCost = tenderedCost;
    }
}

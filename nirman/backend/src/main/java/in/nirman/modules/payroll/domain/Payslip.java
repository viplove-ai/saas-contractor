package in.nirman.modules.payroll.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One member, one month, frozen.
 *
 * <p>Every other roll-up in this system is derived on the call — a dashboard tile, the DPR's
 * prefill, a float balance — on the rule that a stored total is a second version of the
 * truth. This is the exception, and the reason is that a payslip is not a roll-up. It is a
 * <b>document issued to a person</b>, kept three years by statute, reconciled against a
 * transfer that has already left the bank, and quite possibly folded in somebody's pocket. A
 * slip that recomputed itself would change the day an administrator corrected a salary
 * revision, and the copy in the office would stop matching the copy in his hand — which is
 * the one disagreement an employer cannot have. The daily report freezes its snapshot at
 * handover for exactly this reason; this is the same rule one register along.</p>
 *
 * <p>The name and the numbers are frozen with the figures. A member who changes his name in
 * August has not changed the name on July's payslip, because July's payslip was printed in
 * July.</p>
 *
 * <p><b>Three figures on the row are the employer's and not his</b> — the provident fund and
 * pension the employer remits, and the insurance it pays. They are on the payslip because it
 * is the only place every part of what employing somebody costs is known at once, and they
 * are outside {@code totalDeductions} and outside the net, because they were never his money.
 * The printed slip shows them under their own heading and never among the deductions.</p>
 */
@Entity
@Table(name = "payslips")
public class Payslip extends BaseEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "period_month", nullable = false, updatable = false)
    private LocalDate periodMonth;

    // ------------------------------------------------------------------ who, as at drawing

    @Column(name = "employee_name", nullable = false, length = 200)
    private String employeeName;

    @Column(name = "employee_number", length = 20)
    private String employeeNumber;

    @Column(name = "designation", length = 100)
    private String designation;

    @Column(name = "uan", length = 12)
    private String uan;

    @Column(name = "esic_number", length = 17)
    private String esicNumber;

    // ------------------------------------------------------------------ what was agreed

    @Column(name = "struct_basic", nullable = false, precision = 14, scale = 2)
    private BigDecimal structBasic = BigDecimal.ZERO;

    @Column(name = "struct_da", nullable = false, precision = 14, scale = 2)
    private BigDecimal structDa = BigDecimal.ZERO;

    @Column(name = "struct_hra", nullable = false, precision = 14, scale = 2)
    private BigDecimal structHra = BigDecimal.ZERO;

    @Column(name = "struct_conveyance", nullable = false, precision = 14, scale = 2)
    private BigDecimal structConveyance = BigDecimal.ZERO;

    @Column(name = "struct_other", nullable = false, precision = 14, scale = 2)
    private BigDecimal structOther = BigDecimal.ZERO;

    @Column(name = "struct_gross", nullable = false, precision = 14, scale = 2)
    private BigDecimal structGross = BigDecimal.ZERO;

    // ------------------------------------------------------------------ what the month came to

    @Column(name = "payable_days", nullable = false)
    private int payableDays;

    /**
     * Two decimals because half days are real and a muster records them. Everything below
     * prorates on {@code paidDays / payableDays}.
     */
    @Column(name = "paid_days", nullable = false, precision = 5, scale = 2)
    private BigDecimal paidDays = BigDecimal.ZERO;

    @Column(name = "earned_basic", nullable = false, precision = 14, scale = 2)
    private BigDecimal earnedBasic = BigDecimal.ZERO;

    @Column(name = "earned_da", nullable = false, precision = 14, scale = 2)
    private BigDecimal earnedDa = BigDecimal.ZERO;

    @Column(name = "earned_hra", nullable = false, precision = 14, scale = 2)
    private BigDecimal earnedHra = BigDecimal.ZERO;

    @Column(name = "earned_conveyance", nullable = false, precision = 14, scale = 2)
    private BigDecimal earnedConveyance = BigDecimal.ZERO;

    @Column(name = "earned_other", nullable = false, precision = 14, scale = 2)
    private BigDecimal earnedOther = BigDecimal.ZERO;

    @Column(name = "overtime_hours", nullable = false, precision = 7, scale = 2)
    private BigDecimal overtimeHours = BigDecimal.ZERO;

    @Column(name = "overtime_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal overtimeAmount = BigDecimal.ZERO;

    /**
     * Whether the amount above was typed rather than worked out.
     *
     * <p>Overtime is paid at twice the ordinary rate, so the amount normally follows from the
     * hours and nobody types it. An office that has agreed a different rate types it — and
     * this is what stops the next redraw replacing his figure with the statutory one, which
     * would make "a redraw keeps what the office typed" true of four fields and false of the
     * fifth.</p>
     */
    @Column(name = "overtime_overridden", nullable = false)
    private boolean overtimeOverridden;

    @Column(name = "total_earnings", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalEarnings = BigDecimal.ZERO;

    @Column(name = "statutory_wages", nullable = false, precision = 14, scale = 2)
    private BigDecimal statutoryWages = BigDecimal.ZERO;

    // ------------------------------------------------------------------ deducted from him

    @Column(name = "pf_wages", nullable = false, precision = 14, scale = 2)
    private BigDecimal pfWages = BigDecimal.ZERO;

    @Column(name = "pf_employee", nullable = false, precision = 14, scale = 2)
    private BigDecimal pfEmployee = BigDecimal.ZERO;

    @Column(name = "esi_wages", nullable = false, precision = 14, scale = 2)
    private BigDecimal esiWages = BigDecimal.ZERO;

    @Column(name = "esi_employee", nullable = false, precision = 14, scale = 2)
    private BigDecimal esiEmployee = BigDecimal.ZERO;

    @Column(name = "professional_tax", nullable = false, precision = 14, scale = 2)
    private BigDecimal professionalTax = BigDecimal.ZERO;

    /** Section 392 of the Income-tax Act 2025. Typed by the office, never computed here. */
    @Column(name = "tds", nullable = false, precision = 14, scale = 2)
    private BigDecimal tds = BigDecimal.ZERO;

    @Column(name = "salary_advance", nullable = false, precision = 14, scale = 2)
    private BigDecimal salaryAdvance = BigDecimal.ZERO;

    @Column(name = "other_deduction", nullable = false, precision = 14, scale = 2)
    private BigDecimal otherDeduction = BigDecimal.ZERO;

    @Column(name = "other_deduction_note", length = 200)
    private String otherDeductionNote;

    @Column(name = "total_deductions", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalDeductions = BigDecimal.ZERO;

    @Column(name = "net_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal netAmount = BigDecimal.ZERO;

    // ------------------------------------------------------------------ the employer's own

    @Column(name = "pf_employer", nullable = false, precision = 14, scale = 2)
    private BigDecimal pfEmployer = BigDecimal.ZERO;

    @Column(name = "eps_employer", nullable = false, precision = 14, scale = 2)
    private BigDecimal epsEmployer = BigDecimal.ZERO;

    @Column(name = "esi_employer", nullable = false, precision = 14, scale = 2)
    private BigDecimal esiEmployer = BigDecimal.ZERO;

    @Column(name = "remarks", length = 300)
    private String remarks;

    protected Payslip() {
    }

    public Payslip(UUID orgId, UUID runId, UUID userId, LocalDate periodMonth,
                   String employeeName) {
        this.orgId = orgId;
        this.runId = runId;
        this.userId = userId;
        this.periodMonth = periodMonth;
        this.employeeName = employeeName;
    }

    /**
     * The structure this slip is being drawn against, taken whole off the revision that
     * applied on the last day of the month.
     */
    public void takeStructure(BigDecimal basic, BigDecimal da, BigDecimal hra,
                              BigDecimal conveyance, BigDecimal other) {
        this.structBasic = money(basic);
        this.structDa = money(da);
        this.structHra = money(hra);
        this.structConveyance = money(conveyance);
        this.structOther = money(other);
        this.structGross = structBasic.add(structDa).add(structHra)
                .add(structConveyance).add(structOther);
    }

    public void takeIdentity(String employeeNumber, String designation, String uan,
                             String esicNumber) {
        this.employeeNumber = employeeNumber;
        this.designation = designation;
        this.uan = uan;
        this.esicNumber = esicNumber;
    }

    /**
     * Works the month out from the structure, the days and the statutory rules, and leaves
     * every total agreeing with its parts.
     *
     * <p>The one place the arithmetic lives. It is on the entity rather than in the service
     * because the check constraints under this row demand that earnings, deductions and net
     * all add up, and a caller that could set {@code netAmount} on its own would be a caller
     * able to write a slip the database then refuses for reasons it cannot explain.</p>
     *
     * <p>Components are prorated one at a time and the total is summed from the results,
     * rather than the gross being prorated and split: proration rounds, and a total taken
     * from the rounded parts is the total that matches the column of figures a reader adds
     * up by hand.</p>
     */
    public void recompute(int payableDays, boolean pfApplicable, boolean esiApplicable,
                          boolean pfOnFullWages, BigDecimal overtimeOverride,
                          int monthlyWageDays) {
        this.payableDays = payableDays;
        BigDecimal factor = payableDays <= 0 ? BigDecimal.ZERO
                : paidDays.divide(BigDecimal.valueOf(payableDays), 8, RoundingMode.HALF_UP);

        this.earnedBasic = prorate(structBasic, factor);
        this.earnedDa = prorate(structDa, factor);
        this.earnedHra = prorate(structHra, factor);
        this.earnedConveyance = prorate(structConveyance, factor);
        this.earnedOther = prorate(structOther, factor);

        // The full month's wage as the definition counts it, which is what an hour of
        // overtime is priced off — not this month's reduced earnings. A man who lost three
        // days and then worked a Sunday is owed the Sunday at his rate, not at a rate his
        // absence quietly cut.
        BigDecimal monthlyStatutory = structBasic.add(structDa)
                .max(money(structGross.multiply(StatutoryContributions.WAGE_FLOOR_SHARE)));
        if (overtimeOverride != null) {
            this.overtimeAmount = money(overtimeOverride);
            this.overtimeOverridden = true;
        } else if (overtimeOverridden) {
            // Left exactly where the office put it. A redraw re-prices the hours it computed
            // and does not touch the ones it was told.
            this.overtimeAmount = money(overtimeAmount);
        } else {
            this.overtimeAmount = StatutoryContributions.overtimeFor(monthlyStatutory,
                    overtimeHours, monthlyWageDays);
        }

        StatutoryContributions.Result statutory = StatutoryContributions.of(
                earnedBasic.add(earnedDa),
                earnedHra.add(earnedConveyance).add(earnedOther),
                overtimeAmount, structGross, factor,
                pfApplicable, esiApplicable, pfOnFullWages);

        this.statutoryWages = statutory.statutoryWages();
        this.pfWages = statutory.pfWages();
        this.pfEmployee = statutory.pfEmployee();
        this.pfEmployer = statutory.pfEmployer();
        this.epsEmployer = statutory.epsEmployer();
        this.esiWages = statutory.esiWages();
        this.esiEmployee = statutory.esiEmployee();
        this.esiEmployer = statutory.esiEmployer();

        this.totalEarnings = earnedBasic.add(earnedDa).add(earnedHra)
                .add(earnedConveyance).add(earnedOther).add(overtimeAmount);
        this.totalDeductions = pfEmployee.add(esiEmployee).add(professionalTax)
                .add(tds).add(salaryAdvance).add(otherDeduction);
        this.netAmount = totalEarnings.subtract(totalDeductions);
    }

    /** What employing him cost this month: what he was paid plus what was remitted for him. */
    public BigDecimal employerCost() {
        return totalEarnings.add(pfEmployer).add(epsEmployer).add(esiEmployer);
    }

    private static BigDecimal prorate(BigDecimal full, BigDecimal factor) {
        return money(full.multiply(factor));
    }

    private static BigDecimal money(BigDecimal value) {
        return StatutoryContributions.money(value);
    }

    // ------------------------------------------------------------------ the typed figures

    public void setPaidDays(BigDecimal paidDays) {
        this.paidDays = money(paidDays);
    }

    public void setOvertimeHours(BigDecimal overtimeHours) {
        this.overtimeHours = money(overtimeHours);
    }

    /**
     * Hands the overtime back to the rule.
     *
     * <p>Called when a save arrives with no amount in it, which is the office saying "work it
     * out" — and is how a figure typed by mistake is undone. Without this the override would
     * be a one-way door: clearing the box would leave the typed amount in place and look like
     * the screen had ignored the clearing.</p>
     */
    public void clearOvertimeOverride() {
        this.overtimeOverridden = false;
    }

    public void setProfessionalTax(BigDecimal professionalTax) {
        this.professionalTax = money(professionalTax);
    }

    public void setTds(BigDecimal tds) {
        this.tds = money(tds);
    }

    public void setSalaryAdvance(BigDecimal salaryAdvance) {
        this.salaryAdvance = money(salaryAdvance);
    }

    public void setOtherDeduction(BigDecimal otherDeduction, String note) {
        this.otherDeduction = money(otherDeduction);
        this.otherDeductionNote = this.otherDeduction.signum() == 0 ? null : note;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    // ------------------------------------------------------------------ getters

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getUserId() {
        return userId;
    }

    public LocalDate getPeriodMonth() {
        return periodMonth;
    }

    public String getEmployeeName() {
        return employeeName;
    }

    public String getEmployeeNumber() {
        return employeeNumber;
    }

    public String getDesignation() {
        return designation;
    }

    public String getUan() {
        return uan;
    }

    public String getEsicNumber() {
        return esicNumber;
    }

    public BigDecimal getStructBasic() {
        return structBasic;
    }

    public BigDecimal getStructDa() {
        return structDa;
    }

    public BigDecimal getStructHra() {
        return structHra;
    }

    public BigDecimal getStructConveyance() {
        return structConveyance;
    }

    public BigDecimal getStructOther() {
        return structOther;
    }

    public BigDecimal getStructGross() {
        return structGross;
    }

    public int getPayableDays() {
        return payableDays;
    }

    public BigDecimal getPaidDays() {
        return paidDays;
    }

    public BigDecimal getEarnedBasic() {
        return earnedBasic;
    }

    public BigDecimal getEarnedDa() {
        return earnedDa;
    }

    public BigDecimal getEarnedHra() {
        return earnedHra;
    }

    public BigDecimal getEarnedConveyance() {
        return earnedConveyance;
    }

    public BigDecimal getEarnedOther() {
        return earnedOther;
    }

    public BigDecimal getOvertimeHours() {
        return overtimeHours;
    }

    public BigDecimal getOvertimeAmount() {
        return overtimeAmount;
    }

    public boolean isOvertimeOverridden() {
        return overtimeOverridden;
    }

    public BigDecimal getTotalEarnings() {
        return totalEarnings;
    }

    public BigDecimal getStatutoryWages() {
        return statutoryWages;
    }

    public BigDecimal getPfWages() {
        return pfWages;
    }

    public BigDecimal getPfEmployee() {
        return pfEmployee;
    }

    public BigDecimal getEsiWages() {
        return esiWages;
    }

    public BigDecimal getEsiEmployee() {
        return esiEmployee;
    }

    public BigDecimal getProfessionalTax() {
        return professionalTax;
    }

    public BigDecimal getTds() {
        return tds;
    }

    public BigDecimal getSalaryAdvance() {
        return salaryAdvance;
    }

    public BigDecimal getOtherDeduction() {
        return otherDeduction;
    }

    public String getOtherDeductionNote() {
        return otherDeductionNote;
    }

    public BigDecimal getTotalDeductions() {
        return totalDeductions;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public BigDecimal getPfEmployer() {
        return pfEmployer;
    }

    public BigDecimal getEpsEmployer() {
        return epsEmployer;
    }

    public BigDecimal getEsiEmployer() {
        return esiEmployer;
    }

    public String getRemarks() {
        return remarks;
    }
}

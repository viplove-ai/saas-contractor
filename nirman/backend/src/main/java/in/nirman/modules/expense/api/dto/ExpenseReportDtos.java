package in.nirman.modules.expense.api.dto;

import in.nirman.modules.expense.domain.Expense;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The three cash reports docs/05 names for Phase 5. */
public final class ExpenseReportDtos {

    private ExpenseReportDtos() {
    }

    public record RegisterRow(
            UUID id,
            String expenseNumber,
            LocalDate expenseDate,
            String categoryName,
            String vendorName,
            String description,
            String billNumber,
            BigDecimal amountBeforeTax,
            BigDecimal gstAmount,
            BigDecimal totalAmount,
            BigDecimal paidAmount,
            BigDecimal payableAmount,
            Expense.Workflow workflowStatus,
            /** True when this row is inventory value rather than cost incurred. */
            boolean materialPurchase,
            /**
             * True when this row settles wages already costed through attendance, and so is
             * not cost incurred.
             *
             * <p>Not the same question as "is the head a labour disbursement". At a site
             * that lets its labour to suppliers there is no muster and nothing was costed,
             * so the supplier's bill is false here and counts as cost — which is why the
             * field is named for what it decides rather than for the flag it starts from.</p>
             */
            boolean wageSettlement) {
    }

    /**
     * The register, with the double-counting guards from docs/09 finally doing something.
     *
     * <p>{@code totalBooked} is what left the books. It is <b>not</b> the project's cost, and
     * this is the first report in the system where saying so matters. Material purchase
     * becomes inventory and is costed again when the material is issued; money handed to a
     * worker settles a wage already costed through verified attendance. Add either to
     * labour and material consumption and the project is overstated — at Kausani by most of
     * ₹4,99,528 on the labour side alone.</p>
     *
     * <p>The labour half of that is asked of the site as well as of the head. A site working
     * through labour suppliers keeps no muster, so nothing there was costed through
     * attendance and the supplier's bill belongs in {@code costIncurred}: excluding it would
     * report a site whose men cost nothing.</p>
     *
     * <p>So the register reports four figures and lets nobody merge them:
     * {@code costIncurred + materialPurchases + labourDisbursements = totalBooked}.</p>
     */
    public record ExpenseRegisterReport(
            String siteName,
            LocalDate from,
            LocalDate to,
            List<RegisterRow> rows,
            BigDecimal totalBooked,
            BigDecimal costIncurred,
            BigDecimal materialPurchases,
            BigDecimal labourDisbursements,
            BigDecimal totalPaid,
            BigDecimal totalPayable,
            String caveat) {
    }

    /**
     * @param bucket how long the bill has been outstanding: {@code 0-30}, {@code 31-60},
     *               {@code 61-90}, {@code 90+}
     */
    public record AgeingRow(
            UUID vendorId,
            String vendorName,
            String expenseNumber,
            LocalDate expenseDate,
            int daysOutstanding,
            String bucket,
            BigDecimal totalAmount,
            BigDecimal paidAmount,
            BigDecimal payableAmount) {
    }

    public record PayableAgeingReport(
            LocalDate asOf,
            List<AgeingRow> rows,
            BigDecimal totalPayable,
            BigDecimal current,
            BigDecimal days31to60,
            BigDecimal days61to90,
            BigDecimal over90) {
    }

    public record AdvanceBalanceRow(
            UUID advanceId,
            String advanceNumber,
            UUID siteId,
            String siteName,
            UUID heldByUserId,
            String heldByName,
            LocalDate advanceDate,
            int daysOutstanding,
            BigDecimal amount,
            BigDecimal adjustedAmount,
            BigDecimal returnedAmount,
            BigDecimal balanceAmount) {
    }

    public record AdvanceBalancesReport(
            LocalDate asOf,
            List<AdvanceBalanceRow> rows,
            BigDecimal totalOutstanding) {
    }
}

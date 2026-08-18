package in.nirman.modules.expense.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.expense.domain.Expense;
import in.nirman.modules.expense.domain.ExpenseSettings;
import in.nirman.modules.expense.repository.ExpenseAttachmentRepository;
import in.nirman.modules.expense.repository.ExpenseSettingsRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

/**
 * What counts as evidence that an expense happened, in one place.
 *
 * <p>The rule was written three times — at submission, at approval by a database check, and
 * again in {@code ExpenseLookupService} where the register counts the bills that are
 * missing — and the three did not agree. The check was the odd one out and it was the one
 * with no voice: it refused the row at the moment of approval with "this record conflicts
 * with one that already exists", which is not what happened and not a sentence anybody can
 * act on.</p>
 *
 * <p>Two things the check could not see, both of which the organisation had already said
 * out loud:</p>
 *
 * <ul>
 *   <li><b>The threshold is configuration.</b> {@code expense_settings.bill_required_above}
 *       exists because a great many small site purchases genuinely have no bill, and
 *       demanding a written reason on every ₹200 of cartage produces a column of the word
 *       "cash" that nobody reads. A row-level check cannot read another table, so it
 *       enforced a blanket rule the organisation had explicitly switched off.</li>
 *   <li><b>A photograph is evidence.</b> A challan with no serial number on it is still a
 *       challan, and the man at the gate photographs it. The link lives in
 *       {@code expense_attachments} — again a second table, again invisible to a check.</li>
 * </ul>
 *
 * <p>So the rule lives here, where both are visible, and V40 drops the check. It is asked at
 * submission, at revision, and once more when a decision turns the row into an approved one
 * — that last is the backstop the check used to be, and it now refuses with a sentence.</p>
 */
@Component
public class ExpenseEvidencePolicy {

    /**
     * Bill "numbers" that name no bill. A supervisor with a mandatory field and no bill in
     * his hand types one of these, and a check that accepted them would be satisfied by the
     * absence it exists to detect.
     */
    private static final Set<String> PLACEHOLDER_BILLS =
            Set.of("-", "--", "NIL", "NA", "N/A", "LOCAL", "CASH", "");

    private final ExpenseSettingsRepository settings;
    private final ExpenseAttachmentRepository billLinks;

    public ExpenseEvidencePolicy(ExpenseSettingsRepository settings,
                                 ExpenseAttachmentRepository billLinks) {
        this.settings = settings;
        this.billLinks = billLinks;
    }

    /**
     * Whether this string names a bill somebody could go and find. Static because the
     * duplicate check and the register ask it of a bare string, with no expense in hand.
     */
    public static boolean namesARealBill(String billNumber) {
        return billNumber != null && !billNumber.isBlank()
                && !PLACEHOLDER_BILLS.contains(billNumber.trim().toUpperCase());
    }

    /**
     * Above the threshold an expense needs a bill number, a photograph of the bill, or a
     * written reason there is none.
     *
     * @throws BusinessException 422 with the sentence, where the database used to answer 409
     *         with the wrong one
     */
    public void assertHasEvidence(Expense expense) {
        BigDecimal threshold = thresholdFor(expense);
        if (expense.getTotalAmount().compareTo(threshold) <= 0) {
            return;
        }
        if (namesARealBill(expense.getBillNumber())
                || billLinks.existsByExpenseId(expense.getId())) {
            return;
        }
        if (expense.getNoBillReason() == null || expense.getNoBillReason().isBlank()) {
            throw new BusinessException("expense.bill-required",
                    "Above %s an expense needs a bill number, a photograph of the bill, or a "
                            .formatted(threshold.toPlainString())
                            + "written reason there is none.");
        }
    }

    /**
     * Read against the expense's own organisation rather than the caller's. The two are the
     * same on every path today, but the approval backstop runs inside a listener that is
     * handed a record and not a request, and a policy that quietly depended on who was
     * asking would be the wrong shape for it.
     */
    private BigDecimal thresholdFor(Expense expense) {
        return settings.findByOrgId(expense.getOrgId())
                .orElseGet(() -> new ExpenseSettings(expense.getOrgId()))
                .getBillRequiredAbove();
    }
}

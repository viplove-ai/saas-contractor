package in.nirman.modules.expense.service;

import in.nirman.modules.approval.domain.Approval;
import in.nirman.modules.approval.repository.ApprovalRepository;
import in.nirman.modules.attachment.repository.AttachmentRepository;
import in.nirman.modules.expense.api.dto.ExpenseDtos.ExpenseAttachmentResponse;
import in.nirman.modules.expense.api.dto.ExpenseDtos.ExpenseResponse;
import in.nirman.modules.expense.domain.Expense;
import in.nirman.modules.expense.domain.ExpenseAttachment;
import in.nirman.modules.expense.repository.ExpenseAttachmentRepository;
import in.nirman.modules.identity.repository.UserRepository;
import in.nirman.modules.masterdata.repository.ExpenseCategoryRepository;
import in.nirman.modules.masterdata.repository.VendorRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Turns an expense into its API shape, with the names and the pending level attached.
 *
 * <p>Held apart from the service because both the expense endpoints and the settlement ones
 * need it, and because "which level is this waiting at" is a question about the approval
 * chain rather than about the expense — the screen needs it on every row, and nobody should
 * have to make a second call to find out who is sitting on their bill.</p>
 */
@Component
@Transactional(readOnly = true)
public class ExpenseResponses {

    private final ExpenseAttachmentRepository billLinks;
    private final AttachmentRepository attachments;
    private final ApprovalRepository approvals;
    private final VendorRepository vendors;
    private final ExpenseCategoryRepository categories;
    private final UserRepository users;

    public ExpenseResponses(ExpenseAttachmentRepository billLinks,
                            AttachmentRepository attachments, ApprovalRepository approvals,
                            VendorRepository vendors, ExpenseCategoryRepository categories,
                            UserRepository users) {
        this.billLinks = billLinks;
        this.attachments = attachments;
        this.approvals = approvals;
        this.vendors = vendors;
        this.categories = categories;
        this.users = users;
    }

    public ExpenseResponse toResponse(Expense expense) {
        Approval pending = approvals.findPending(ExpenseService.ENTITY_TYPE, expense.getId())
                .orElse(null);
        return new ExpenseResponse(expense.getId(), expense.getExpenseNumber(),
                expense.getSiteId(), expense.getProjectId(), expense.getExpenseDate(),
                expense.getCategoryId(), categoryName(expense.getCategoryId()),
                expense.getSubcategoryId(), expense.getVendorId(),
                vendorName(expense.getVendorId()), expense.getBoqItemId(),
                expense.getDescription(), expense.getBillNumber(), expense.getBillDate(),
                expense.getAmountBeforeTax(), expense.getGstPercent(), expense.getGstAmount(),
                expense.getTotalAmount(), expense.getPaymentMode(), expense.getPaymentStatus(),
                expense.getPaidAmount(), expense.payableAmount(), expense.getNoBillReason(),
                expense.getRefundableAmount(), expense.getRefundExpectedOn(),
                expense.getRefundedAmount(), expense.getWrittenOffAmount(),
                expense.outstandingDeposit(), expense.depositStatus(), expense.spentAmount(),
                expense.getSiteAdvanceId(),
                expense.getCostAllocation(), expense.siteCost(), expense.companyCost(),
                expense.getAllocationNote(), expense.getAllocatedAt(),
                expense.getRevision(), expense.getRevisedAt(), expense.getRevisionReason(),
                expense.getWorkflowStatus(),
                expense.getCreatedBy(), userName(expense.getCreatedBy()),
                pending == null ? null : pending.getLevel(),
                pending == null ? null : pending.getAssignedRole(),
                expense.getSubmittedAt(), expense.getApprovedAt(), expense.getRejectionReason(),
                expense.getDuplicateOfId(), expense.getRemarks(), expense.getVersion(),
                attachmentsFor(expense.getId()));
    }

    private List<ExpenseAttachmentResponse> attachmentsFor(UUID expenseId) {
        List<ExpenseAttachment> links = billLinks.findByExpenseId(expenseId);
        if (links.isEmpty()) {
            return List.of();
        }
        return links.stream().map(link -> attachments.findById(link.getAttachmentId())
                        .map(file -> new ExpenseAttachmentResponse(link.getId(),
                                link.getAttachmentId(), link.getDocType(), file.getFileName(),
                                file.getContentType(), file.getSizeBytes()))
                        .orElseGet(() -> new ExpenseAttachmentResponse(link.getId(),
                                link.getAttachmentId(), link.getDocType(), null, null, 0)))
                .toList();
    }

    private String vendorName(UUID vendorId) {
        return vendorId == null ? null
                : vendors.findById(vendorId).map(v -> v.getName()).orElse(null);
    }

    private String categoryName(UUID categoryId) {
        return categoryId == null ? null
                : categories.findById(categoryId).map(c -> c.getName()).orElse(null);
    }

    /**
     * The author's name, or nothing.
     *
     * <p>Null on the rows migrated in before auditing was switched on, and on a user since
     * removed from the organisation. The screen says "author not recorded" rather than
     * printing a bare id, because a name nobody can read is worse than an admitted gap.</p>
     */
    private String userName(UUID userId) {
        return userId == null ? null
                : users.findById(userId).map(user -> user.getFullName()).orElse(null);
    }
}

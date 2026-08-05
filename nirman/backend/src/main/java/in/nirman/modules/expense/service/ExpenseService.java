package in.nirman.modules.expense.service;

import in.nirman.common.BusinessException;
import in.nirman.common.DocumentNumberService;
import in.nirman.common.PageResponse;
import in.nirman.common.PeriodLockGuard;
import in.nirman.modules.approval.domain.Approval;
import in.nirman.modules.approval.service.ApprovalEngine;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.expense.api.dto.ExpenseDtos.AttachBillRequest;
import in.nirman.modules.expense.api.dto.ExpenseDtos.CreateExpenseRequest;
import in.nirman.modules.expense.api.dto.ExpenseDtos.DuplicateCandidate;
import in.nirman.modules.expense.api.dto.ExpenseDtos.ExpenseResponse;
import in.nirman.modules.expense.api.dto.ExpenseDtos.UpdateExpenseRequest;
import in.nirman.modules.expense.domain.Expense;
import in.nirman.modules.expense.domain.ExpenseAttachment;
import in.nirman.modules.expense.domain.ExpenseSettings;
import in.nirman.modules.expense.repository.ExpenseAttachmentRepository;
import in.nirman.modules.expense.repository.ExpenseRepository;
import in.nirman.modules.expense.repository.ExpenseSettingsRepository;
import in.nirman.modules.project.service.BoqLookup;
import in.nirman.modules.project.service.SiteLookup;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Expenses: entry, evidence, approval and voiding.
 *
 * <p>Four rules carry the weight.</p>
 *
 * <p><b>An approved expense cannot be silently edited.</b> Draft, returned and rejected rows
 * are the author's to change; anything that has been submitted is not. Correcting an
 * approved expense means voiding it and booking the replacement, which leaves both on the
 * record — the alternative is a cost figure that moves after somebody signed it.</p>
 *
 * <p><b>The same bill cannot be booked twice.</b> Checked two ways before the unique index
 * ever fires: exact vendor-and-bill-number, and a near-miss on vendor, amount and date. The
 * second is the one that catches real double entries, because the field writes "Local" or
 * "-" in the bill box for half of them and a placeholder collides with nothing (docs/09).
 * The answer is 409 <i>with the candidates</i>, and {@code force=true} plus a stated reason
 * books it anyway.</p>
 *
 * <p><b>Evidence above a threshold.</b> Above {@code billRequiredAbove} an expense needs a
 * bill number or a written explanation of why there is none. A threshold rather than a
 * blanket rule because a great many small site purchases genuinely have no bill, and
 * demanding a reason on every ₹200 of cartage produces a column of the word "cash".</p>
 *
 * <p><b>Approval belongs to the engine.</b> This service raises a chain and then stops
 * thinking about it; the status column is written by {@link ExpenseApprovalListener} on the
 * engine's event, never here (docs/09 open question 2).</p>
 */
@Service
@Transactional
public class ExpenseService {

    /** The document type the approval engine routes this by. */
    public static final String ENTITY_TYPE = "EXPENSE";

    /** How far either side of an expense date a near-miss counts as suspicious. */
    private static final int SIMILAR_WINDOW_DAYS = 7;

    /** Bill-number placeholders the field writes repeatedly. Mirrors uq_expense_vendor_bill. */
    private static final Set<String> PLACEHOLDER_BILLS =
            Set.of("-", "--", "NIL", "NA", "N/A", "LOCAL", "CASH", "");

    private final ExpenseRepository expenses;
    private final ExpenseAttachmentRepository billLinks;
    private final ExpenseSettingsRepository settings;
    private final ApprovalEngine approvals;
    private final SiteLookup sites;
    private final BoqLookup boqItems;
    private final SiteAccessGuard siteAccessGuard;
    private final PeriodLockGuard periodLockGuard;
    private final DocumentNumberService documentNumbers;
    private final ExpenseResponses responses;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public ExpenseService(ExpenseRepository expenses, ExpenseAttachmentRepository billLinks,
                          ExpenseSettingsRepository settings, ApprovalEngine approvals,
                          SiteLookup sites, BoqLookup boqItems, SiteAccessGuard siteAccessGuard,
                          PeriodLockGuard periodLockGuard, DocumentNumberService documentNumbers,
                          ExpenseResponses responses, CurrentUserProvider currentUser,
                          AuditService audit) {
        this.expenses = expenses;
        this.billLinks = billLinks;
        this.settings = settings;
        this.approvals = approvals;
        this.sites = sites;
        this.boqItems = boqItems;
        this.siteAccessGuard = siteAccessGuard;
        this.periodLockGuard = periodLockGuard;
        this.documentNumbers = documentNumbers;
        this.responses = responses;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('expense:read')")
    public PageResponse<ExpenseResponse> list(UUID siteId, UUID vendorId, UUID categoryId,
                                              Expense.Workflow status, LocalDate from,
                                              LocalDate to, Pageable pageable) {
        if (siteId != null) {
            siteAccessGuard.assertCanAccess(siteId);
        }
        boolean restricted = !currentUser.seesAllSites();
        Collection<UUID> visible = restricted ? currentUser.assignedSiteIds() : List.of();
        if (restricted && visible.isEmpty()) {
            return new PageResponse<>(List.of(), 0, pageable.getPageSize(), 0, 0, true, true);
        }
        // The matrix says A/O for a supervisor: his own records, not everything at his site.
        boolean ownOnly = ownRecordsOnly();
        return PageResponse.from(
                expenses.search(orgId(), siteId, vendorId, categoryId, status, from, to,
                        restricted, visible, ownOnly, currentUser.currentUserIdOrNull(), pageable),
                responses::toResponse);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('expense:read')")
    public ExpenseResponse get(UUID id) {
        Expense expense = require(id);
        siteAccessGuard.assertCanAccess(expense.getSiteId());
        assertOwnIfRestricted(expense);
        return responses.toResponse(expense);
    }

    // ------------------------------------------------------------------ entry

    /**
     * Books an expense as a draft. Idempotent on the client-generated id.
     *
     * @param force the author has looked at the candidates the 409 named and says this
     *              really is a separate purchase. Requires a stated reason, which goes on
     *              the record beside the expense it was compared against.
     */
    @PreAuthorize("hasAuthority('expense:create')")
    public ExpenseResponse create(CreateExpenseRequest request, boolean force) {
        siteAccessGuard.assertCanAccess(request.siteId());
        periodLockGuard.assertOpen(request.siteId(), request.expenseDate(),
                PeriodLockGuard.Module.EXPENSE);

        var existing = expenses.findByIdAndOrgId(request.id(), orgId());
        if (existing.isPresent()) {
            return responses.toResponse(existing.get());   // the offline replay
        }

        SiteLookup.SiteInfo site = sites.require(request.siteId());
        if (request.boqItemId() != null) {
            BoqLookup.BoqItemInfo item = boqItems.requireChargeable(request.boqItemId());
            if (!item.projectId().equals(site.projectId())) {
                throw new BusinessException("expense.boq-other-project",
                        "Item " + item.itemNumber() + " belongs to a different project.");
            }
        }

        BigDecimal total = totalOf(request.amountBeforeTax(), request.gstPercent());
        List<DuplicateCandidate> candidates = findDuplicates(request.vendorId(),
                request.billNumber(), total, request.expenseDate(), null);
        if (!candidates.isEmpty() && !force) {
            throw new DuplicateExpenseException(
                    "This looks like an expense already on file. Check the candidates, then "
                            + "re-send with force=true and a reason if it really is separate.",
                    candidates);
        }
        if (!candidates.isEmpty() && isBlank(request.duplicateOverrideReason())) {
            throw new BusinessException("expense.override-reason-required",
                    "Booking this despite the duplicate warning needs a reason.");
        }

        String number = documentNumbers.next(orgId(), DocumentNumberService.DocType.EXPENSE,
                request.expenseDate());
        Expense expense = new Expense(request.id(), orgId(), site.projectId(), site.id(), number,
                request.expenseDate(), request.categoryId(), request.description());
        apply(expense, request.subcategoryId(), request.vendorId(), request.boqItemId(),
                request.billNumber(), request.billDate(), request.amountBeforeTax(),
                request.gstPercent(), request.paymentMode(), request.noBillReason(),
                request.remarks());
        expense.setSiteAdvanceId(request.siteAdvanceId());
        if (!candidates.isEmpty()) {
            expense.markDuplicateOf(candidates.getFirst().id(), request.duplicateOverrideReason());
        }
        expenses.save(expense);

        audit.record(ENTITY_TYPE, expense.getId(), "CREATE", null,
                Map.of("expenseNumber", number, "siteId", site.id().toString(),
                        "totalAmount", expense.getTotalAmount(),
                        "bookedOverDuplicate", !candidates.isEmpty()),
                request.duplicateOverrideReason());
        return responses.toResponse(expense);
    }

    @PreAuthorize("hasAuthority('expense:create')")
    public ExpenseResponse update(UUID id, UpdateExpenseRequest request) {
        Expense expense = require(id);
        siteAccessGuard.assertCanAccess(expense.getSiteId());
        assertOwnIfRestricted(expense);
        periodLockGuard.assertOpen(expense.getSiteId(), expense.getExpenseDate(),
                PeriodLockGuard.Module.EXPENSE);

        if (!expense.getWorkflowStatus().isEditable()) {
            throw new BusinessException("expense.not-editable",
                    "Expense " + expense.getExpenseNumber() + " is "
                            + expense.getWorkflowStatus().name().toLowerCase().replace('_', ' ')
                            + " and can no longer be edited. Void it and book a replacement.");
        }
        if (!expense.getVersion().equals(request.version())) {
            throw new OptimisticLockingFailureException(
                    "Expense " + id + " was changed by someone else");
        }

        expense.setExpenseDate(request.expenseDate());
        expense.setCategoryId(request.categoryId());
        expense.setDescription(request.description());
        apply(expense, request.subcategoryId(), request.vendorId(), request.boqItemId(),
                request.billNumber(), request.billDate(), request.amountBeforeTax(),
                request.gstPercent(), request.paymentMode(), request.noBillReason(),
                request.remarks());

        audit.record(ENTITY_TYPE, id, "UPDATE", null,
                Map.of("expenseNumber", expense.getExpenseNumber(),
                        "totalAmount", expense.getTotalAmount()), null);
        return responses.toResponse(expense);
    }

    /**
     * Sends the expense for approval. This is where the evidence rule bites, and where the
     * engine takes over routing.
     */
    @PreAuthorize("hasAuthority('expense:create')")
    public ExpenseResponse submit(UUID id) {
        Expense expense = require(id);
        siteAccessGuard.assertCanAccess(expense.getSiteId());
        assertOwnIfRestricted(expense);
        periodLockGuard.assertOpen(expense.getSiteId(), expense.getExpenseDate(),
                PeriodLockGuard.Module.EXPENSE);

        if (!expense.getWorkflowStatus().isEditable()) {
            throw new BusinessException("expense.not-submittable",
                    "Expense " + expense.getExpenseNumber() + " is already "
                            + expense.getWorkflowStatus().name().toLowerCase().replace('_', ' ')
                            + ".");
        }
        assertHasEvidence(expense);

        expense.submit(Instant.now(), currentUser.currentUserIdOrNull());
        ApprovalEngine.Chain chain = approvals.submit(new ApprovalEngine.Request(ENTITY_TYPE,
                expense.getId(), expense.getSiteId(), expense.getTotalAmount(),
                Expense.Workflow.DRAFT.name()));

        audit.record(ENTITY_TYPE, id, "SUBMIT", null,
                Map.of("expenseNumber", expense.getExpenseNumber(),
                        "totalAmount", expense.getTotalAmount(),
                        "levels", chain.levels(), "pendingWith", chain.assignedRole()), null);
        return responses.toResponse(expense);
    }

    /**
     * The decision, taken while looking at the record rather than from the queue. Routes to
     * exactly the same engine as {@code POST /approvals/{id}/action}, so there is one code
     * path and two doors.
     */
    @PreAuthorize("hasAuthority('expense:approve:l1')")
    public ExpenseResponse decide(UUID id, Approval.Status outcome, String remarks) {
        Expense expense = require(id);
        siteAccessGuard.assertCanAccess(expense.getSiteId());
        Approval pending = approvals.requirePending(ENTITY_TYPE, id);
        approvals.act(pending.getId(), outcome, remarks);
        return responses.toResponse(expense);
    }

    /**
     * Voids an expense. Never a hard delete: an expense somebody approved and may have paid
     * is part of the record, and a row that vanishes is a row nobody can explain.
     */
    @PreAuthorize("hasAuthority('expense:create')")
    public ExpenseResponse voidExpense(UUID id, String reason) {
        Expense expense = require(id);
        siteAccessGuard.assertCanAccess(expense.getSiteId());
        if (expense.getWorkflowStatus() == Expense.Workflow.VOIDED) {
            throw new BusinessException("expense.already-voided",
                    "Expense " + expense.getExpenseNumber() + " is already void.");
        }
        // Voiding an approved-and-paid expense stays possible — the payment happened, and
        // refusing to record the void would leave the books saying the cost still stands.
        if (expense.getPaidAmount().signum() > 0 && !currentUser.isAdmin()) {
            throw BusinessException.forbidden(
                    "Cash has already gone out against this expense. Only an administrator "
                            + "can void it, and the payment stays on the record.");
        }
        approvals.cancelChain(ENTITY_TYPE, id, "expense voided");
        expense.voidExpense(Instant.now(), currentUser.currentUserIdOrNull(), reason);

        audit.record(ENTITY_TYPE, id, "VOID", null,
                Map.of("expenseNumber", expense.getExpenseNumber(),
                        "paidAmount", expense.getPaidAmount()), reason);
        return responses.toResponse(expense);
    }

    /** Links an uploaded bill photograph to the expense it evidences. */
    @PreAuthorize("hasAuthority('expense:create')")
    public ExpenseResponse attachBill(UUID id, AttachBillRequest request) {
        Expense expense = require(id);
        siteAccessGuard.assertCanAccess(expense.getSiteId());
        assertOwnIfRestricted(expense);
        if (!expense.getWorkflowStatus().isEditable()
                && !expense.getWorkflowStatus().isInFlight()) {
            throw new BusinessException("expense.not-attachable",
                    "Evidence can no longer be added to a "
                            + expense.getWorkflowStatus().name().toLowerCase() + " expense.");
        }
        if (billLinks.existsByExpenseIdAndAttachmentId(id, request.attachmentId())) {
            return responses.toResponse(expense);   // the retried upload
        }
        billLinks.save(new ExpenseAttachment(id, request.attachmentId(), request.docType()));
        audit.record(ENTITY_TYPE, id, "ATTACH", null,
                Map.of("attachmentId", request.attachmentId().toString()), null);
        return responses.toResponse(expense);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Above the threshold an expense needs a bill number or a written reason there is none —
     * and a photograph counts, because a challan with no number on it is still evidence.
     */
    private void assertHasEvidence(Expense expense) {
        ExpenseSettings config = settings();
        if (expense.getTotalAmount().compareTo(config.getBillRequiredAbove()) <= 0) {
            return;
        }
        boolean hasBillNumber = !isBlank(expense.getBillNumber())
                && !PLACEHOLDER_BILLS.contains(expense.getBillNumber().trim().toUpperCase());
        if (hasBillNumber || billLinks.existsByExpenseId(expense.getId())) {
            return;
        }
        if (isBlank(expense.getNoBillReason())) {
            throw new BusinessException("expense.bill-required",
                    "Above %s an expense needs a bill number, a photograph of the bill, or a "
                            .formatted(config.getBillRequiredAbove().toPlainString())
                            + "written reason there is none.");
        }
    }

    /**
     * Two checks, because they catch different mistakes.
     *
     * <p>The exact one — same vendor, same bill number — is what the unique index enforces,
     * run here so the caller gets a sentence. The near-miss — same vendor, same amount,
     * within a week — is what actually catches a double entry, because a bill number of
     * "Local" collides with nothing and half of them are.</p>
     */
    private List<DuplicateCandidate> findDuplicates(UUID vendorId, String billNumber,
                                                    BigDecimal total, LocalDate date,
                                                    UUID excludeId) {
        if (!settings().isDuplicateCheckEnabled()) {
            return List.of();
        }
        Map<UUID, DuplicateCandidate> found = new LinkedHashMap<>();

        if (!isBlank(billNumber)
                && !PLACEHOLDER_BILLS.contains(billNumber.trim().toUpperCase())) {
            expenses.findSameBill(orgId(), vendorId, billNumber).stream()
                    .filter(candidate -> !candidate.getId().equals(excludeId))
                    .forEach(candidate -> found.put(candidate.getId(),
                            toCandidate(candidate, "same vendor and bill number")));
        }
        expenses.findSimilar(orgId(), vendorId, total, date.minusDays(SIMILAR_WINDOW_DAYS),
                        date.plusDays(SIMILAR_WINDOW_DAYS)).stream()
                .filter(candidate -> !candidate.getId().equals(excludeId))
                .forEach(candidate -> found.putIfAbsent(candidate.getId(),
                        toCandidate(candidate, "same vendor and amount within a week")));

        return List.copyOf(found.values());
    }

    private static DuplicateCandidate toCandidate(Expense expense, String matchedOn) {
        return new DuplicateCandidate(expense.getId(), expense.getExpenseNumber(),
                expense.getExpenseDate(), expense.getDescription(), expense.getBillNumber(),
                expense.getTotalAmount(), expense.getWorkflowStatus(), matchedOn);
    }

    private void apply(Expense expense, UUID subcategoryId, UUID vendorId, UUID boqItemId,
                       String billNumber, LocalDate billDate, BigDecimal amountBeforeTax,
                       BigDecimal gstPercent, String paymentMode, String noBillReason,
                       String remarks) {
        expense.setSubcategoryId(subcategoryId);
        expense.setVendorId(vendorId);
        expense.setBoqItemId(boqItemId);
        expense.setBillNumber(emptyToNull(billNumber));
        expense.setBillDate(billDate);
        expense.priceAt(amountBeforeTax, gstPercent);
        expense.setPaymentMode(paymentMode);
        expense.setNoBillReason(emptyToNull(noBillReason));
        expense.setRemarks(remarks);
    }

    private static BigDecimal totalOf(BigDecimal amountBeforeTax, BigDecimal gstPercent) {
        BigDecimal rate = gstPercent == null ? BigDecimal.ZERO : gstPercent;
        return amountBeforeTax.add(amountBeforeTax.multiply(rate)
                .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP));
    }

    /**
     * The matrix's <b>O</b>. A supervisor sees the expenses he raised; an engineer sees
     * everything at his sites. Expressed as "holds expense:create but not the approval that
     * would make site-wide visibility part of the job".
     */
    private boolean ownRecordsOnly() {
        return !currentUser.seesAllSites()
                && !currentUser.hasPermission("expense:approve:l1");
    }

    private void assertOwnIfRestricted(Expense expense) {
        if (ownRecordsOnly()
                && !java.util.Objects.equals(expense.getCreatedBy(),
                        currentUser.currentUserIdOrNull())) {
            throw BusinessException.notFound("Expense", expense.getId());
        }
    }

    private ExpenseSettings settings() {
        return settings.findByOrgId(orgId())
                .orElseThrow(() -> new BusinessException("expense.settings-missing",
                        "Expense settings have not been configured for this organisation."));
    }

    private Expense require(UUID id) {
        return expenses.findByIdAndOrgId(id, orgId())
                .orElseThrow(() -> BusinessException.notFound("Expense", id));
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String emptyToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }
}

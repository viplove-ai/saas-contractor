package in.nirman.modules.expense.service;

import in.nirman.common.BusinessException;
import in.nirman.common.CostAllocation;
import in.nirman.common.DocumentNumberService;
import in.nirman.common.PageResponse;
import in.nirman.common.PeriodLockGuard;
import in.nirman.modules.approval.domain.Approval;
import in.nirman.modules.approval.service.ApprovalEngine;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.expense.api.dto.ExpenseDtos.AllocateExpenseRequest;
import in.nirman.modules.expense.api.dto.ExpenseDtos.AllocationSummary;
import in.nirman.modules.expense.api.dto.ExpenseDtos.AttachBillRequest;
import in.nirman.modules.expense.api.dto.ExpenseDtos.CreateExpenseRequest;
import in.nirman.modules.expense.api.dto.ExpenseDtos.DuplicateCandidate;
import in.nirman.modules.expense.api.dto.ExpenseDtos.ExpenseResponse;
import in.nirman.modules.expense.api.dto.ExpenseDtos.ReviseExpenseRequest;
import in.nirman.modules.expense.api.dto.ExpenseDtos.UpdateExpenseRequest;
import in.nirman.modules.expense.domain.Expense;
import in.nirman.modules.expense.domain.ExpenseAttachment;
import in.nirman.modules.expense.domain.ExpenseSettings;
import in.nirman.modules.expense.repository.ExpenseAttachmentRepository;
import in.nirman.modules.expense.repository.ExpenseRepository;
import in.nirman.modules.expense.repository.ExpenseSettingsRepository;
import in.nirman.modules.masterdata.domain.ExpenseCategory;
import in.nirman.modules.masterdata.repository.ExpenseCategoryRepository;
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

    private final ExpenseRepository expenses;
    private final ExpenseAttachmentRepository billLinks;
    private final ExpenseSettingsRepository settings;
    private final ApprovalEngine approvals;
    private final SiteLookup sites;
    private final BoqLookup boqItems;
    /**
     * Read straight, as {@link ExpenseResponses} and {@link ExpenseLookupService} already do:
     * master data is the reference catalogue every module reads and nothing reads back.
     */
    private final ExpenseCategoryRepository categories;
    private final SiteAccessGuard siteAccessGuard;
    private final PeriodLockGuard periodLockGuard;
    private final DocumentNumberService documentNumbers;
    private final ExpenseResponses responses;
    private final ExpenseEvidencePolicy evidence;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public ExpenseService(ExpenseRepository expenses, ExpenseAttachmentRepository billLinks,
                          ExpenseSettingsRepository settings, ApprovalEngine approvals,
                          SiteLookup sites, BoqLookup boqItems,
                          ExpenseCategoryRepository categories, SiteAccessGuard siteAccessGuard,
                          PeriodLockGuard periodLockGuard, DocumentNumberService documentNumbers,
                          ExpenseResponses responses, ExpenseEvidencePolicy evidence,
                          CurrentUserProvider currentUser, AuditService audit) {
        this.expenses = expenses;
        this.billLinks = billLinks;
        this.settings = settings;
        this.approvals = approvals;
        this.sites = sites;
        this.boqItems = boqItems;
        this.categories = categories;
        this.siteAccessGuard = siteAccessGuard;
        this.periodLockGuard = periodLockGuard;
        this.documentNumbers = documentNumbers;
        this.responses = responses;
        this.evidence = evidence;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('expense:read')")
    public PageResponse<ExpenseResponse> list(UUID siteId, UUID vendorId, UUID categoryId,
                                              Expense.Workflow status,
                                              CostAllocation allocation,
                                              Expense.PaymentStatus paymentStatus,
                                              LocalDate from, LocalDate to, Pageable pageable) {
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
                expenses.search(orgId(), siteId, vendorId, categoryId, status, allocation,
                        paymentStatus, from, to, restricted, visible, ownOnly,
                        currentUser.currentUserIdOrNull(), pageable),
                responses::toResponse);
    }

    /**
     * What the register's filter adds up to, in the figures the screen is split by.
     *
     * <p>Computed from the rows on every call. A stored total would be a second version of
     * the truth and would go stale the first time the office re-allocated a bill — which is
     * the one thing this screen exists to let it do.</p>
     *
     * <p>Void rows are out of every figure: a cost that was never incurred is carried by
     * nobody. Everything else is in, because "what is still waiting on somebody" is half of
     * what the office comes here to find out.</p>
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('expense:read')")
    public AllocationSummary summary(UUID siteId, CostAllocation allocation, LocalDate from,
                                     LocalDate to) {
        if (siteId != null) {
            siteAccessGuard.assertCanAccess(siteId);
        }
        boolean restricted = !currentUser.seesAllSites();
        Collection<UUID> visible = restricted ? currentUser.assignedSiteIds() : List.of();
        if (restricted && visible.isEmpty()) {
            return new AllocationSummary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    0, 0, 0);
        }
        List<Expense> rows = expenses.findForSummary(orgId(), siteId, allocation, from, to,
                restricted, visible, ownRecordsOnly(), currentUser.currentUserIdOrNull());

        BigDecimal booked = BigDecimal.ZERO;
        BigDecimal site = BigDecimal.ZERO;
        BigDecimal company = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal payable = BigDecimal.ZERO;
        BigDecimal deposits = BigDecimal.ZERO;
        BigDecimal depositsOut = BigDecimal.ZERO;
        int awaiting = 0;
        int undecided = 0;

        for (Expense expense : rows) {
            booked = booked.add(expense.getTotalAmount());
            site = site.add(expense.siteCost());
            company = company.add(expense.companyCost());
            paid = paid.add(expense.getPaidAmount());
            deposits = deposits.add(expense.getRefundableAmount());
            depositsOut = depositsOut.add(expense.outstandingDeposit());
            if (expense.getWorkflowStatus() == Expense.Workflow.APPROVED) {
                payable = payable.add(expense.payableAmount());
            }
            if (expense.getWorkflowStatus().isInFlight()) {
                awaiting++;
            }
            // Still carrying its head's proposal: approved without anybody looking at the
            // question, or not approved yet. Worth a number, because it is what the office
            // would otherwise have to find by reading every row.
            if (expense.getAllocatedAt() == null) {
                undecided++;
            }
        }
        return new AllocationSummary(booked, site, company, paid, payable, deposits,
                depositsOut, rows.size(), awaiting, undecided);
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
                request.refundableAmount(), request.refundExpectedOn(), request.remarks());
        expense.setSiteAdvanceId(request.siteAdvanceId());
        // The head's proposal, not a decision — the approver's is the decision. Booking it
        // here is what lets him be shown the answer already chosen for the two hundred office
        // bills, so that the question is still read on the diesel bill where it matters.
        expense.proposeAllocation(defaultAllocationFor(request.categoryId(),
                request.subcategoryId()));
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

    /**
     * Re-opens an approved expense so its author can correct it.
     *
     * <p>V1 gave an approved expense one way back: void it and book a replacement. That is
     * right when the expense should not have existed and wrong when a figure was typed badly
     * — the replacement carries a new number, so the vendor's bill and the system disagree
     * about what the record is called, and the supervisor who typed 45,000 for 4,500 learns
     * to telephone the office rather than use the screen.</p>
     *
     * <p>So: not a silent edit behind a signature, and not a void either. The approval it had
     * is cancelled, the row keeps its number, and it goes back through the same chain as a
     * first submission — what stands is what somebody signed again. Three refusals hold the
     * rest of the system still:</p>
     *
     * <ul>
     *   <li><b>Not once cash has gone out.</b> Paid and payable are computed against the
     *       total, and moving the total under a payment that has already left the bank is how
     *       a supplier's ledger stops matching his bills. That case is a void, and the
     *       payment stays on the record.</li>
     *   <li><b>Not by anybody but its author</b>, or an administrator. The office's answer to
     *       a wrong figure is to send it back; re-opening it is the field correcting itself.</li>
     *   <li><b>Not into a closed period or a closed site</b>, for the reason every other
     *       write path stops there.</li>
     * </ul>
     */
    @PreAuthorize("hasAuthority('expense:create')")
    public ExpenseResponse revise(UUID id, ReviseExpenseRequest request) {
        Expense expense = require(id);
        siteAccessGuard.assertCanAccess(expense.getSiteId());
        assertOwnIfRestricted(expense);
        assertSiteStillOpen(expense);
        periodLockGuard.assertOpen(expense.getSiteId(), expense.getExpenseDate(),
                PeriodLockGuard.Module.EXPENSE);
        periodLockGuard.assertOpen(expense.getSiteId(), request.expenseDate(),
                PeriodLockGuard.Module.EXPENSE);

        if (expense.getWorkflowStatus() != Expense.Workflow.APPROVED) {
            throw new BusinessException("expense.not-revisable",
                    "Expense " + expense.getExpenseNumber() + " is "
                            + spell(expense.getWorkflowStatus())
                            + ". Only an approved expense is re-opened this way — this one can "
                            + "still be corrected where it stands.");
        }
        if (!expense.getVersion().equals(request.version())) {
            throw new OptimisticLockingFailureException(
                    "Expense " + id + " was changed by someone else");
        }
        if (expense.getPaidAmount().signum() > 0) {
            throw new BusinessException("expense.paid-not-revisable",
                    "%s has already been paid %s. Changing what it says now would leave the "
                            .formatted(expense.getExpenseNumber(),
                                    expense.getPaidAmount().toPlainString())
                            + "supplier's ledger disagreeing with his bills — void it and book "
                            + "the replacement instead, and the payment stays on the record.");
        }
        if (!isAuthorOrAdmin(expense)) {
            throw BusinessException.forbidden(
                    "Only the person who booked " + expense.getExpenseNumber() + " may re-open "
                            + "it. Send it back to them instead, and they will correct it.");
        }

        BigDecimal wasTotal = expense.getTotalAmount();
        approvals.cancelChain(ENTITY_TYPE, id, "expense re-opened by its author");
        // The allocation goes with the amount it was a decision about: a split of ₹45,000
        // means nothing once the row says ₹4,500. Back to the head's proposal, and the
        // approver decides it again along with everything else.
        expense.revise(Instant.now(), currentUser.currentUserIdOrNull(), request.reason(),
                defaultAllocationFor(request.categoryId(), request.subcategoryId()));
        expense.setExpenseDate(request.expenseDate());
        expense.setCategoryId(request.categoryId());
        expense.setDescription(request.description());
        apply(expense, request.subcategoryId(), request.vendorId(), request.boqItemId(),
                request.billNumber(), request.billDate(), request.amountBeforeTax(),
                request.gstPercent(), request.paymentMode(), request.noBillReason(),
                request.refundableAmount(), request.refundExpectedOn(), request.remarks());
        evidence.assertHasEvidence(expense);

        expense.submit(Instant.now(), currentUser.currentUserIdOrNull());
        ApprovalEngine.Chain chain = approvals.submit(new ApprovalEngine.Request(ENTITY_TYPE,
                expense.getId(), expense.getSiteId(), expense.getTotalAmount(),
                Expense.Workflow.DRAFT.name()));

        audit.record(ENTITY_TYPE, id, "REVISE",
                Map.of("totalAmount", wasTotal),
                Map.of("expenseNumber", expense.getExpenseNumber(),
                        "totalAmount", expense.getTotalAmount(),
                        "revision", expense.getRevision(),
                        "pendingWith", chain.assignedRole()), request.reason());
        return responses.toResponse(expense);
    }

    /**
     * Whose cost an approved expense was, re-decided.
     *
     * <p>The approver answers this while the bill is in front of him and is sometimes wrong
     * about it — a month later the office reads the register and can see that the diesel was
     * the office car's. Its own permission rather than the approver's: the accountant who
     * reads the month holds no approval permission at all, and the act is a different one by
     * a different person.</p>
     *
     * <p>It stops at the site's closing. A closed site's figures have gone to the department,
     * and a classification moved afterwards moves a number somebody has already been paid
     * against.</p>
     */
    @PreAuthorize("hasAuthority('expense:allocate')")
    public ExpenseResponse allocate(UUID id, AllocateExpenseRequest request) {
        Expense expense = require(id);
        siteAccessGuard.assertCanAccess(expense.getSiteId());
        assertSiteStillOpen(expense);
        periodLockGuard.assertOpen(expense.getSiteId(), expense.getExpenseDate(),
                PeriodLockGuard.Module.EXPENSE);

        if (expense.getWorkflowStatus() == Expense.Workflow.VOIDED) {
            throw new BusinessException("expense.voided-not-allocatable",
                    "Expense " + expense.getExpenseNumber() + " is void. Nobody carries a cost "
                            + "that was never incurred.");
        }
        CostAllocation was = expense.getCostAllocation();
        applyAllocation(expense, request.allocation(), request.siteShare(), request.note());

        audit.record(ENTITY_TYPE, id, "ALLOCATE",
                Map.of("costAllocation", was.name()),
                Map.of("expenseNumber", expense.getExpenseNumber(),
                        "costAllocation", expense.getCostAllocation().name(),
                        "siteCost", expense.siteCost(),
                        "companyCost", expense.companyCost()), request.note());
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
                request.refundableAmount(), request.refundExpectedOn(), request.remarks());

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
        evidence.assertHasEvidence(expense);

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
     *
     * <p><b>And whose cost it is, in the same call.</b> Approving the money and saying whose
     * money it was are one act by one person at one moment; two requests would leave a window
     * in which an approved expense is charged to nobody, and a second screen for the approver
     * who did not come back. It needs no permission of its own for the same reason — deciding
     * to spend the company's money and deciding that it was the company's are the same
     * question, and an organisation able to grant one and withhold the other would leave an
     * approver required to answer something he is not allowed to answer.</p>
     *
     * <p>An allocation is only taken on an approval. A rejection and a return decide nothing
     * about a cost that is not being incurred, and the row keeps its head's proposal for
     * whenever it comes back round.</p>
     */
    @PreAuthorize("hasAuthority('expense:approve:l1')")
    public ExpenseResponse decide(UUID id, Approval.Status outcome, String remarks,
                                  CostAllocation allocation, BigDecimal siteShare,
                                  String allocationNote) {
        Expense expense = require(id);
        siteAccessGuard.assertCanAccess(expense.getSiteId());
        Approval pending = approvals.requirePending(ENTITY_TYPE, id);
        if (outcome == Approval.Status.APPROVED && allocation != null) {
            applyAllocation(expense, allocation, siteShare, allocationNote);
        }
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
     * The allocation rules, in one place because two doors reach them.
     *
     * <p>The split is refused at both ends of the range rather than clamped: a split giving
     * the site the whole bill is {@code SITE} and one giving it none is {@code COMPANY}, and
     * two spellings of one fact make a register that disagrees with itself.</p>
     *
     * <p>The other refusal is the one that keeps the older invariant true. A material
     * purchase becomes stock in <i>this site's</i> store and a wage payment settles a wage
     * <i>this site's</i> attendance already counted; charging half of either to the company
     * would leave the stock ledger and the muster roll answering a question the expense
     * register answers differently. Those two heads carry the whole of their amount at the
     * site, and the way to make one an overhead is to book it under a head that is one.</p>
     */
    private void applyAllocation(Expense expense, CostAllocation allocation,
                                 BigDecimal siteShare, String note) {
        if (allocation == CostAllocation.SPLIT
                && (siteShare == null || siteShare.signum() <= 0
                        || siteShare.compareTo(expense.getTotalAmount()) >= 0)) {
            throw new BusinessException("expense.split-share",
                    "A split needs the site's part, and it has to be more than nothing and "
                            + "less than the whole %s. All of it is a site cost; none of it is "
                                    .formatted(expense.getTotalAmount().toPlainString())
                            + "a company cost.");
        }
        /*
          A deposit is not split. The refundable part of a bill is money the company placed
          and will get back in one piece from one payer, and a split would be two answers to
          whose refund it is when it arrives — the same disagreement the split's own bounds
          are refused for. SITE and COMPANY both work: the office's own meter security is the
          company's deposit and the site's is the site's, and either way the whole of it goes
          back where the whole of it came from.
        */
        if (allocation == CostAllocation.SPLIT
                && expense.getRefundableAmount().signum() > 0) {
            throw new BusinessException("expense.deposit-not-splittable",
                    "%s carries a refundable deposit of %s, and a deposit comes back in one "
                            .formatted(expense.getExpenseNumber(),
                                    expense.getRefundableAmount().toPlainString())
                            + "piece from one payer. Charge the bill to the site or to the "
                            + "company; splitting it would leave two answers to whose refund "
                            + "it is.");
        }
        if (allocation != CostAllocation.SITE && isCostedElsewhere(expense)) {
            throw new BusinessException("expense.allocation-not-shareable",
                    "%s is booked under a head whose value is counted at the site — material "
                            .formatted(expense.getExpenseNumber())
                            + "purchases become that store's stock, and wage payments settle "
                            + "wages its attendance has already counted. Book it under a "
                            + "company head instead of splitting it.");
        }
        expense.allocate(allocation, siteShare, emptyToNull(note), Instant.now(),
                currentUser.currentUserIdOrNull());
    }

    /** Material purchase or wage disbursement: value that another register already carries. */
    private boolean isCostedElsewhere(Expense expense) {
        ExpenseCategory head = headOf(expense.getCategoryId(), expense.getSubcategoryId());
        return head != null && (head.isMaterialPurchase() || head.isLabourPayment());
    }

    /**
     * The head's proposal. The subcategory's where there is one, on the precedence
     * {@code ExpenseLookupService} already applies — "Worker Wage Payment" carries the flags,
     * its parent "Labour" does not, because not everything under Labour is a disbursement.
     */
    private CostAllocation defaultAllocationFor(UUID categoryId, UUID subcategoryId) {
        ExpenseCategory head = headOf(categoryId, subcategoryId);
        return head == null ? CostAllocation.SITE : head.getDefaultAllocation();
    }

    private ExpenseCategory headOf(UUID categoryId, UUID subcategoryId) {
        if (subcategoryId != null) {
            ExpenseCategory sub = categories.findById(subcategoryId).orElse(null);
            if (sub != null) {
                return sub;
            }
        }
        return categoryId == null ? null : categories.findById(categoryId).orElse(null);
    }

    /**
     * A closed site takes no more corrections.
     *
     * <p>Its figures have been reported to the department and paid against; a cost moved off
     * it afterwards moves a number somebody has already been paid for. {@code isLiveInOrg}
     * asks exactly this and asks it without the assignment check, which is right here — the
     * caller has already been through {@link SiteAccessGuard}.</p>
     */
    private void assertSiteStillOpen(Expense expense) {
        if (!sites.isLiveInOrg(expense.getSiteId())) {
            throw new BusinessException("expense.site-closed",
                    "The site this expense was booked to is closed. Its figures have gone to "
                            + "the department, and nothing on them moves afterwards.");
        }
    }

    /** The author, or the administrator who has to be able to unstick a site he is not on. */
    private boolean isAuthorOrAdmin(Expense expense) {
        return currentUser.isAdmin()
                || java.util.Objects.equals(expense.getCreatedBy(),
                        currentUser.currentUserIdOrNull());
    }

    private static String spell(Expense.Workflow status) {
        return status.name().toLowerCase().replace('_', ' ');
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

        if (ExpenseEvidencePolicy.namesARealBill(billNumber)) {
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
                       BigDecimal refundableAmount, LocalDate refundExpectedOn,
                       String remarks) {
        expense.setSubcategoryId(subcategoryId);
        expense.setVendorId(vendorId);
        expense.setBoqItemId(boqItemId);
        expense.setBillNumber(emptyToNull(billNumber));
        expense.setBillDate(billDate);
        expense.priceAt(amountBeforeTax, gstPercent);
        expense.setPaymentMode(paymentMode);
        expense.setNoBillReason(emptyToNull(noBillReason));
        applyDeposit(expense, refundableAmount, refundExpectedOn);
        expense.setRemarks(remarks);
    }

    /**
     * How much of the bill is a deposit, checked here so the caller gets a sentence.
     *
     * <p>{@code ck_expense_refundable_within_total} is behind this and would answer a
     * repriced bill with 23514, which Spring maps to a 409 and the handler spells "this
     * record conflicts with one that already exists" — the exact failure V40 went and fixed
     * in the evidence rule. Nothing conflicts with anything: a ₹12,000 deposit has been left
     * standing on a bill somebody has just corrected to ₹4,500, and the person who did it is
     * the one who can say which figure was wrong.</p>
     *
     * <p>It is refused rather than clamped, and refused below what has already come back:
     * shrinking a deposit under its own settlements would leave the register reporting money
     * received against a deposit nobody placed.</p>
     */
    private void applyDeposit(Expense expense, BigDecimal refundable, LocalDate expectedOn) {
        BigDecimal amount = refundable == null ? BigDecimal.ZERO : refundable;
        if (amount.compareTo(expense.getTotalAmount()) > 0) {
            throw new BusinessException("expense.deposit-above-total",
                    "A refundable deposit of %s is more than the bill it came on (%s). It is "
                            .formatted(amount.toPlainString(),
                                    expense.getTotalAmount().toPlainString())
                            + "part of the bill, never more than it.");
        }
        BigDecimal settled = expense.getRefundedAmount().add(expense.getWrittenOffAmount());
        if (amount.compareTo(settled) < 0) {
            throw new BusinessException("expense.deposit-below-settled",
                    "%s of the deposit on %s has already been settled. The deposit cannot be "
                            .formatted(settled.toPlainString(), expense.getExpenseNumber())
                            + "corrected below what has come back.");
        }
        expense.setDeposit(amount, expectedOn);
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

    /**
     * The org's expense policy, or the default one if nobody has written a row yet.
     *
     * <p>This used to throw, and the effect was that an organisation whose settings row had
     * never been inserted could not book an expense at all — the draft save is the first thing
     * that reads the row, and it reads it only to ask whether duplicate checking is on, so the
     * author was told "expense settings have not been configured" about a setting no screen in
     * the app creates. The row exists nowhere but the dev seed, which the prod profile never
     * loads. Both fields read here have a default declared twice already (the column defaults
     * in V1 and the field initialisers on the entity), so returning them is the same answer an
     * inserted row would have given. V28 backfills the row for organisations that lack it;
     * this is what keeps a future organisation from meeting the same wall on its first
     * expense.</p>
     */
    private ExpenseSettings settings() {
        return settings.findByOrgId(orgId()).orElseGet(() -> new ExpenseSettings(orgId()));
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

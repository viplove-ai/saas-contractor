package in.nirman.modules.expense.repository;

import in.nirman.common.CostAllocation;
import in.nirman.modules.expense.domain.Expense;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExpenseRepository extends JpaRepository<Expense, UUID> {

    Optional<Expense> findByIdAndOrgId(UUID id, UUID orgId);

    List<Expense> findByIdInAndOrgId(Collection<UUID> ids, UUID orgId);

    /**
     * The duplicate check, run before the unique index can fire so the caller gets a
     * sentence and a list of candidates rather than a constraint violation.
     *
     * <p>Vendorless expenses are the case that matters. {@code vendor_id} is nullable and
     * NULLs compare as distinct in Postgres, so a naive query never catches two cash
     * purchases with the same bill number — and most site expenses have no vendor. Hence the
     * explicit null handling on both sides rather than a plain equality.</p>
     */
    @Query("""
            SELECT e FROM Expense e
            WHERE e.orgId = :orgId
              AND ((:vendorId IS NULL AND e.vendorId IS NULL) OR e.vendorId = :vendorId)
              AND upper(trim(e.billNumber)) = upper(trim(:billNumber))
              AND e.workflowStatus NOT IN (
                    in.nirman.modules.expense.domain.Expense$Workflow.VOIDED,
                    in.nirman.modules.expense.domain.Expense$Workflow.REJECTED)
            """)
    List<Expense> findSameBill(@Param("orgId") UUID orgId,
                               @Param("vendorId") UUID vendorId,
                               @Param("billNumber") String billNumber);

    /**
     * The near-miss check the index cannot do: same vendor, same amount, within a few days,
     * whatever the bill number says. This is what catches the genuine double-entry, because
     * the field writes "Local" in the bill box for half of them and a placeholder collides
     * with nothing.
     */
    @Query("""
            SELECT e FROM Expense e
            WHERE e.orgId = :orgId
              AND ((:vendorId IS NULL AND e.vendorId IS NULL) OR e.vendorId = :vendorId)
              AND e.totalAmount = :amount
              AND e.expenseDate BETWEEN :from AND :to
              AND e.workflowStatus NOT IN (
                    in.nirman.modules.expense.domain.Expense$Workflow.VOIDED,
                    in.nirman.modules.expense.domain.Expense$Workflow.REJECTED)
            """)
    List<Expense> findSimilar(@Param("orgId") UUID orgId,
                              @Param("vendorId") UUID vendorId,
                              @Param("amount") BigDecimal amount,
                              @Param("from") LocalDate from,
                              @Param("to") LocalDate to);

    /**
     * {@code ownRecordsOnly} is the matrix's <b>O</b>: a supervisor sees the expenses he
     * raised, not everything booked at his site.
     *
     * <p>The date bounds are written as {@code COALESCE(:from, e.expenseDate)} rather than as
     * {@code :from IS NULL OR …}. An omitted bound in the second form reaches Postgres as a
     * parameter that appears nowhere but in its own null check, and the server has nothing to
     * infer its type from — "could not determine data type of parameter $6", at prepare time,
     * whatever the value. Coalescing it against the column types it, and an absent bound
     * compares the row's own date to itself.</p>
     */
    @Query("""
            SELECT e FROM Expense e
            WHERE e.orgId = :orgId
              AND (:siteId IS NULL OR e.siteId = :siteId)
              AND (:vendorId IS NULL OR e.vendorId = :vendorId)
              AND (:categoryId IS NULL OR e.categoryId = :categoryId)
              AND (:status IS NULL OR e.workflowStatus = :status)
              AND (:allocation IS NULL OR e.costAllocation = :allocation)
              AND (:paymentStatus IS NULL OR e.paymentStatus = :paymentStatus)
              AND e.expenseDate >= COALESCE(:from, e.expenseDate)
              AND e.expenseDate <= COALESCE(:to, e.expenseDate)
              AND (:restricted = false OR e.siteId IN :siteIds)
              AND (:ownRecordsOnly = false OR e.createdBy = :currentUserId)
            """)
    Page<Expense> search(@Param("orgId") UUID orgId,
                         @Param("siteId") UUID siteId,
                         @Param("vendorId") UUID vendorId,
                         @Param("categoryId") UUID categoryId,
                         @Param("status") Expense.Workflow status,
                         @Param("allocation") CostAllocation allocation,
                         @Param("paymentStatus") Expense.PaymentStatus paymentStatus,
                         @Param("from") LocalDate from,
                         @Param("to") LocalDate to,
                         @Param("restricted") boolean restricted,
                         @Param("siteIds") Collection<UUID> siteIds,
                         @Param("ownRecordsOnly") boolean ownRecordsOnly,
                         @Param("currentUserId") UUID currentUserId,
                         Pageable pageable);

    /**
     * The register's totals: the same rows the page shows, unpaged and without the void ones.
     *
     * <p>Unpaged on purpose — a total over the twenty-five rows somebody happens to be looking
     * at is not a total, and a screen that labels it one is worse than a screen with no total
     * at all. The filter is a month and a site in practice, which is hundreds of rows.</p>
     */
    @Query("""
            SELECT e FROM Expense e
            WHERE e.orgId = :orgId
              AND (:siteId IS NULL OR e.siteId = :siteId)
              AND (:allocation IS NULL OR e.costAllocation = :allocation)
              AND e.expenseDate >= COALESCE(:from, e.expenseDate)
              AND e.expenseDate <= COALESCE(:to, e.expenseDate)
              AND (:restricted = false OR e.siteId IN :siteIds)
              AND (:ownRecordsOnly = false OR e.createdBy = :currentUserId)
              AND e.workflowStatus <> in.nirman.modules.expense.domain.Expense$Workflow.VOIDED
            """)
    List<Expense> findForSummary(@Param("orgId") UUID orgId,
                                 @Param("siteId") UUID siteId,
                                 @Param("allocation") CostAllocation allocation,
                                 @Param("from") LocalDate from,
                                 @Param("to") LocalDate to,
                                 @Param("restricted") boolean restricted,
                                 @Param("siteIds") Collection<UUID> siteIds,
                                 @Param("ownRecordsOnly") boolean ownRecordsOnly,
                                 @Param("currentUserId") UUID currentUserId);

    /** Approved expenses over a period, for the register and the cost roll-up. */
    @Query("""
            SELECT e FROM Expense e
            WHERE e.orgId = :orgId
              AND (:siteId IS NULL OR e.siteId = :siteId)
              AND e.expenseDate BETWEEN :from AND :to
              AND e.workflowStatus <> in.nirman.modules.expense.domain.Expense$Workflow.VOIDED
            ORDER BY e.expenseDate ASC, e.expenseNumber ASC
            """)
    List<Expense> findForPeriod(@Param("orgId") UUID orgId,
                                @Param("siteId") UUID siteId,
                                @Param("from") LocalDate from,
                                @Param("to") LocalDate to);

    /** Everything still owed, for the ageing report and vendor balances. */
    @Query("""
            SELECT e FROM Expense e
            WHERE e.orgId = :orgId
              AND e.workflowStatus = in.nirman.modules.expense.domain.Expense$Workflow.APPROVED
              AND e.paidAmount < e.totalAmount
              AND (:vendorId IS NULL OR e.vendorId = :vendorId)
            ORDER BY e.expenseDate ASC
            """)
    List<Expense> findOutstanding(@Param("orgId") UUID orgId, @Param("vendorId") UUID vendorId);
}

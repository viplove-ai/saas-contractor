package in.nirman.modules.expense.repository;

import in.nirman.modules.expense.domain.ExpenseRefund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ExpenseRefundRepository extends JpaRepository<ExpenseRefund, UUID> {

    List<ExpenseRefund> findByExpenseIdOrderBySettledOnAscCreatedAtAsc(UUID expenseId);

    /** Every settlement behind a page of deposits, in one question rather than one per row. */
    List<ExpenseRefund> findByExpenseIdInOrderBySettledOnAsc(Collection<UUID> expenseIds);
}

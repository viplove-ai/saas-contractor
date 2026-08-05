package in.nirman.modules.expense.repository;

import in.nirman.modules.expense.domain.AdvanceSettlementExpense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AdvanceSettlementExpenseRepository
        extends JpaRepository<AdvanceSettlementExpense, AdvanceSettlementExpense.Key> {

    @Query("SELECT l FROM AdvanceSettlementExpense l WHERE l.id.settlementId = :settlementId")
    List<AdvanceSettlementExpense> findBySettlement(@Param("settlementId") UUID settlementId);

    /**
     * Whether a bill has already been claimed against some float. The unique index
     * {@code uq_ase_expense_settled_once} is the backstop; this is what turns a second claim
     * into a sentence rather than a constraint violation.
     */
    @Query("SELECT count(l) > 0 FROM AdvanceSettlementExpense l WHERE l.id.expenseId = :expenseId")
    boolean isAlreadySettled(@Param("expenseId") UUID expenseId);
}

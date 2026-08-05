package in.nirman.modules.expense.repository;

import in.nirman.modules.expense.domain.ExpenseAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExpenseAttachmentRepository extends JpaRepository<ExpenseAttachment, UUID> {

    List<ExpenseAttachment> findByExpenseId(UUID expenseId);

    boolean existsByExpenseId(UUID expenseId);

    boolean existsByExpenseIdAndAttachmentId(UUID expenseId, UUID attachmentId);
}

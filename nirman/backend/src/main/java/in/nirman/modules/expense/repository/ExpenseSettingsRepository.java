package in.nirman.modules.expense.repository;

import in.nirman.modules.expense.domain.ExpenseSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ExpenseSettingsRepository extends JpaRepository<ExpenseSettings, UUID> {

    Optional<ExpenseSettings> findByOrgId(UUID orgId);
}

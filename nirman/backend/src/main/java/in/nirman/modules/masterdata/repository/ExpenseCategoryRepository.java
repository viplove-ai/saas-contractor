package in.nirman.modules.masterdata.repository;

import in.nirman.modules.masterdata.domain.ExpenseCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategory, UUID> {

    List<ExpenseCategory> findByOrgIdOrderBySortOrderAscCodeAsc(UUID orgId);

    boolean existsByOrgIdAndCode(UUID orgId, String code);
}

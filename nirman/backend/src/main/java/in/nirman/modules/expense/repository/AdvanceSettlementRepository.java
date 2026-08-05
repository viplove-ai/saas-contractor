package in.nirman.modules.expense.repository;

import in.nirman.modules.expense.domain.AdvanceSettlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdvanceSettlementRepository extends JpaRepository<AdvanceSettlement, UUID> {

    Optional<AdvanceSettlement> findByIdAndOrgId(UUID id, UUID orgId);

    List<AdvanceSettlement> findByAdvanceIdOrderBySettlementDateAsc(UUID advanceId);
}

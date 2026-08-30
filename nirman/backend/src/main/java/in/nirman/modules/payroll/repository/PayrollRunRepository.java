package in.nirman.modules.payroll.repository;

import in.nirman.modules.payroll.domain.PayrollRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PayrollRunRepository extends JpaRepository<PayrollRun, UUID> {

    Optional<PayrollRun> findByIdAndOrgId(UUID id, UUID orgId);

    Optional<PayrollRun> findByOrgIdAndPeriodMonth(UUID orgId, LocalDate periodMonth);

    /** Newest month first — an office opens this screen to look at the month just gone. */
    List<PayrollRun> findByOrgIdOrderByPeriodMonthDesc(UUID orgId);
}

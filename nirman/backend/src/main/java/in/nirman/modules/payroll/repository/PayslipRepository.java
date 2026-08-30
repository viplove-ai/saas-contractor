package in.nirman.modules.payroll.repository;

import in.nirman.modules.payroll.domain.Payslip;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PayslipRepository extends JpaRepository<Payslip, UUID> {

    Optional<Payslip> findByIdAndOrgId(UUID id, UUID orgId);

    /** One run's slips, in the order the register prints them. */
    List<Payslip> findByRunIdOrderByEmployeeNameAsc(UUID runId);

    Optional<Payslip> findByRunIdAndUserId(UUID runId, UUID userId);

    /** One member's own slips, newest month first. */
    List<Payslip> findByOrgIdAndUserIdOrderByPeriodMonthDesc(UUID orgId, UUID userId);
}

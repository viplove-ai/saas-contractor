package in.nirman.modules.approval.repository;

import in.nirman.modules.approval.domain.ApprovalRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApprovalRuleRepository extends JpaRepository<ApprovalRule, UUID> {

    List<ApprovalRule> findByOrgIdAndEntityTypeAndActiveTrueOrderByLevelAsc(UUID orgId,
                                                                            String entityType);

    List<ApprovalRule> findByOrgIdOrderByEntityTypeAscLevelAsc(UUID orgId);
}

package in.nirman.modules.treasury.repository;

import in.nirman.modules.treasury.domain.ProjectSecurity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectSecurityRepository extends JpaRepository<ProjectSecurity, UUID> {

    Optional<ProjectSecurity> findByIdAndOrgId(UUID id, UUID orgId);

    List<ProjectSecurity> findByOrgIdAndProjectIdOrderBySecurityTypeAscCreatedAtAsc(
            UUID orgId, UUID projectId);

    /**
     * The whole register, for the treasury dashboard.
     *
     * <p>Unpaged on purpose. Every figure on that screen is an aggregate over every deposit the
     * company holds, and a page of them would answer "how much is blocked" with the first
     * twenty-five rows' worth. The register grows by a handful of rows per contract and stays
     * in the low thousands for a contractor's whole life.</p>
     */
    List<ProjectSecurity> findByOrgId(UUID orgId);

    boolean existsByOrgIdAndProjectIdAndSecurityType(
            UUID orgId, UUID projectId, ProjectSecurity.Type securityType);
}

package in.nirman.modules.masterdata.repository;

import in.nirman.modules.masterdata.domain.SkillCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SkillCategoryRepository extends JpaRepository<SkillCategory, UUID> {

    List<SkillCategory> findByOrgIdOrderByCode(UUID orgId);

    boolean existsByOrgIdAndCode(UUID orgId, String code);
}

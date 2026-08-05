package in.nirman.modules.masterdata.repository;

import in.nirman.modules.masterdata.domain.MaterialCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MaterialCategoryRepository extends JpaRepository<MaterialCategory, UUID> {

    List<MaterialCategory> findByOrgIdOrderByCode(UUID orgId);

    boolean existsByOrgIdAndCode(UUID orgId, String code);
}

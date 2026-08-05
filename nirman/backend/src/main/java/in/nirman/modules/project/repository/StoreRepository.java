package in.nirman.modules.project.repository;

import in.nirman.modules.project.domain.Store;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface StoreRepository extends JpaRepository<Store, UUID> {

    List<Store> findBySiteIdOrderByCode(UUID siteId);

    List<Store> findBySiteIdInOrderByCode(Collection<UUID> siteIds);

    boolean existsByOrgIdAndCode(UUID orgId, String code);

    long countBySiteIdInAndActiveTrue(List<UUID> siteIds);
}

package in.nirman.modules.inventory.repository;

import in.nirman.modules.inventory.domain.PhysicalStockCount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface PhysicalStockCountRepository extends JpaRepository<PhysicalStockCount, UUID> {

    Optional<PhysicalStockCount> findByIdAndOrgId(UUID id, UUID orgId);

    @Query("""
            SELECT c FROM PhysicalStockCount c
            WHERE c.orgId = :orgId
              AND (:storeId IS NULL OR c.storeId = :storeId)
              AND (:restricted = false OR c.storeId IN :storeIds)
            """)
    Page<PhysicalStockCount> search(@Param("orgId") UUID orgId,
                                    @Param("storeId") UUID storeId,
                                    @Param("restricted") boolean restricted,
                                    @Param("storeIds") Collection<UUID> storeIds,
                                    Pageable pageable);
}

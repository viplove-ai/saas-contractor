package in.nirman.modules.inventory.repository;

import in.nirman.modules.inventory.domain.StockCorrectionRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockCorrectionRequestRepository
        extends JpaRepository<StockCorrectionRequest, UUID> {

    Optional<StockCorrectionRequest> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * The queue, narrowed however the caller asked and to whatever they may see.
     *
     * <p>Decided rows are in it. The storekeeper who counted the shed is owed the answer, and
     * a request that vanishes once it is refused is a count nobody bothers to type twice.</p>
     */
    @Query("""
            SELECT c FROM StockCorrectionRequest c
            WHERE c.orgId = :orgId
              AND (:siteId IS NULL OR c.siteId = :siteId)
              AND (:storeId IS NULL OR c.storeId = :storeId)
              AND (:status IS NULL OR c.status = :status)
              AND (:restricted = false OR c.siteId IN :siteIds)
            ORDER BY c.status ASC, c.createdAt DESC
            """)
    List<StockCorrectionRequest> search(@Param("orgId") UUID orgId,
                                        @Param("siteId") UUID siteId,
                                        @Param("storeId") UUID storeId,
                                        @Param("status") StockCorrectionRequest.Status status,
                                        @Param("restricted") boolean restricted,
                                        @Param("siteIds") Collection<UUID> siteIds);

    /**
     * What is already waiting on this store and material.
     *
     * <p>Two open requests for one figure is how the office accepts both and writes the
     * correction on twice — the second one having been raised because the first had not been
     * answered yet.</p>
     */
    List<StockCorrectionRequest> findByOrgIdAndStoreIdAndMaterialIdAndStatus(
            UUID orgId, UUID storeId, UUID materialId, StockCorrectionRequest.Status status);
}

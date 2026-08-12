package in.nirman.modules.inventory.repository;

import in.nirman.modules.inventory.domain.SiteEquipment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SiteEquipmentRepository extends JpaRepository<SiteEquipment, UUID> {

    Optional<SiteEquipment> findByIdAndOrgIdAndDeletedAtIsNull(UUID id, UUID orgId);

    /** For the offline replay: the same entry arriving twice is one machine. */
    Optional<SiteEquipment> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * The register, narrowed however the caller asked and to whatever they may see.
     *
     * <p>Rejected rows are in it. Somebody entered the machine and is owed the answer, and a
     * row that quietly disappears gets entered again next week.</p>
     */
    @Query("""
            SELECT e FROM SiteEquipment e
            WHERE e.orgId = :orgId
              AND e.deletedAt IS NULL
              AND (:siteId IS NULL OR e.siteId = :siteId)
              AND (:storeId IS NULL OR e.storeId = :storeId)
              AND (:status IS NULL OR e.status = :status)
              AND (:restricted = false OR e.siteId IN :siteIds)
            ORDER BY e.status ASC, e.name ASC
            """)
    List<SiteEquipment> search(@Param("orgId") UUID orgId,
                               @Param("siteId") UUID siteId,
                               @Param("storeId") UUID storeId,
                               @Param("status") SiteEquipment.Status status,
                               @Param("restricted") boolean restricted,
                               @Param("siteIds") Collection<UUID> siteIds);

    /**
     * The same machine already on the register, by the number painted on it.
     *
     * <p>Case- and space-insensitive, mirroring {@code uq_equipment_asset_code}: two rows
     * carrying one registration are two machines that are one machine, and the register is
     * then wrong about how much plant is standing at the site.</p>
     */
    @Query("""
            SELECT e FROM SiteEquipment e
            WHERE e.orgId = :orgId
              AND e.deletedAt IS NULL
              AND e.assetCode IS NOT NULL
              AND upper(trim(e.assetCode)) = upper(trim(:assetCode))
              AND (:excludeId IS NULL OR e.id <> :excludeId)
            """)
    List<SiteEquipment> findByAssetCode(@Param("orgId") UUID orgId,
                                        @Param("assetCode") String assetCode,
                                        @Param("excludeId") UUID excludeId);
}

package in.nirman.modules.masterdata.repository;

import in.nirman.modules.masterdata.domain.Material;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MaterialRepository extends JpaRepository<Material, UUID> {

    Optional<Material> findByIdAndOrgIdAndDeletedAtIsNull(UUID id, UUID orgId);

    boolean existsByOrgIdAndCode(UUID orgId, String code);

    /**
     * The same material under the name somebody typed at the gate.
     *
     * <p>Case- and space-insensitive, because "cement opc 43" and "Cement OPC 43" are one
     * material and two rows would split its stock into two balances that never add up.
     * Oldest first: if the catalogue already holds duplicates, the established one wins.</p>
     */
    @Query("""
            SELECT m FROM Material m
            WHERE m.orgId = :orgId AND m.deletedAt IS NULL
              AND lower(trim(m.name)) = lower(trim(:name))
            ORDER BY m.createdAt ASC
            """)
    List<Material> findByName(@Param("orgId") UUID orgId, @Param("name") String name);

    /** {@code CAST(:q AS string)} for the reason spelled out in {@link LabourContractorRepository}. */
    @Query("""
            SELECT m FROM Material m
            WHERE m.orgId = :orgId AND m.deletedAt IS NULL
              AND (:categoryId IS NULL OR m.categoryId = :categoryId)
              AND (:active IS NULL OR m.active = :active)
              AND (CAST(:q AS string) IS NULL
                   OR lower(m.name) LIKE lower(concat('%', CAST(:q AS string), '%'))
                   OR lower(m.code) LIKE lower(concat('%', CAST(:q AS string), '%')))
            """)
    Page<Material> search(@Param("orgId") UUID orgId, @Param("categoryId") UUID categoryId,
                          @Param("active") Boolean active, @Param("q") String q, Pageable pageable);
}

package in.nirman.modules.masterdata.repository;

import in.nirman.modules.masterdata.domain.Vendor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface VendorRepository extends JpaRepository<Vendor, UUID> {

    Optional<Vendor> findByIdAndOrgIdAndDeletedAtIsNull(UUID id, UUID orgId);

    boolean existsByOrgIdAndCode(UUID orgId, String code);

    /** {@code CAST(:q AS string)} for the reason spelled out in {@link LabourContractorRepository}. */
    @Query("""
            SELECT v FROM Vendor v
            WHERE v.orgId = :orgId AND v.deletedAt IS NULL
              AND (:type IS NULL OR v.vendorType = :type)
              AND (:active IS NULL OR v.active = :active)
              AND (CAST(:q AS string) IS NULL
                   OR lower(v.name) LIKE lower(concat('%', CAST(:q AS string), '%'))
                   OR lower(v.code) LIKE lower(concat('%', CAST(:q AS string), '%')))
            """)
    Page<Vendor> search(@Param("orgId") UUID orgId, @Param("type") Vendor.Type type,
                        @Param("active") Boolean active, @Param("q") String q, Pageable pageable);
}

package in.nirman.modules.masterdata.repository;

import in.nirman.modules.masterdata.domain.Vendor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VendorRepository extends JpaRepository<Vendor, UUID> {

    Optional<Vendor> findByIdAndOrgIdAndDeletedAtIsNull(UUID id, UUID orgId);

    boolean existsByOrgIdAndCode(UUID orgId, String code);

    /** Every live supplier, for the callers that turn an id into a name. */
    List<Vendor> findByOrgIdAndDeletedAtIsNullOrderByCode(UUID orgId);

    /**
     * The suppliers of one kind — SUBCONTRACTOR for the men who bring gangs. Since V23 there
     * is one register for material dealers and labour suppliers alike, and the type is what
     * tells a picker which of them to offer.
     */
    List<Vendor> findByOrgIdAndVendorTypeAndActiveTrueAndDeletedAtIsNullOrderByName(
            UUID orgId, Vendor.Type vendorType);

    /**
     * Every live supplier carrying this name, case- and space-insensitively.
     *
     * <p>The duplicate check behind naming one from the field, and it is the whole reason
     * that endpoint is not {@code createVendor} with fewer fields: two rows for one firm
     * split his account in half, and neither half is what he thinks he is owed. Oldest
     * first, so an established row wins over one somebody typed twice.</p>
     */
    @Query("""
            SELECT v FROM Vendor v
            WHERE v.orgId = :orgId AND v.deletedAt IS NULL
              AND lower(trim(v.name)) = lower(trim(:name))
            ORDER BY v.createdAt ASC
            """)
    List<Vendor> findByName(@Param("orgId") UUID orgId, @Param("name") String name);

    /**
     * {@code CAST(:q AS string)} because an optional filter bound as a bare null gives
     * PostgreSQL no type to infer, and the statement fails exactly when the caller filters
     * by nothing — which is how every screen opens.
     */
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

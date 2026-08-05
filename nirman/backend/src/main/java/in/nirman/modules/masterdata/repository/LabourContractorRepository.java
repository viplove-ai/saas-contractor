package in.nirman.modules.masterdata.repository;

import in.nirman.modules.masterdata.domain.LabourContractor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LabourContractorRepository extends JpaRepository<LabourContractor, UUID> {

    Optional<LabourContractor> findByIdAndOrgIdAndDeletedAtIsNull(UUID id, UUID orgId);

    boolean existsByOrgIdAndCode(UUID orgId, String code);

    List<LabourContractor> findByOrgIdAndDeletedAtIsNullOrderByCode(UUID orgId);

    /**
     * The {@code CAST(:q AS string)} is load-bearing, not decoration. A bare {@code :q IS NULL}
     * gives PostgreSQL nothing to infer the parameter's type from, so a null search term is sent
     * untyped and the driver settles on {@code bytea} — at which point {@code lower(bytea)} does
     * not exist and listing every contractor fails with a 500. The cast names the type up front.
     */
    @Query("""
            SELECT c FROM LabourContractor c
            WHERE c.orgId = :orgId AND c.deletedAt IS NULL
              AND (CAST(:q AS string) IS NULL
                   OR lower(c.name) LIKE lower(concat('%', CAST(:q AS string), '%'))
                   OR lower(c.code) LIKE lower(concat('%', CAST(:q AS string), '%')))
            """)
    Page<LabourContractor> search(@Param("orgId") UUID orgId, @Param("q") String q, Pageable pageable);
}

package in.nirman.modules.project.repository;

import in.nirman.modules.project.domain.Project;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    Optional<Project> findByIdAndOrgIdAndDeletedAtIsNull(UUID id, UUID orgId);

    boolean existsByOrgIdAndCode(UUID orgId, String code);

    /**
     * {@code q} is an empty string when nothing is being searched for, never null: a null
     * whose only context is {@code lower()} gives PostgreSQL no type to infer, and the
     * statement fails to parse as {@code lower(bytea)}. The other optional filters compare
     * against a mapped attribute, which is type enough.
     */
    @Query("""
            SELECT p FROM Project p
            WHERE p.orgId = :orgId AND p.deletedAt IS NULL
              AND (:status IS NULL OR p.status = :status)
              AND (:q = '' OR lower(p.name) LIKE lower(concat('%', :q, '%'))
                           OR lower(p.code) LIKE lower(concat('%', :q, '%')))
              AND (:idsRestricted = false OR p.id IN :ids)
            """)
    Page<Project> search(@Param("orgId") UUID orgId,
                         @Param("status") Project.Status status,
                         @Param("q") String q,
                         @Param("idsRestricted") boolean idsRestricted,
                         @Param("ids") Collection<UUID> ids,
                         Pageable pageable);
}

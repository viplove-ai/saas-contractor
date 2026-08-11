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

    /**
     * The one read that can see a deleted project, for restoring it and for nothing else.
     * Every other lookup is the filtered one above, so a deleted project stays unreachable
     * by id from ordinary paths.
     */
    Optional<Project> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * Deliberately blind to {@code deleted_at}: a deleted project keeps its code reserved.
     * Handing the code to a new project would make the deleted one impossible to restore
     * without a collision, so the code stays taken and the error says why.
     */
    boolean existsByOrgIdAndCode(UUID orgId, String code);

    Optional<Project> findByOrgIdAndCode(UUID orgId, String code);

    /**
     * {@code q} is an empty string when nothing is being searched for, never null: a null
     * whose only context is {@code lower()} gives PostgreSQL no type to infer, and the
     * statement fails to parse as {@code lower(bytea)}. The other optional filters compare
     * against a mapped attribute, which is type enough.
     *
     * <p>{@code deleted} switches which side of the line is being asked for, and it is a
     * switch rather than an "include" flag: a deleted project is never wanted mixed in with
     * live ones, only looked at on its own.</p>
     */
    @Query("""
            SELECT p FROM Project p
            WHERE p.orgId = :orgId
              AND ((:deleted = false AND p.deletedAt IS NULL)
                OR (:deleted = true AND p.deletedAt IS NOT NULL))
              AND (:status IS NULL OR p.status = :status)
              AND (:q = '' OR lower(p.name) LIKE lower(concat('%', :q, '%'))
                           OR lower(p.code) LIKE lower(concat('%', :q, '%')))
              AND (:idsRestricted = false OR p.id IN :ids)
            """)
    Page<Project> search(@Param("orgId") UUID orgId,
                         @Param("status") Project.Status status,
                         @Param("q") String q,
                         @Param("deleted") boolean deleted,
                         @Param("idsRestricted") boolean idsRestricted,
                         @Param("ids") Collection<UUID> ids,
                         Pageable pageable);
}

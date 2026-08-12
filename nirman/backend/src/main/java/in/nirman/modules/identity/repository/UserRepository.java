package in.nirman.modules.identity.repository;

import in.nirman.modules.identity.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Login lookup. Usernames are unique per organisation and v1 runs single-org
     * (docs/00-assumptions); if multi-tenant login ever lands, the login form gains an org
     * discriminator and this becomes findByOrgIdAndUsername.
     */
    Optional<User> findByUsernameIgnoreCase(String username);

    /** Org-scoped lookup for the modules that name a member — a float's holder, say. */
    Optional<User> findByIdAndOrgId(UUID id, UUID orgId);

    boolean existsByOrgIdAndUsernameIgnoreCase(UUID orgId, String username);

    /** Every login in the organisation. A contractor's staff is a dozen people, unpaged. */
    List<User> findByOrgIdOrderByUsername(UUID orgId);

    /**
     * The role filter joins {@code u.roles} for matching only — the collection itself is
     * eager and loads in its own select, so the join cannot narrow what a matched user's
     * roles look like. Hence the explicit count query: a user with three roles must not be
     * counted three times.
     *
     * <p><b>No parameter here is ever null.</b> The obvious shape for an optional filter,
     * {@code :q IS NULL OR ...}, gives PostgreSQL nothing to infer the parameter's type
     * from, so an absent filter binds as {@code bytea} and the statement fails to parse —
     * and it fails precisely when the caller filters by nothing, which is how the screen
     * opens. Casting rescues the strings but not a null Boolean, which cannot be cast from
     * {@code bytea} at all. So the absent cases are carried by values instead: an empty
     * search term, an empty role code, and a pair of flags that say which activity states
     * to include.</p>
     */
    @Query(value = """
            SELECT DISTINCT u FROM User u LEFT JOIN u.roles r
            WHERE u.orgId = :orgId
              AND (:q = '' OR lower(u.username) LIKE lower(concat('%', :q, '%'))
                           OR lower(u.fullName) LIKE lower(concat('%', :q, '%')))
              AND (:role = '' OR r.code = :role)
              AND ((u.active = true AND :includeActive = true)
                   OR (u.active = false AND :includeInactive = true))
            """,
            countQuery = """
            SELECT count(DISTINCT u) FROM User u LEFT JOIN u.roles r
            WHERE u.orgId = :orgId
              AND (:q = '' OR lower(u.username) LIKE lower(concat('%', :q, '%'))
                           OR lower(u.fullName) LIKE lower(concat('%', :q, '%')))
              AND (:role = '' OR r.code = :role)
              AND ((u.active = true AND :includeActive = true)
                   OR (u.active = false AND :includeInactive = true))
            """)
    Page<User> search(@Param("orgId") UUID orgId, @Param("q") String q,
                      @Param("role") String role,
                      @Param("includeActive") boolean includeActive,
                      @Param("includeInactive") boolean includeInactive,
                      Pageable pageable);
}

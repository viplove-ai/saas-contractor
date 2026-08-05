package in.nirman.modules.identity.repository;

import in.nirman.modules.identity.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Transactional on the repository method: AuthService is deliberately not transactional
     * (a revocation must commit even though the caller then throws 401), so each of these
     * runs and commits on its own.
     */
    @Transactional
    @Modifying
    @Query("""
            UPDATE RefreshToken t SET t.revokedAt = :now, t.revokedReason = :reason
            WHERE t.familyId = :familyId AND t.revokedAt IS NULL
            """)
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now,
                     @Param("reason") String reason);

    @Transactional
    @Modifying
    @Query("""
            UPDATE RefreshToken t SET t.revokedAt = :now, t.revokedReason = :reason
            WHERE t.userId = :userId AND t.revokedAt IS NULL
            """)
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now,
                         @Param("reason") String reason);
}

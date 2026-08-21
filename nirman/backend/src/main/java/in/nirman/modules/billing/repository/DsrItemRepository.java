package in.nirman.modules.billing.repository;

import in.nirman.modules.billing.domain.DsrItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DsrItemRepository extends JpaRepository<DsrItem, UUID> {

    Optional<DsrItem> findByScheduleIdAndCode(UUID scheduleId, String code);

    /** Free-text search over code and description, which is how an engineer looks for a rate. */
    @Query("""
            SELECT i FROM DsrItem i
            WHERE i.scheduleId = :scheduleId
              AND (:q IS NULL OR LOWER(i.code) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(i.description) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY i.code ASC
            """)
    Page<DsrItem> search(@Param("scheduleId") UUID scheduleId, @Param("q") String q, Pageable pageable);

    long countByScheduleIdAndConfirmedFalse(UUID scheduleId);

    long countByScheduleId(UUID scheduleId);
}

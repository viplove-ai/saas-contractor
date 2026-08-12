package in.nirman.modules.masterdata.repository;

import in.nirman.modules.masterdata.domain.ExpenseCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategory, UUID> {

    List<ExpenseCategory> findByOrgIdOrderBySortOrderAscCodeAsc(UUID orgId);

    boolean existsByOrgIdAndCode(UUID orgId, String code);

    /**
     * The same head under the name somebody typed at a site.
     *
     * <p>Case- and space-insensitive, on {@code MaterialRepository.findByName}'s reasoning:
     * "site cleaning" and "Site Cleaning" are one head, and two of them split a month's
     * figure across two lines of the same report. Oldest first, so an established head wins
     * over anything that has already slipped past this check.</p>
     */
    @Query("""
            SELECT c FROM ExpenseCategory c
            WHERE c.orgId = :orgId
              AND lower(trim(c.name)) = lower(trim(:name))
            ORDER BY c.provisional ASC, c.code ASC
            """)
    List<ExpenseCategory> findByName(@Param("orgId") UUID orgId, @Param("name") String name);
}

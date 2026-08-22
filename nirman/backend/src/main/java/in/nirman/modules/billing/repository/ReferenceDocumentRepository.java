package in.nirman.modules.billing.repository;

import in.nirman.modules.billing.domain.ReferenceDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReferenceDocumentRepository extends JpaRepository<ReferenceDocument, UUID> {

    Optional<ReferenceDocument> findByIdAndOrgIdAndDeletedAtIsNull(UUID id, UUID orgId);

    Optional<ReferenceDocument> findByOrgIdAndCodeAndDeletedAtIsNull(UUID orgId, String code);

    /**
     * The shelf, newest edition first. The optional kind is a typed flag beside an always-bound
     * value rather than {@code :kind IS NULL} — Postgres cannot infer a type for a parameter
     * standing alone in {@code ? IS NULL} and refuses to prepare the statement.
     */
    @Query("""
            SELECT d FROM ReferenceDocument d
            WHERE d.orgId = :orgId AND d.deletedAt IS NULL
              AND (:allKinds = TRUE OR d.kind = :kind)
            ORDER BY d.kind ASC, d.editionYear DESC NULLS LAST, d.code ASC
            """)
    List<ReferenceDocument> findForOrg(@Param("orgId") UUID orgId,
                                       @Param("allKinds") boolean allKinds,
                                       @Param("kind") ReferenceDocument.Kind kind);

    /**
     * The edition a tender let today would cite, for one kind — used to offer a default, never
     * to decide one. What a tender is actually priced under is stored against it.
     */
    @Query("""
            SELECT d FROM ReferenceDocument d
            WHERE d.orgId = :orgId AND d.deletedAt IS NULL
              AND d.kind = :kind
              AND d.status = in.nirman.modules.billing.domain.ReferenceDocument$Status.CURRENT
              AND (:anyYear = TRUE OR d.editionYear = :year)
            ORDER BY d.editionYear DESC NULLS LAST
            """)
    List<ReferenceDocument> findCurrent(@Param("orgId") UUID orgId,
                                        @Param("kind") ReferenceDocument.Kind kind,
                                        @Param("anyYear") boolean anyYear,
                                        @Param("year") Integer year);

    List<ReferenceDocument> findBySupersedesId(UUID supersedesId);
}

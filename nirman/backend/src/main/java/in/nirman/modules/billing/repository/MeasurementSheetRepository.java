package in.nirman.modules.billing.repository;

import in.nirman.modules.billing.domain.MeasurementSheet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MeasurementSheetRepository extends JpaRepository<MeasurementSheet, UUID> {

    Optional<MeasurementSheet> findByIdAndOrgIdAndDeletedAtIsNull(UUID id, UUID orgId);

    /**
     * The duplicate-entry guard. A pre-printed serial is unique across the book, so a sheet
     * somebody has already entered is answered with the row it produced rather than with a
     * second one — the same argument the material catalogue makes about two rows for one
     * cement.
     */
    Optional<MeasurementSheet> findByOrgIdAndSheetSerialAndDeletedAtIsNull(UUID orgId, String sheetSerial);

    /**
     * What the next bill will sweep: signed, measured on or before the cutoff, and claimed by
     * no bill yet. Ordered as the measurement book reads — by item, then by date.
     *
     * <p>The optional cutoff is expressed as a boolean flag beside a always-bound date rather
     * than as {@code :cutoff IS NULL}. Postgres cannot infer a type for a parameter standing
     * alone in {@code ? IS NULL} and refuses to prepare the statement; every parameter here is
     * used in a typed comparison, so every one of them is inferable.</p>
     */
    @Query("""
            SELECT s FROM MeasurementSheet s
            WHERE s.orgId = :orgId AND s.projectId = :projectId
              AND s.deletedAt IS NULL AND s.raBillId IS NULL
              AND s.status = in.nirman.modules.billing.domain.MeasurementSheet$Status.SIGNED
              AND (:ignoreCutoff = TRUE OR s.measuredOn <= :cutoff)
            ORDER BY s.boqItemId ASC, s.measuredOn ASC
            """)
    List<MeasurementSheet> findUnbilled(@Param("orgId") UUID orgId,
                                        @Param("projectId") UUID projectId,
                                        @Param("ignoreCutoff") boolean ignoreCutoff,
                                        @Param("cutoff") LocalDate cutoff);

    /**
     * Everything on a project, billed or not — the register view. Same typed-flag treatment as
     * {@link #findUnbilled} and for the same reason.
     */
    @Query("""
            SELECT s FROM MeasurementSheet s
            WHERE s.orgId = :orgId AND s.projectId = :projectId
              AND s.deletedAt IS NULL
              AND (:allItems = TRUE OR s.boqItemId = :boqItemId)
              AND (:anyBillState = TRUE
                   OR (:billed = TRUE AND s.raBillId IS NOT NULL)
                   OR (:billed = FALSE AND s.raBillId IS NULL))
            ORDER BY s.measuredOn DESC, s.createdAt DESC
            """)
    List<MeasurementSheet> findForProject(@Param("orgId") UUID orgId,
                                          @Param("projectId") UUID projectId,
                                          @Param("allItems") boolean allItems,
                                          @Param("boqItemId") UUID boqItemId,
                                          @Param("anyBillState") boolean anyBillState,
                                          @Param("billed") boolean billed);

    List<MeasurementSheet> findByRaBillIdOrderByBoqItemIdAscMeasuredOnAsc(UUID raBillId);

    /**
     * The quantity a bill's own sheets claim for one item. Section sheets are excluded from
     * this sum and added by the caller, because their claim is the row total times a unit
     * weight and the database has no business doing that multiplication in two places.
     */
    @Query("""
            SELECT COALESCE(SUM(s.computedTotal), 0) FROM MeasurementSheet s
            WHERE s.raBillId = :billId AND s.boqItemId = :boqItemId
              AND s.deletedAt IS NULL AND s.unitWeight IS NULL
            """)
    BigDecimal claimedForItemInBill(@Param("billId") UUID billId, @Param("boqItemId") UUID boqItemId);
}

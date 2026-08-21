package in.nirman.modules.billing.repository;

import in.nirman.modules.billing.domain.RaBillItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface RaBillItemRepository extends JpaRepository<RaBillItem, UUID> {

    List<RaBillItem> findByRaBillIdOrderBySortOrder(UUID raBillId);

    /**
     * What every earlier passed bill already paid for one item. This is the figure that used
     * to be typed by hand off the last bill's printout, a hundred and fifteen times a bill.
     */
    @Query("""
            SELECT COALESCE(SUM(i.amountSince), 0) FROM RaBillItem i
            JOIN RaBill b ON b.id = i.raBillId
            WHERE i.boqItemId = :boqItemId AND b.projectId = :projectId
              AND b.deletedAt IS NULL
              AND b.status = in.nirman.modules.billing.domain.RaBill$Status.PASSED
            """)
    BigDecimal amountPaidToDate(@Param("projectId") UUID projectId, @Param("boqItemId") UUID boqItemId);

    @Query("""
            SELECT COALESCE(SUM(i.qtySincePrevious), 0) FROM RaBillItem i
            JOIN RaBill b ON b.id = i.raBillId
            WHERE i.boqItemId = :boqItemId AND b.projectId = :projectId
              AND b.deletedAt IS NULL
              AND b.status = in.nirman.modules.billing.domain.RaBill$Status.PASSED
            """)
    BigDecimal quantityPaidToDate(@Param("projectId") UUID projectId, @Param("boqItemId") UUID boqItemId);
}

package in.nirman.modules.labour.repository;

import in.nirman.modules.labour.domain.SiteLabourCount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface SiteLabourCountRepository extends JpaRepository<SiteLabourCount, UUID> {

    List<SiteLabourCount> findBySiteIdAndCountDate(UUID siteId, LocalDate countDate);

    /** For a period roll-up: the report reads a month at a time, not a day at a time. */
    List<SiteLabourCount> findBySiteIdAndCountDateBetween(UUID siteId, LocalDate from, LocalDate to);

    /** Every count attributed to one supplier, anywhere — his engagement, read backwards. */
    List<SiteLabourCount> findByLabourSupplierIdOrderByCountDateDesc(UUID labourSupplierId);
}

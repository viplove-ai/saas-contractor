package in.nirman.modules.labour.repository;

import in.nirman.modules.labour.domain.SiteLabourSupplierDay;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface SiteLabourSupplierDayRepository extends JpaRepository<SiteLabourSupplierDay, UUID> {

    List<SiteLabourSupplierDay> findBySiteIdAndCountDate(UUID siteId, LocalDate countDate);

    /** The supplier's own page: every day his men were somewhere, newest first. */
    List<SiteLabourSupplierDay> findByLabourSupplierIdOrderByCountDateDesc(UUID labourSupplierId);
}

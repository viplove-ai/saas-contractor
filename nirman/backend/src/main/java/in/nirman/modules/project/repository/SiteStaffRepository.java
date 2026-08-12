package in.nirman.modules.project.repository;

import in.nirman.modules.project.domain.SiteStaff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SiteStaffRepository extends JpaRepository<SiteStaff, UUID> {

    List<SiteStaff> findBySiteId(UUID siteId);

    /**
     * Every posting across a page of sites in one query. The register lists sites and each
     * row names its staff, so the alternative is a query per row.
     */
    List<SiteStaff> findBySiteIdIn(Collection<UUID> siteIds);
}

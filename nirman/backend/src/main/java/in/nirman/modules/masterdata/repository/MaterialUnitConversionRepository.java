package in.nirman.modules.masterdata.repository;

import in.nirman.modules.masterdata.domain.MaterialUnitConversion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MaterialUnitConversionRepository extends JpaRepository<MaterialUnitConversion, UUID> {

    List<MaterialUnitConversion> findByMaterialId(UUID materialId);

    /** Every conversion for a page of materials at once, rather than one query per row. */
    List<MaterialUnitConversion> findByMaterialIdIn(Collection<UUID> materialIds);

    boolean existsByMaterialIdAndAltUnitId(UUID materialId, UUID altUnitId);
}

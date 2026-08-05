package in.nirman.modules.masterdata.repository;

import in.nirman.modules.masterdata.domain.MaterialUnitConversion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MaterialUnitConversionRepository extends JpaRepository<MaterialUnitConversion, UUID> {

    List<MaterialUnitConversion> findByMaterialId(UUID materialId);

    boolean existsByMaterialIdAndAltUnitId(UUID materialId, UUID altUnitId);
}

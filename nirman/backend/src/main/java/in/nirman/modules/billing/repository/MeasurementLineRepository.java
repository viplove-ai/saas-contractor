package in.nirman.modules.billing.repository;

import in.nirman.modules.billing.domain.MeasurementLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MeasurementLineRepository extends JpaRepository<MeasurementLine, UUID> {

    List<MeasurementLine> findBySheetIdOrderByLineNo(UUID sheetId);

    List<MeasurementLine> findBySheetIdInOrderBySheetIdAscLineNoAsc(List<UUID> sheetIds);

    void deleteBySheetId(UUID sheetId);
}

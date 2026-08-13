package in.nirman.modules.planning.repository;

import in.nirman.modules.planning.domain.PlanNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlanNoteRepository extends JpaRepository<PlanNote, UUID> {

    List<PlanNote> findByPlanId(UUID planId);

    void deleteByPlanId(UUID planId);
}

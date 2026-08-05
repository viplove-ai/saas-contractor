package in.nirman.modules.inventory.repository;

import in.nirman.modules.inventory.domain.MaterialIssueItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MaterialIssueItemRepository extends JpaRepository<MaterialIssueItem, UUID> {

    List<MaterialIssueItem> findByIssueId(UUID issueId);

    List<MaterialIssueItem> findByIssueIdIn(Collection<UUID> issueIds);

    void deleteByIssueId(UUID issueId);
}

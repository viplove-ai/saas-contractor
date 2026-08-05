package in.nirman.modules.identity.repository;

import in.nirman.modules.identity.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByCodeAndSystemTrue(String code);

    List<Role> findByCodeInAndSystemTrue(List<String> codes);

    List<Role> findBySystemTrueOrderByCode();
}

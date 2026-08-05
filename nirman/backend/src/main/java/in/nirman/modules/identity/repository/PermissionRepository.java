package in.nirman.modules.identity.repository;

import in.nirman.modules.identity.domain.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {

    List<Permission> findAllByOrderByModuleAscCodeAsc();
}

package in.nirman.modules.identity.mapper;

import in.nirman.modules.identity.api.dto.UserDtos.UserResponse;
import in.nirman.modules.identity.domain.Role;
import in.nirman.modules.identity.domain.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "roles", source = "user.roles")
    @Mapping(target = "siteIds", source = "siteIds")
    UserResponse toResponse(User user, List<UUID> siteIds);

    default List<String> roleCodes(Set<Role> roles) {
        return roles.stream().map(Role::getCode).sorted().toList();
    }
}

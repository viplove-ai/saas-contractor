package in.nirman.modules.project.mapper;

import in.nirman.modules.project.api.dto.ProjectDtos.ProjectResponse;
import in.nirman.modules.project.api.dto.ProjectDtos.SiteResponse;
import in.nirman.modules.project.api.dto.ProjectDtos.StoreResponse;
import in.nirman.modules.project.domain.Project;
import in.nirman.modules.project.domain.Site;
import in.nirman.modules.project.domain.Store;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ProjectMapper {

    ProjectResponse toResponse(Project project);

    SiteResponse toResponse(Site site);

    StoreResponse toResponse(Store store);
}

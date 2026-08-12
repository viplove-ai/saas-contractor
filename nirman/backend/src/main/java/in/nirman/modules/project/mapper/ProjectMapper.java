package in.nirman.modules.project.mapper;

import in.nirman.modules.project.api.dto.ProjectDtos.ProjectResponse;
import in.nirman.modules.project.api.dto.ProjectDtos.SiteResponse;
import in.nirman.modules.project.api.dto.ProjectDtos.StoreResponse;
import in.nirman.modules.project.domain.Project;
import in.nirman.modules.project.domain.Site;
import in.nirman.modules.project.domain.Store;
import org.mapstruct.Mapper;

import java.util.List;
import java.util.UUID;

@Mapper(componentModel = "spring")
public interface ProjectMapper {

    ProjectResponse toResponse(Project project);

    /**
     * The site plus the two lists of names on it. They arrive as parameters rather than off
     * the entity because who runs a site lives in its own table (see {@code SiteStaff}), and
     * the register loads every row's staff in one query rather than one query per row.
     */
    SiteResponse toResponse(Site site, List<UUID> siteEngineerIds, List<UUID> supervisorIds);

    StoreResponse toResponse(Store store);
}

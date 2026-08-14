package in.nirman.modules.project.service;

import in.nirman.modules.project.repository.ProjectRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * {@link ProjectLookup}. Carries no permission check of its own, in the manner of every other
 * {@code *Lookup} here: it answers a caller that has already passed the check which got it there.
 */
@Service
@Transactional(readOnly = true)
public class ProjectLookupService implements ProjectLookup {

    private final ProjectRepository projects;
    private final CurrentUserProvider currentUser;

    public ProjectLookupService(ProjectRepository projects, CurrentUserProvider currentUser) {
        this.projects = projects;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<ProjectContract> contract(UUID projectId) {
        return projects.findByIdAndOrgIdAndDeletedAtIsNull(projectId, currentUser.currentOrgId())
                .map(project -> new ProjectContract(project.getId(), project.getName(),
                        project.getContractValue(), project.getQuotedPercent(),
                        project.getStartDate(), project.getExpectedCompletionDate()));
    }
}

package in.nirman.modules.project.service;

import in.nirman.modules.project.domain.Project;
import in.nirman.modules.project.repository.ProjectRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
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

    @Override
    public Optional<ContractCalendar> calendar(UUID projectId) {
        return projects.findByIdAndOrgIdAndDeletedAtIsNull(projectId, currentUser.currentOrgId())
                .map(ProjectLookupService::toCalendar);
    }

    @Override
    public List<ContractCalendar> calendars(Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return List.of();
        }
        return projects.findByIdInAndOrgIdAndDeletedAtIsNull(projectIds, currentUser.currentOrgId())
                .stream().map(ProjectLookupService::toCalendar).toList();
    }

    @Override
    public List<ContractCalendar> allCalendars() {
        return projects.findByOrgIdAndDeletedAtIsNullOrderByCode(currentUser.currentOrgId())
                .stream().map(ProjectLookupService::toCalendar).toList();
    }

    private static ContractCalendar toCalendar(Project project) {
        return new ContractCalendar(project.getId(), project.getCode(), project.getName(),
                project.getStatus().name(), project.getQuotedCost(), project.getContractValue(),
                project.getQuotedPercent(),
                project.getWorkNature() == null ? null : project.getWorkNature().name(),
                project.getStartDate(), project.getActualCompletionDate(),
                project.guaranteeClockStart(), project.getDefectLiabilityMonths());
    }
}

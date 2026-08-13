package in.nirman.modules.masterdata.service;

import in.nirman.modules.masterdata.domain.SkillCategory;
import in.nirman.modules.masterdata.repository.SkillCategoryRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link SkillLookup}, kept apart from {@link MasterDataService} for the same reason
 * {@link UnitLookupService} is: it names a thing for a caller that has already passed the check
 * which got it there, so it carries no permission check of its own.
 */
@Service
@Transactional(readOnly = true)
public class SkillLookupService implements SkillLookup {

    private final SkillCategoryRepository skills;
    private final CurrentUserProvider currentUser;

    public SkillLookupService(SkillCategoryRepository skills, CurrentUserProvider currentUser) {
        this.skills = skills;
        this.currentUser = currentUser;
    }

    @Override
    public List<SkillInfo> all() {
        return skills.findByOrgIdOrderByCode(currentUser.currentOrgId()).stream()
                .map(SkillLookupService::toInfo)
                .toList();
    }

    @Override
    public Map<UUID, SkillInfo> byIds(Collection<UUID> skillCategoryIds) {
        if (skillCategoryIds == null || skillCategoryIds.isEmpty()) {
            return Map.of();
        }
        UUID orgId = currentUser.currentOrgId();
        Map<UUID, SkillInfo> found = new LinkedHashMap<>();
        for (SkillCategory skill : skills.findAllById(skillCategoryIds)) {
            if (orgId.equals(skill.getOrgId())) {
                found.put(skill.getId(), toInfo(skill));
            }
        }
        return found;
    }

    private static SkillInfo toInfo(SkillCategory skill) {
        return new SkillInfo(skill.getId(), skill.getCode(), skill.getName(), skill.isSkilled());
    }
}

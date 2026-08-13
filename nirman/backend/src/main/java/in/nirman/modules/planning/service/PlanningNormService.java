package in.nirman.modules.planning.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.masterdata.service.MaterialLookup;
import in.nirman.modules.masterdata.service.SkillLookup;
import in.nirman.modules.masterdata.service.UnitLookup;
import in.nirman.modules.planning.api.dto.PlanningDtos.LeadTimeResponse;
import in.nirman.modules.planning.api.dto.PlanningDtos.ProductivityNormResponse;
import in.nirman.modules.planning.api.dto.PlanningDtos.ReviseLeadTimeRequest;
import in.nirman.modules.planning.api.dto.PlanningDtos.ReviseProductivityNormRequest;
import in.nirman.modules.planning.api.dto.PlanningDtos.ReviseSequenceNormRequest;
import in.nirman.modules.planning.api.dto.PlanningDtos.SequenceNormResponse;
import in.nirman.modules.planning.api.dto.PlanningDtos.WorkTypeProfileResponse;
import in.nirman.modules.planning.domain.LabourProductivityNorm;
import in.nirman.modules.planning.domain.MaterialLeadTime;
import in.nirman.modules.planning.domain.WorkSequenceNorm;
import in.nirman.modules.planning.repository.LabourProductivityNormRepository;
import in.nirman.modules.planning.repository.MaterialLeadTimeRepository;
import in.nirman.modules.planning.repository.WorkSequenceNormRepository;
import in.nirman.modules.planning.repository.WorkTypeProfileRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reading and correcting the norms a construction programme is derived from.
 *
 * <p>Everything shipped in {@code V30} is a starting value. A contractor's own productivity is
 * his competitive advantage and the number he will most want to change, so the whole catalogue
 * is org-scoped and editable — and every write is audited, because a norm quietly changed is
 * every future plan quietly changed.</p>
 *
 * <p>What may be edited is deliberately narrow: the <i>figures</i>, and whether a row is in use.
 * The category, the trade and the unit are what a norm <b>is</b>, and letting those move would
 * silently turn one norm into another that some earlier plan had already been built on. A norm
 * that is wrong in that way is deactivated and replaced, not mutated.</p>
 */
@Service
@Transactional
public class PlanningNormService {

    private final WorkTypeProfileRepository profiles;
    private final LabourProductivityNormRepository productivity;
    private final WorkSequenceNormRepository sequence;
    private final MaterialLeadTimeRepository leadTimes;
    private final SkillLookup skills;
    private final UnitLookup units;
    private final MaterialLookup materials;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public PlanningNormService(WorkTypeProfileRepository profiles,
                               LabourProductivityNormRepository productivity,
                               WorkSequenceNormRepository sequence,
                               MaterialLeadTimeRepository leadTimes,
                               SkillLookup skills, UnitLookup units, MaterialLookup materials,
                               CurrentUserProvider currentUser, AuditService audit) {
        this.profiles = profiles;
        this.productivity = productivity;
        this.sequence = sequence;
        this.leadTimes = leadTimes;
        this.skills = skills;
        this.units = units;
        this.materials = materials;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ work type profiles

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('planning:read')")
    public List<WorkTypeProfileResponse> workTypeProfiles() {
        return profiles.findByOrgIdAndActiveTrueOrderByCodeAsc(currentUser.currentOrgId()).stream()
                .map(profile -> new WorkTypeProfileResponse(profile.getId(), profile.getCode(),
                        profile.getName(), profile.getDescription(), profile.getPhaseBasis(),
                        profile.isMonsoonSensitive(), profile.getDefaultOverheadPercent(),
                        profile.isActive(), profile.getVersion()))
                .toList();
    }

    // ------------------------------------------------------------------ productivity

    /**
     * Every productivity norm, with its trade and unit named.
     *
     * <p>The two lookups are resolved in bulk rather than per row: a full catalogue is a
     * hundred-odd norms over eight trades and ten units, and naming them one at a time would be
     * two hundred queries to render one screen.</p>
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('planning:read')")
    public List<ProductivityNormResponse> productivityNorms() {
        List<LabourProductivityNorm> norms = productivity
                .findByOrgIdOrderByWorkCategoryAscWorkSubTypeAsc(currentUser.currentOrgId());
        Map<UUID, SkillLookup.SkillInfo> skillsById =
                skills.byIds(norms.stream().map(LabourProductivityNorm::getSkillCategoryId).toList());
        Map<UUID, String> unitCodes =
                units.codesByIds(norms.stream().map(LabourProductivityNorm::getWorkUnitId).toList());

        return norms.stream().map(norm -> {
            SkillLookup.SkillInfo skill = skillsById.get(norm.getSkillCategoryId());
            return new ProductivityNormResponse(norm.getId(), norm.getWorkCategory(),
                    norm.getWorkSubType(), norm.getSkillCategoryId(),
                    skill == null ? null : skill.code(), skill == null ? null : skill.name(),
                    skill != null && skill.skilled(),
                    norm.getWorkUnitId(), unitCodes.get(norm.getWorkUnitId()),
                    norm.getManDaysPerWorkUnit(), norm.getSource(), norm.isActive(),
                    norm.getNotes(), norm.getVersion());
        }).toList();
    }

    @PreAuthorize("hasAuthority('planning:norms:write')")
    public ProductivityNormResponse reviseProductivityNorm(UUID id,
                                                           ReviseProductivityNormRequest request) {
        LabourProductivityNorm norm = productivity
                .findByIdAndOrgId(id, currentUser.currentOrgId())
                .orElseThrow(() -> BusinessException.notFound("Productivity norm", id));
        requireVersion(norm.getVersion(), request.version(), "Productivity norm", id);

        String was = String.valueOf(norm.getManDaysPerWorkUnit());
        norm.reviseTo(request.manDaysPerWorkUnit(), request.active(), request.notes());

        // The old figure is recorded because the question asked six months from now is not what
        // the norm says, it is what it said when the plan that used it was made.
        audit.record("PLANNING_NORM", norm.getId(), "UPDATE", null,
                Map.of("category", norm.getWorkCategory(),
                        "was", was,
                        "now", String.valueOf(norm.getManDaysPerWorkUnit())), null);
        return productivityNorms().stream()
                .filter(response -> response.id().equals(id))
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("Productivity norm", id));
    }

    // ------------------------------------------------------------------ sequence

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('planning:read')")
    public List<SequenceNormResponse> sequenceNorms() {
        return sequence.findByOrgIdOrderBySequenceRankAsc(currentUser.currentOrgId()).stream()
                .map(PlanningNormService::toResponse)
                .toList();
    }

    @PreAuthorize("hasAuthority('planning:norms:write')")
    public SequenceNormResponse reviseSequenceNorm(UUID id, ReviseSequenceNormRequest request) {
        WorkSequenceNorm norm = sequence.findByIdAndOrgId(id, currentUser.currentOrgId())
                .orElseThrow(() -> BusinessException.notFound("Sequence norm", id));
        requireVersion(norm.getVersion(), request.version(), "Sequence norm", id);

        norm.reviseTo(request.sequenceRank(), request.maxOverlapPercent(),
                request.maxConcurrentGangs(), request.monsoonSensitive(), request.active());
        audit.record("PLANNING_NORM", norm.getId(), "UPDATE", null,
                Map.of("category", norm.getWorkCategory(),
                        "rank", String.valueOf(norm.getSequenceRank()),
                        "gangs", String.valueOf(norm.getMaxConcurrentGangs())), null);
        return toResponse(norm);
    }

    // ------------------------------------------------------------------ lead times

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('planning:read')")
    public List<LeadTimeResponse> leadTimes() {
        List<MaterialLeadTime> rows = leadTimes.findByOrgId(currentUser.currentOrgId());
        Map<UUID, MaterialLookup.MaterialInfo> byId =
                materials.byIds(rows.stream().map(MaterialLeadTime::getMaterialId).toList());
        return rows.stream()
                .map(row -> toResponse(row, byId.get(row.getMaterialId())))
                .sorted(java.util.Comparator.comparing(
                        response -> response.materialCode() == null ? "" : response.materialCode()))
                .toList();
    }

    @PreAuthorize("hasAuthority('planning:norms:write')")
    public LeadTimeResponse reviseLeadTime(UUID id, ReviseLeadTimeRequest request) {
        MaterialLeadTime row = leadTimes.findByIdAndOrgId(id, currentUser.currentOrgId())
                .orElseThrow(() -> BusinessException.notFound("Material lead time", id));
        requireVersion(row.getVersion(), request.version(), "Material lead time", id);

        row.reviseTo(request.leadDays(), request.bufferDays(), request.shelfLifeDays(),
                request.storable(), request.active(), request.notes());
        audit.record("PLANNING_NORM", row.getId(), "UPDATE", null,
                Map.of("materialId", String.valueOf(row.getMaterialId()),
                        "orderAheadDays", String.valueOf(row.orderAheadDays())), null);
        return toResponse(row, materials.byIds(List.of(row.getMaterialId()))
                .get(row.getMaterialId()));
    }

    // ------------------------------------------------------------------ mapping

    private static SequenceNormResponse toResponse(WorkSequenceNorm norm) {
        return new SequenceNormResponse(norm.getId(), norm.getWorkTypeProfileId(),
                norm.getWorkCategory(), norm.getSequenceRank(), norm.getMaxOverlapPercent(),
                norm.getMaxConcurrentGangs(), norm.isMonsoonSensitive(), norm.isActive(),
                norm.getVersion());
    }

    private static LeadTimeResponse toResponse(MaterialLeadTime row,
                                               MaterialLookup.MaterialInfo material) {
        return new LeadTimeResponse(row.getId(), row.getMaterialId(),
                material == null ? null : material.code(),
                material == null ? null : material.name(),
                row.getLeadDays(), row.getBufferDays(), row.orderAheadDays(),
                row.getShelfLifeDays(), row.isStorable(), row.isActive(), row.getNotes(),
                row.getVersion());
    }

    private static void requireVersion(Long actual, Long presented, String what, UUID id) {
        if (!actual.equals(presented)) {
            throw new OptimisticLockingFailureException(
                    what + " " + id + " was changed by someone else");
        }
    }
}

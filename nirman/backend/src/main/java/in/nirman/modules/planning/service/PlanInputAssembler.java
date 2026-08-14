package in.nirman.modules.planning.service;

import in.nirman.modules.inventory.service.InventoryLookup;
import in.nirman.modules.masterdata.service.MaterialLookup;
import in.nirman.modules.masterdata.service.SkillLookup;
import in.nirman.modules.masterdata.service.UnitLookup;
import in.nirman.modules.planning.domain.WorkTypeProfile;
import in.nirman.modules.planning.engine.PlanInput;
import in.nirman.modules.planning.repository.LabourProductivityNormRepository;
import in.nirman.modules.planning.repository.MaterialLeadTimeRepository;
import in.nirman.modules.planning.repository.WorkSequenceNormRepository;
import in.nirman.modules.project.service.BoqLookup;
import in.nirman.modules.project.service.ProjectLookup;
import in.nirman.modules.tender.service.NitLookup;
import in.nirman.security.CurrentUserProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the engine's input from what this organisation actually holds.
 *
 * <p>Everything crossing a module boundary crosses it here, through a published lookup and
 * nothing else — the BOQ from {@code project}, the tender's terms from {@code tender}, the
 * consumption norms from {@code inventory}, trades and units from {@code masterdata}. That is
 * what lets {@link in.nirman.modules.planning.engine.PlanEngine} stay pure and be tested against
 * a hand-written case.</p>
 */
@Component
public class PlanInputAssembler {

    /** Ordinary CPWD deductions. Statutory rather than tendered, so they are constants here. */
    static final BigDecimal INCOME_TAX_TDS = new BigDecimal("2");
    static final BigDecimal GST_TDS = new BigDecimal("2");
    static final BigDecimal LABOUR_CESS = new BigDecimal("1");
    static final BigDecimal BG_COMMISSION = new BigDecimal("1.2");
    /** Most contractors bill monthly, so that is where the cycle starts. */
    static final int DEFAULT_BILLING_CYCLE_DAYS = 30;
    static final int DEFAULT_PAYMENT_LAG_DAYS = 45;
    static final int DEFAULT_DEFECT_LIABILITY_MONTHS = 6;

    private final BoqLookup boq;
    private final ProjectLookup projects;
    private final NitLookup tenders;
    private final InventoryLookup inventory;
    private final MaterialLookup materials;
    private final SkillLookup skills;
    private final UnitLookup units;
    private final LabourProductivityNormRepository productivity;
    private final WorkSequenceNormRepository sequence;
    private final MaterialLeadTimeRepository leadTimes;
    private final in.nirman.modules.planning.repository.WorkTypeProfileRepository profiles;
    private final CurrentUserProvider currentUser;

    public PlanInputAssembler(BoqLookup boq, ProjectLookup projects, NitLookup tenders,
                              InventoryLookup inventory,
                              MaterialLookup materials, SkillLookup skills,
                              UnitLookup units,
                              LabourProductivityNormRepository productivity,
                              WorkSequenceNormRepository sequence,
                              MaterialLeadTimeRepository leadTimes,
                              in.nirman.modules.planning.repository.WorkTypeProfileRepository profiles,
                              CurrentUserProvider currentUser) {
        this.profiles = profiles;
        this.boq = boq;
        this.projects = projects;
        this.tenders = tenders;
        this.inventory = inventory;
        this.materials = materials;
        this.skills = skills;
        this.units = units;
        this.productivity = productivity;
        this.sequence = sequence;
        this.leadTimes = leadTimes;
        this.currentUser = currentUser;
    }

    /**
     * @param overrides what the user chose on the screen, which always beats what was extracted
     */
    public Assembled forProject(UUID projectId, UUID requestedProfileId, Overrides overrides) {
        NitLookup.TenderTerms terms = tenders.forProject(projectId).orElse(null);
        ProjectLookup.ProjectContract contract = projects.contract(projectId).orElse(null);

        List<PlanInput.WorkItem> items = boq.forProject(projectId).stream()
                .map(line -> new PlanInput.WorkItem(line.itemNumber(), line.description(),
                        line.category(), line.workPart(), line.contractQuantity(),
                        unitCode(line.unitId()), line.contractAmount(), line.synthetic()))
                .toList();

        LocalDate commencement = overrides.commencementDate() != null
                ? overrides.commencementDate()
                : LocalDate.now().plusDays(terms == null || terms.startReckoningDays() == null
                        ? 10 : terms.startReckoningDays());
        int allowedDays = overrides.allowedDays() != null ? overrides.allowedDays()
                : terms != null && terms.completionDays() != null ? terms.completionDays() : 365;

        BigDecimal contractValue = items.stream()
                .map(PlanInput.WorkItem::amount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        WorkTypeProfile profile = resolveProfile(requestedProfileId, items,
                contract == null ? null : contract.name());

        // The bid belongs to the contract, not to the plan. Two plans of one project that
        // disagreed about what was actually quoted would both be wrong about the money.
        BigDecimal quoted = overrides.quotedPercent() != null ? overrides.quotedPercent()
                : contract != null && contract.quotedPercent() != null ? contract.quotedPercent()
                : BigDecimal.ZERO;

        PlanInput input = new PlanInput(toProfile(profile), commencement, allowedDays, quoted,
                items, milestones(terms, allowedDays),
                terms(terms, contractValue, overrides), costs(overrides), norms());
        return new Assembled(input, profile);
    }

    /** The input, and which profile it was built on — detected unless the user named one. */
    public record Assembled(PlanInput input, WorkTypeProfile profile) {
    }

    /**
     * The user's choice if there is one, otherwise what the schedule says the job is.
     *
     * <p>Asking somebody to pick from a list of seven when the tender in front of them answers it
     * is a question with a right answer, and those are the ones a screen should not ask. The
     * detected profile is still shown and still changeable.</p>
     */
    private WorkTypeProfile resolveProfile(UUID requestedProfileId,
                                           List<PlanInput.WorkItem> items, String projectName) {
        UUID orgId = currentUser.currentOrgId();
        if (requestedProfileId != null) {
            WorkTypeProfile chosen = profiles.findById(requestedProfileId)
                    .filter(row -> row.getOrgId().equals(orgId)).orElse(null);
            if (chosen != null) {
                return chosen;
            }
        }
        return profiles.findByOrgIdAndCode(orgId, WorkTypeDetector.detect(items, projectName))
                .orElse(null);
    }

    /** The choices a plan cannot read out of a document, with the engine's own defaults. */
    public record Overrides(
            LocalDate commencementDate,
            Integer allowedDays,
            BigDecimal quotedPercent,
            Integer billingCycleDays,
            Integer paymentLagDays,
            BigDecimal defaultDailyWage,
            Map<String, BigDecimal> dailyWageByTrade,
            Integer workingDaysPerMonth,
            BigDecimal monthlyStaffCost,
            BigDecimal siteSetupCost,
            BigDecimal monthlyPlantAndTransport) {

        public static Overrides empty() {
            return new Overrides(null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    private PlanInput.WorkTypeProfile toProfile(WorkTypeProfile profile) {
        if (profile == null) {
            return new PlanInput.WorkTypeProfile("BUILDING_NEW", "Building - new construction",
                    true, new BigDecimal("8"), Map.of());
        }
        return new PlanInput.WorkTypeProfile(profile.getCode(), profile.getName(),
                profile.isMonsoonSensitive(), profile.getDefaultOverheadPercent(), Map.of());
    }

    /**
     * The stipulated milestones, with a due day each.
     *
     * <p>A milestone the reader could not date is dropped rather than guessed at: a phase
     * boundary invented on the wrong day would be measured against, and being confidently wrong
     * about a date the contract withholds money on is worse than being silent.</p>
     */
    private static List<PlanInput.Milestone> milestones(NitLookup.TenderTerms terms,
                                                        int allowedDays) {
        if (terms == null) {
            return List.of();
        }
        return terms.milestones().stream()
                .filter(milestone -> milestone.dueDays() != null)
                .map(milestone -> new PlanInput.Milestone(milestone.sequence(),
                        milestone.description(), Math.min(milestone.dueDays(), allowedDays),
                        milestone.financialPercent(), milestone.withheldPercent(),
                        milestone.physical()))
                .toList();
    }

    private static PlanInput.CommercialTerms terms(NitLookup.TenderTerms terms,
                                                   BigDecimal contractValue, Overrides overrides) {
        Map<String, BigDecimal> thresholds = terms == null ? Map.of() : terms.interimMinimums();
        return new PlanInput.CommercialTerms(
                terms != null && terms.estimatedCost() != null
                        ? terms.estimatedCost() : contractValue,
                terms == null ? new BigDecimal("5") : orDefault(
                        terms.performanceGuaranteePercent(), new BigDecimal("5")),
                terms == null ? new BigDecimal("2.5") : orDefault(
                        terms.securityDepositPercent(), new BigDecimal("2.5")),
                thresholds,
                terms == null ? null : terms.clause7aApplicable(),
                overrides.billingCycleDays() == null
                        ? DEFAULT_BILLING_CYCLE_DAYS : overrides.billingCycleDays(),
                overrides.paymentLagDays() == null
                        ? DEFAULT_PAYMENT_LAG_DAYS : overrides.paymentLagDays(),
                INCOME_TAX_TDS, GST_TDS, LABOUR_CESS, BigDecimal.ZERO,
                DEFAULT_DEFECT_LIABILITY_MONTHS,
                terms == null ? BigDecimal.ZERO : orDefault(terms.emdAmount(), BigDecimal.ZERO),
                // The estimate is what the guarantees are measured against, and it is not the
                // BOQ's own total once a bid has been applied to it.
                terms == null || terms.estimatedCost() == null ? contractValue
                        : terms.estimatedCost(),
                terms == null || terms.additionalGuarantee() == null ? null
                        : new PlanInput.AdditionalGuarantee(
                                terms.additionalGuarantee().thresholdPercent(),
                                terms.additionalGuarantee().method(),
                                terms.additionalGuarantee().percent()));
    }

    private static PlanInput.CostBasis costs(Overrides overrides) {
        return new PlanInput.CostBasis(
                overrides.dailyWageByTrade() == null ? Map.of() : overrides.dailyWageByTrade(),
                orDefault(overrides.defaultDailyWage(), new BigDecimal("600")),
                overrides.workingDaysPerMonth() == null ? 26 : overrides.workingDaysPerMonth(),
                orDefault(overrides.monthlyStaffCost(), new BigDecimal("100000")),
                orDefault(overrides.siteSetupCost(), new BigDecimal("200000")),
                orDefault(overrides.monthlyPlantAndTransport(), new BigDecimal("50000")),
                BG_COMMISSION);
    }

    private PlanInput.Norms norms() {
        UUID orgId = currentUser.currentOrgId();
        Map<UUID, SkillLookup.SkillInfo> skillsById = new LinkedHashMap<>();
        skills.all().forEach(skill -> skillsById.put(skill.id(), skill));

        var productivityRows =
                productivity.findByOrgIdOrderByWorkCategoryAscWorkSubTypeAsc(orgId);
        Map<UUID, String> unitCodes = units.codesByIds(
                productivityRows.stream().map(row -> row.getWorkUnitId()).toList());

        List<PlanInput.ProductivityNorm> productivityNorms = productivityRows.stream()
                .filter(row -> row.isActive())
                .map(row -> {
                    SkillLookup.SkillInfo skill = skillsById.get(row.getSkillCategoryId());
                    return new PlanInput.ProductivityNorm(row.getWorkCategory(),
                            row.getWorkSubType(), skill == null ? "?" : skill.code(),
                            skill != null && skill.skilled(), unitCodes.get(row.getWorkUnitId()),
                            row.getManDaysPerWorkUnit());
                })
                .toList();

        List<PlanInput.SequenceNorm> sequenceNorms =
                sequence.findByOrgIdOrderBySequenceRankAsc(orgId).stream()
                        .filter(row -> row.isActive() && row.getWorkTypeProfileId() == null)
                        .map(row -> new PlanInput.SequenceNorm(row.getWorkCategory(),
                                row.getSequenceRank(), row.getMaxOverlapPercent(),
                                row.getMaxConcurrentGangs(), row.isMonsoonSensitive()))
                        .toList();

        List<InventoryLookup.ConsumptionNormInfo> consumptionRows = inventory.consumptionNorms();
        List<PlanInput.ConsumptionNorm> consumption = consumptionRows.stream()
                .map(norm -> new PlanInput.ConsumptionNorm(norm.workCategory(),
                        norm.workSubType(), norm.materialCode(), norm.materialName(),
                        norm.workUnitCode(), norm.qtyPerWorkUnit(), norm.standardRate()))
                .toList();

        // Lead times are held against a material id; the engine works in codes, because a code
        // is what a procurement list has to print. MaterialLookup is what joins the two.
        var leadRows = leadTimes.findByOrgId(orgId).stream().filter(row -> row.isActive()).toList();
        Map<UUID, in.nirman.modules.masterdata.service.MaterialLookup.MaterialInfo> byMaterial =
                materials.byIds(leadRows.stream().map(row -> row.getMaterialId()).toList());
        Map<String, PlanInput.LeadTime> byCode = new LinkedHashMap<>();
        leadRows.forEach(row -> {
            var material = byMaterial.get(row.getMaterialId());
            if (material != null) {
                byCode.put(material.code(), new PlanInput.LeadTime(row.getLeadDays(),
                        row.getBufferDays(), row.getShelfLifeDays(), row.isStorable()));
            }
        });
        // A material with no lead time falls back to the engine's own default, which the plan
        // then declares as an assumption rather than passing off as a zero-day lead.
        return new PlanInput.Norms(productivityNorms, sequenceNorms, consumption, byCode);
    }

    private String unitCode(UUID unitId) {
        return unitId == null ? null : units.codesByIds(List.of(unitId)).get(unitId);
    }

    private static BigDecimal orDefault(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }
}

package in.nirman.modules.planning.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.planning.api.dto.PlanDtos.CashView;
import in.nirman.modules.planning.api.dto.PlanDtos.GeneratePlanRequest;
import in.nirman.modules.planning.api.dto.PlanDtos.LabourView;
import in.nirman.modules.planning.api.dto.PlanDtos.MaterialView;
import in.nirman.modules.planning.api.dto.PlanDtos.NoteView;
import in.nirman.modules.planning.api.dto.PlanDtos.PackageView;
import in.nirman.modules.planning.api.dto.PlanDtos.PhaseView;
import in.nirman.modules.planning.api.dto.PlanDtos.PlanResponse;
import in.nirman.modules.planning.api.dto.PlanDtos.WorkingCapitalView;
import in.nirman.modules.planning.domain.ExecutionPlan;
import in.nirman.modules.planning.domain.PlanCashFlow;
import in.nirman.modules.planning.domain.PlanLabourDemand;
import in.nirman.modules.planning.domain.PlanMaterialDemand;
import in.nirman.modules.planning.domain.PlanNote;
import in.nirman.modules.planning.domain.PlanPhase;
import in.nirman.modules.planning.domain.PlanWorkPackage;
import in.nirman.modules.planning.domain.WorkTypeProfile;
import in.nirman.modules.planning.engine.PlanEngine;
import in.nirman.modules.planning.engine.PlanInput;
import in.nirman.modules.planning.engine.PlanOutput;
import in.nirman.modules.planning.repository.ExecutionPlanRepository;
import in.nirman.modules.planning.repository.PlanCashFlowRepository;
import in.nirman.modules.planning.repository.PlanLabourDemandRepository;
import in.nirman.modules.planning.repository.PlanMaterialDemandRepository;
import in.nirman.modules.planning.repository.PlanNoteRepository;
import in.nirman.modules.planning.repository.PlanPhaseRepository;
import in.nirman.modules.planning.repository.PlanWorkPackageRepository;
import in.nirman.modules.planning.repository.WorkTypeProfileRepository;
import in.nirman.modules.project.service.PlanBaselineWriter;
import in.nirman.security.CurrentUserProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Generating, storing and freezing an execution plan.
 *
 * <p>Three acts, and they are deliberately different in weight. {@link #preview} persists nothing
 * and exists so a bidder can move the quoted percentage and watch the funding peak move with it.
 * {@link #create} keeps a draft. {@link #baseline} is the irreversible one: it freezes the
 * figures the project will be measured against, and it is the only place the plan writes forward
 * into the rest of the system.</p>
 */
@Service
@Transactional
public class ExecutionPlanService {

    /** Bumped when the arithmetic changes, so two plans of the same tender can be compared. */
    public static final String ENGINE_VERSION = "1.1.0";

    /** Mirrors the assembler's default, for reading back a plan stored before the column existed. */
    private static final int DEFAULT_BILLING_CYCLE_DAYS = 30;

    private final ExecutionPlanRepository plans;
    private final PlanPhaseRepository phases;
    private final PlanWorkPackageRepository packages;
    private final PlanLabourDemandRepository labour;
    private final PlanMaterialDemandRepository material;
    private final PlanCashFlowRepository cash;
    private final PlanNoteRepository notes;
    private final WorkTypeProfileRepository profiles;
    private final PlanInputAssembler assembler;
    private final PlanBaselineWriter baselineWriter;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public ExecutionPlanService(ExecutionPlanRepository plans, PlanPhaseRepository phases,
                                PlanWorkPackageRepository packages,
                                PlanLabourDemandRepository labour,
                                PlanMaterialDemandRepository material, PlanCashFlowRepository cash,
                                PlanNoteRepository notes, WorkTypeProfileRepository profiles,
                                PlanInputAssembler assembler, PlanBaselineWriter baselineWriter,
                                CurrentUserProvider currentUser, AuditService audit) {
        this.plans = plans;
        this.phases = phases;
        this.packages = packages;
        this.labour = labour;
        this.material = material;
        this.cash = cash;
        this.notes = notes;
        this.profiles = profiles;
        this.assembler = assembler;
        this.baselineWriter = baselineWriter;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    /**
     * Runs the engine and returns the plan without keeping any of it.
     *
     * <p>Persisting nothing is what makes a scenario cheap. Pre-award the whole exercise is
     * varying one number and watching the peak funding requirement move, and a screen that wrote
     * a row for every drag of a slider would be unusable and would fill the table with drafts
     * nobody chose.</p>
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('planning:generate')")
    public PlanResponse preview(UUID projectId, GeneratePlanRequest request) {
        PlanInputAssembler.Assembled assembled = input(projectId, request);
        return toResponse(null, projectId, name(request), scenario(request), assembled,
                PlanEngine.plan(assembled.input()), false, 1);
    }

    @PreAuthorize("hasAuthority('planning:generate')")
    public PlanResponse create(UUID projectId, GeneratePlanRequest request) {
        PlanInputAssembler.Assembled assembled = input(projectId, request);
        PlanInput input = assembled.input();
        PlanOutput output = PlanEngine.plan(input);

        int previous = plans.findByProjectIdAndOrgIdAndDeletedAtIsNullOrderByRevisionDesc(
                        projectId, currentUser.currentOrgId()).stream()
                .mapToInt(ExecutionPlan::getRevision).max().orElse(0);

        ExecutionPlan plan = new ExecutionPlan(currentUser.currentOrgId(), projectId,
                name(request), ExecutionPlan.SOURCE_PROJECT, scenario(request),
                input.commencementDate(), input.allowedDays(), input.quotedPercent(),
                input.terms().contractValue(), input.terms().paymentLagDays(), ENGINE_VERSION);
        plan.nextRevisionAfter(previous);
        plan.describe(null, assembled.profile() == null ? null : assembled.profile().getId());
        PlanOutput.WorkingCapital capital = output.workingCapital();
        plan.recordOutcome(capital.peakFundingRequirement(), month(capital.peakMonth()),
                capital.moneyBeforeDayOne(), month(capital.breakEvenMonth()),
                capital.totalRetentionHeld(), capital.retentionReleasedOn(),
                capital.totalOutflow(), capital.totalNetReceipts());
        plans.save(plan);
        writeChildren(plan.getId(), output);

        audit.record("EXECUTION_PLAN", plan.getId(), "CREATE", null,
                Map.of("projectId", String.valueOf(projectId),
                        "revision", String.valueOf(plan.getRevision()),
                        "peakFunding", String.valueOf(capital.peakFundingRequirement())), null);
        return toResponse(plan.getId(), projectId, plan.getName(), plan.getScenario(), assembled,
                output, false, plan.getRevision());
    }

    /**
     * Freezes a plan, and supersedes whatever it replaces.
     *
     * <p>This is where the plan writes forward — and only here, and only into
     * {@code boq_items}' own planned dates and budget columns, which {@code V1} provisioned for
     * a planner and nothing has written since. A planned quantity still never reaches the
     * measurement book, a planned requirement is never a stock transaction, and a planned cost
     * is never an expense.</p>
     */
    @PreAuthorize("hasAuthority('planning:baseline')")
    public PlanResponse baseline(UUID planId) {
        ExecutionPlan plan = plans.findByIdAndOrgIdAndDeletedAtIsNull(planId,
                        currentUser.currentOrgId())
                .orElseThrow(() -> BusinessException.notFound("Execution plan", planId));
        if (plan.isBaselined()) {
            throw BusinessException.conflict("plan.already-baselined",
                    "This plan is already the baseline. Generate a new one to replace it.");
        }
        if (plan.getProjectId() == null) {
            throw new BusinessException("plan.not-attached",
                    "A plan has to belong to a project before it can be made its baseline.",
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        }
        // Flushed before the new baseline is stamped. uq_plan_project_baseline allows one live
        // baseline per project, and Postgres checks it per statement — so the outgoing plan has
        // to be marked superseded on its own trip to the database, or the two collide.
        plans.findByProjectIdAndOrgIdAndBaselinedAtIsNotNullAndSupersededAtIsNullAndDeletedAtIsNull(
                plan.getProjectId(), currentUser.currentOrgId())
                .ifPresent(previous -> {
                    previous.supersede();
                    plans.saveAndFlush(previous);
                });

        plan.baseline(currentUser.currentUserIdOrNull());
        baselineWriter.applyPlannedDates(plan.getProjectId(),
                packages.findByPlanId(plan.getId()).stream()
                        .map(block -> new PlanBaselineWriter.PlannedCategory(
                                block.getWorkCategory(), block.getWorkPart(),
                                block.getStartDate(), block.getEndDate()))
                        .toList());

        audit.record("EXECUTION_PLAN", plan.getId(), "BASELINE", null,
                Map.of("projectId", String.valueOf(plan.getProjectId()),
                        "revision", String.valueOf(plan.getRevision())), null);
        return read(plan);
    }

    /** The live baseline a project runs under, or 404 when it has none. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('planning:read')")
    public PlanResponse forProject(UUID projectId) {
        return read(plans
                .findByProjectIdAndOrgIdAndBaselinedAtIsNotNullAndSupersededAtIsNullAndDeletedAtIsNull(
                        projectId, currentUser.currentOrgId())
                .orElseThrow(() -> BusinessException.notFound("Execution plan for project",
                        projectId)));
    }

    // ------------------------------------------------------------------ internals

    private PlanInputAssembler.Assembled input(UUID projectId, GeneratePlanRequest request) {
        return assembler.forProject(projectId, request.workTypeProfileId(),
                new PlanInputAssembler.Overrides(
                        request.commencementDate(), request.allowedDays(), request.quotedPercent(),
                        request.billingCycleDays(), request.paymentLagDays(),
                        request.defaultDailyWage(), request.dailyWageByTrade(),
                        request.workingDaysPerMonth(), request.monthlyStaffCost(),
                        request.siteSetupCost(), request.monthlyPlantAndTransport()));
    }

    private void writeChildren(UUID planId, PlanOutput output) {
        phases.saveAll(output.phases().stream()
                .map(phase -> new PlanPhase(planId, phase.sequence(), phase.description(),
                        phase.startDate(), phase.endDate(), phase.targetPercent(),
                        phase.plannedValue(), phase.plannedPercent(), phase.withheldPercent(),
                        phase.physical(), phase.onTarget()))
                .toList());
        packages.saveAll(output.packages().stream()
                .map(block -> new PlanWorkPackage(planId, block.category(), block.workPart(),
                        block.value(), block.startDate(), block.endDate(), block.gangs(),
                        block.lineCount(), block.normed()))
                .toList());
        labour.saveAll(output.labour().stream()
                .map(row -> new PlanLabourDemand(planId, row.month().toString(), row.skillCode(),
                        row.skilled(), row.manDays(), row.headCount(), row.cost()))
                .toList());
        material.saveAll(output.material().stream()
                .map(row -> new PlanMaterialDemand(planId, row.month().toString(),
                        row.materialCode(), row.materialName(), row.unitCode(), row.requiredQty(),
                        row.procureQty(), row.procureValue(), row.orderByDate()))
                .toList());
        cash.saveAll(output.cash().stream()
                .map(row -> new PlanCashFlow(planId, row.month().toString(), row.labourCost(),
                        row.materialCost(), row.staffCost(), row.plantAndTransport(),
                        row.setupCost(), row.overheadCost(), row.totalOutflow(), row.grossBilled(),
                        row.deductions(), row.netReceived(), row.netMovement(), row.cumulative()))
                .toList());

        List<PlanNote> rows = new ArrayList<>();
        int order = 0;
        for (PlanOutput.Finding finding : output.findings()) {
            rows.add(new PlanNote(planId, PlanNote.FINDING, finding.severity().name(), null, null,
                    finding.message(), order++));
        }
        for (PlanOutput.Assumption assumption : output.assumptions()) {
            rows.add(new PlanNote(planId, PlanNote.ASSUMPTION, null, assumption.subject(),
                    assumption.value(), assumption.because(), order++));
        }
        notes.saveAll(rows);
    }

    private PlanResponse read(ExecutionPlan plan) {
        return new PlanResponse(plan.getId(), plan.getProjectId(), plan.getName(),
                plan.getScenario(), plan.getCommencementDate(), plan.getAllowedDays(),
                plan.getQuotedPercent(), plan.getContractValue(),
                plan.getWorkTypeProfileId(),
                plan.getWorkTypeProfileId() == null ? null
                        : profiles.findById(plan.getWorkTypeProfileId())
                                .map(WorkTypeProfile::getName).orElse(null),
                DEFAULT_BILLING_CYCLE_DAYS, plan.getPaymentLagDays(),
                plan.isBaselined(), plan.getRevision(),
                phases.findByPlanId(plan.getId()).stream()
                        .sorted(java.util.Comparator.comparingInt(PlanPhase::getSequenceNo))
                        .map(row -> new PhaseView(row.getSequenceNo(), row.getDescription(),
                                row.getStartDate(), row.getEndDate(), row.getTargetPercent(),
                                row.getPlannedValue(), row.getPlannedPercent(),
                                row.getWithheldPercent(), row.isPhysical(), row.isOnTarget()))
                        .toList(),
                packages.findByPlanId(plan.getId()).stream()
                        .map(row -> new PackageView(row.getWorkCategory(), row.getWorkPart(),
                                row.getValue(), row.getStartDate(), row.getEndDate(),
                                row.getGangs(), row.getLineCount(), row.isNormed()))
                        .toList(),
                labour.findByPlanId(plan.getId()).stream()
                        .map(row -> new LabourView(row.getYearMonth(), row.getSkillCode(),
                                row.isSkilled(), row.getManDays(), row.getHeadCount(),
                                row.getCost()))
                        .toList(),
                material.findByPlanId(plan.getId()).stream()
                        .map(row -> new MaterialView(row.getYearMonth(), row.getMaterialCode(),
                                row.getMaterialName(), row.getUnitCode(), row.getRequiredQty(),
                                row.getProcureQty(), row.getProcureValue(), row.getOrderByDate()))
                        .toList(),
                cash.findByPlanId(plan.getId()).stream()
                        .sorted(java.util.Comparator.comparing(PlanCashFlow::getYearMonth))
                        .map(ExecutionPlanService::toView).toList(),
                new WorkingCapitalView(plan.getPeakFundingRequired(), plan.getPeakMonth(),
                        plan.getMoneyBeforeDayOne(), plan.getBreakEvenMonth(),
                        plan.getTotalRetentionHeld(), plan.getRetentionReleasedOn(),
                        plan.getTotalOutflow(), plan.getTotalNetReceipts()),
                notes.findByPlanId(plan.getId()).stream()
                        .sorted(java.util.Comparator.comparingInt(PlanNote::getSortOrder))
                        .map(row -> new NoteView(row.getKind(), row.getSeverity(),
                                row.getSubject(), row.getValue(), row.getMessage()))
                        .toList());
    }

    private static CashView toView(PlanCashFlow row) {
        return new CashView(row.getYearMonth(), row.getLabourCost(), row.getMaterialCost(),
                row.getStaffCost(), row.getPlantTransport(), row.getSetupCost(),
                row.getOverheadCost(), row.getTotalOutflow(), row.getGrossBilled(),
                row.getDeductions(), row.getNetReceived(), row.getNetMovement(),
                row.getCumulative());
    }

    private static PlanResponse toResponse(UUID id, UUID projectId, String name, String scenario,
                                           PlanInputAssembler.Assembled assembled,
                                           PlanOutput output, boolean baselined, int revision) {
        PlanInput input = assembled.input();
        WorkTypeProfile profile = assembled.profile();
        return new PlanResponse(id, projectId, name, scenario, input.commencementDate(),
                input.allowedDays(), input.quotedPercent(), input.terms().contractValue(),
                profile == null ? null : profile.getId(),
                profile == null ? null : profile.getName(),
                input.terms().billingCycleDays(), input.terms().paymentLagDays(),
                baselined, revision,
                output.phases().stream()
                        .map(phase -> new PhaseView(phase.sequence(), phase.description(),
                                phase.startDate(), phase.endDate(), phase.targetPercent(),
                                phase.plannedValue(), phase.plannedPercent(),
                                phase.withheldPercent(), phase.physical(), phase.onTarget()))
                        .toList(),
                output.packages().stream()
                        .map(block -> new PackageView(block.category(), block.workPart(),
                                block.value(), block.startDate(), block.endDate(), block.gangs(),
                                block.lineCount(), block.normed()))
                        .toList(),
                output.labour().stream()
                        .map(row -> new LabourView(row.month().toString(), row.skillCode(),
                                row.skilled(), row.manDays(), row.headCount(), row.cost()))
                        .toList(),
                output.material().stream()
                        .map(row -> new MaterialView(row.month().toString(), row.materialCode(),
                                row.materialName(), row.unitCode(), row.requiredQty(),
                                row.procureQty(), row.procureValue(), row.orderByDate()))
                        .toList(),
                output.cash().stream()
                        .map(row -> new CashView(row.month().toString(), row.labourCost(),
                                row.materialCost(), row.staffCost(), row.plantAndTransport(),
                                row.setupCost(), row.overheadCost(), row.totalOutflow(),
                                row.grossBilled(), row.deductions(), row.netReceived(),
                                row.netMovement(), row.cumulative()))
                        .toList(),
                new WorkingCapitalView(output.workingCapital().peakFundingRequirement(),
                        month(output.workingCapital().peakMonth()),
                        output.workingCapital().moneyBeforeDayOne(),
                        month(output.workingCapital().breakEvenMonth()),
                        output.workingCapital().totalRetentionHeld(),
                        output.workingCapital().retentionReleasedOn(),
                        output.workingCapital().totalOutflow(),
                        output.workingCapital().totalNetReceipts()),
                notes(output));
    }

    private static List<NoteView> notes(PlanOutput output) {
        List<NoteView> views = new ArrayList<>();
        output.findings().forEach(finding -> views.add(new NoteView(PlanNote.FINDING,
                finding.severity().name(), null, null, finding.message())));
        output.assumptions().forEach(assumption -> views.add(new NoteView(PlanNote.ASSUMPTION,
                null, assumption.subject(), assumption.value(), assumption.because())));
        return views;
    }

    private static String month(YearMonth month) {
        return month == null ? null : month.toString();
    }

    private static String name(GeneratePlanRequest request) {
        return request.name() == null || request.name().isBlank()
                ? "Execution plan" : request.name().strip();
    }

    private static String scenario(GeneratePlanRequest request) {
        return request.scenario() == null || request.scenario().isBlank()
                ? "BASE" : request.scenario().strip();
    }
}

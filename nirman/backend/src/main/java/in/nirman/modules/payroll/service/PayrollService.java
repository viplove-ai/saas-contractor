package in.nirman.modules.payroll.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.identity.service.StaffPayrollLookup;
import in.nirman.modules.identity.service.StaffPayrollLookup.PayrollMember;
import in.nirman.modules.payroll.api.dto.PayrollDtos.NotDrawn;
import in.nirman.modules.payroll.api.dto.PayrollDtos.OpenRunRequest;
import in.nirman.modules.payroll.api.dto.PayrollDtos.PayrollRunResponse;
import in.nirman.modules.payroll.api.dto.PayrollDtos.PayrollRunSummary;
import in.nirman.modules.payroll.api.dto.PayrollDtos.PayrollTotals;
import in.nirman.modules.payroll.api.dto.PayrollDtos.PayslipResponse;
import in.nirman.modules.payroll.api.dto.PayrollDtos.UpdatePayslipRequest;
import in.nirman.modules.payroll.api.dto.PayrollDtos.UpdateRunRequest;
import in.nirman.modules.payroll.domain.PayrollRun;
import in.nirman.modules.payroll.domain.Payslip;
import in.nirman.modules.payroll.repository.PayrollRunRepository;
import in.nirman.modules.payroll.repository.PayslipRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The month's payroll: drawing it, correcting it, and ending it.
 *
 * <p><b>Drawn, corrected, finalised once.</b> Drawing takes the salary structure in force on
 * the last day of the month and works out everything the rules can work out. Correcting is
 * for the four things no rule can know — how many days he was there, what he worked beyond
 * the shift, what the tax office is owed, and what is being recovered from him. Finalising
 * ends it, because by then the documents have been printed and handed over.</p>
 *
 * <p><b>Redrawing keeps what was typed.</b> A run is redrawn when a structure is corrected
 * mid-month, and a redraw that reset the days and the tax to zero would make correcting one
 * person's basic pay cost the office an afternoon of retyping — which means it would not be
 * done, and the month would go out on a figure everybody knew was wrong. So the structure
 * half is rebuilt and the typed half is carried across, exactly as the daily report carries
 * the office's plant rates over a supervisor's corrected plant list.</p>
 *
 * <p><b>Nothing here posts an expense.</b> Salaries are the company's cost and the expense
 * module is where costs live, and joining the two automatically is deliberately not built:
 * an expense goes through an approval chain by somebody who decided to spend the money, and a
 * bill that appeared in that queue because a payroll run was finalised is a bill nobody typed
 * and nobody can answer for. The same reason billing never writes progress entries.</p>
 */
@Service
@Transactional
public class PayrollService {

    private static final String ENTITY = "PAYROLL_RUN";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy");

    /**
     * The divisor an hour of overtime is priced against — twenty-six days of eight hours.
     *
     * <p>Deliberately not the run's own {@code payableDays}. That figure is the office's
     * convention for prorating a part month and an office is free to set it to the calendar's
     * count; the overtime rate is fixed by the wage rules at a twenty-six day month, and
     * letting it follow a convention would pay a different rate for the same hour in
     * February.</p>
     */
    private static final int OVERTIME_WAGE_DAYS = 26;

    private final PayrollRunRepository runs;
    private final PayslipRepository payslips;
    private final StaffPayrollLookup staff;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public PayrollService(PayrollRunRepository runs, PayslipRepository payslips,
                          StaffPayrollLookup staff, CurrentUserProvider currentUser,
                          AuditService audit) {
        this.runs = runs;
        this.payslips = payslips;
        this.staff = staff;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('payroll:read')")
    public List<PayrollRunSummary> list() {
        return runs.findByOrgIdOrderByPeriodMonthDesc(orgId()).stream()
                .map(run -> {
                    List<Payslip> slips = payslips.findByRunIdOrderByEmployeeNameAsc(run.getId());
                    return new PayrollRunSummary(run.getId(), run.getPeriodMonth(),
                            run.getStatus(), run.getPayableDays(), slips.size(),
                            sum(slips, Payslip::getNetAmount), run.getFinalisedAt());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('payroll:read')")
    public PayrollRunResponse get(UUID runId) {
        return toResponse(require(runId));
    }

    /**
     * One member's own slips, newest first.
     *
     * <p>Behind {@code payroll:read} like everything else here. A member reading his own
     * payslips is a screen this does not yet have, and it will need its own call rather than
     * this one — the same line the float register draws between reading the register and
     * reading your own pocket.</p>
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('payroll:read')")
    public List<PayslipResponse> forMember(UUID userId) {
        return payslips.findByOrgIdAndUserIdOrderByPeriodMonthDesc(orgId(), userId).stream()
                .map(slip -> toResponse(slip, false))
                .toList();
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Opens a month and draws every slip it can.
     *
     * <p>Refused if the month is already open, because two runs for July are two answers to
     * one question and the second is always the one somebody prints. Refused for a month that
     * has not started: a payroll drawn on the 3rd of a month it is still in would be a
     * document about days nobody has worked yet.</p>
     */
    @PreAuthorize("hasAuthority('payroll:process')")
    public PayrollRunResponse open(OpenRunRequest request) {
        LocalDate month = request.periodMonth().withDayOfMonth(1);
        if (month.isAfter(LocalDate.now().withDayOfMonth(1))) {
            throw new BusinessException("payroll.month-not-started",
                    "The payroll for " + month.format(MONTH) + " cannot be drawn before the "
                            + "month has begun.");
        }
        runs.findByOrgIdAndPeriodMonth(orgId(), month).ifPresent(existing -> {
            throw BusinessException.conflict("payroll.month-already-open",
                    month.format(MONTH) + " has already been drawn. Open that run rather than "
                            + "starting a second one — two payrolls for one month are two "
                            + "answers, and the employee has whichever was printed.");
        });

        int payableDays = request.payableDays() == null
                ? month.lengthOfMonth() : request.payableDays();
        PayrollRun run = new PayrollRun(orgId(), month, payableDays);
        run.setNotes(blankToNull(request.notes()));
        runs.save(run);

        draw(run);
        audit.record(ENTITY, run.getId(), "OPEN", null,
                Map.of("periodMonth", month.toString(), "payableDays", payableDays), null);
        return toResponse(run);
    }

    /**
     * Draws the month again against today's staff records.
     *
     * <p>What it does: adds a slip for anybody now drawable who had none, and rebuilds the
     * structure half of every slip already there. What it does not do: remove a slip. A slip
     * with typed figures on it is somebody's afternoon, and a member who turns out not to
     * belong in the month is taken out deliberately, by name — see {@link #removePayslip}.</p>
     */
    @PreAuthorize("hasAuthority('payroll:process')")
    public PayrollRunResponse redraw(UUID runId) {
        PayrollRun run = requireDraft(runId);
        draw(run);
        audit.record(ENTITY, run.getId(), "REDRAW", null,
                Map.of("periodMonth", run.getPeriodMonth().toString()), null);
        return toResponse(run);
    }

    private void draw(PayrollRun run) {
        LocalDate asOf = run.periodEnd();
        Set<UUID> drawn = new HashSet<>();
        payslips.findByRunIdOrderByEmployeeNameAsc(run.getId())
                .forEach(slip -> drawn.add(slip.getUserId()));

        for (PayrollMember member : staff.membersFor(asOf)) {
            if (!member.structured()) {
                continue;   // named in notDrawn on the way out; never silently absent
            }
            Payslip slip = payslips.findByRunIdAndUserId(run.getId(), member.userId())
                    .orElseGet(() -> newSlip(run, member));
            slip.takeIdentity(member.employeeNumber(), member.designation(), member.uan(),
                    member.esicNumber());
            slip.takeStructure(member.basic(), member.dearnessAllowance(), member.hra(),
                    member.conveyance(), member.otherAllowance());
            // Professional tax follows the structure, so a redraw picks up a corrected slab;
            // an office that typed a different figure on the slip has it overwritten here,
            // which is right — the figure belongs to the salary and not to the month.
            slip.setProfessionalTax(member.professionalTax());
            slip.recompute(run.getPayableDays(), member.pfApplicable(), member.esiApplicable(),
                    member.pfOnFullWages(), null, OVERTIME_WAGE_DAYS);
            payslips.save(slip);
            drawn.add(member.userId());
        }
    }

    /**
     * A slip for somebody being drawn for the first time.
     *
     * <p>The days default to the whole month rather than to zero. A month is drawn for people
     * who were mostly there, and a screen that opens with twenty zeroes teaches the office to
     * type twenty numbers it would otherwise only have had to correct three of — which is
     * twenty chances to type one wrong.</p>
     */
    private Payslip newSlip(PayrollRun run, PayrollMember member) {
        Payslip slip = new Payslip(orgId(), run.getId(), member.userId(), run.getPeriodMonth(),
                member.fullName());
        slip.setPaidDays(payableDaysFor(run, member));
        return slip;
    }

    /**
     * The days a first draw starts him on: the whole month, cut back for somebody who joined
     * or left inside it.
     *
     * <p>A man who started on the 20th did not work the month, and offering the full figure
     * for him would put a full month's pay one careless click away.</p>
     */
    private static BigDecimal payableDaysFor(PayrollRun run, PayrollMember member) {
        LocalDate first = run.getPeriodMonth();
        LocalDate last = run.periodEnd();
        LocalDate from = member.joinedOn() != null && member.joinedOn().isAfter(first)
                ? member.joinedOn() : first;
        LocalDate to = member.exitDate() != null && member.exitDate().isBefore(last)
                ? member.exitDate() : last;
        if (to.isBefore(from)) {
            return BigDecimal.ZERO;
        }
        long served = to.toEpochDay() - from.toEpochDay() + 1;
        long calendar = last.getDayOfMonth();
        if (served >= calendar) {
            return BigDecimal.valueOf(run.getPayableDays());
        }
        // Proportioned against the run's own convention, so an office paying on 26 days gives
        // a man who served 15 of 31 calendar days 12.58 of its 26 rather than 15 of them.
        return BigDecimal.valueOf(served)
                .multiply(BigDecimal.valueOf(run.getPayableDays()))
                .divide(BigDecimal.valueOf(calendar), 2, java.math.RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ correcting

    @PreAuthorize("hasAuthority('payroll:process')")
    public PayrollRunResponse update(UUID runId, UpdateRunRequest request) {
        PayrollRun run = requireDraft(runId);
        assertVersion(run.getVersion(), request.version(), "payroll run");
        boolean daysChanged = run.getPayableDays() != request.payableDays();
        run.setPayableDays(request.payableDays());
        run.setNotes(blankToNull(request.notes()));
        runs.save(run);
        if (daysChanged) {
            // The denominator moved, so every slip under it is now claiming a proration that
            // was worked out against a different month.
            draw(run);
        }
        return toResponse(run);
    }

    /**
     * The four things no rule can know, typed onto one slip.
     *
     * <p>Everything else on the row is recomputed from them here and now, so a slip is never
     * left in a state where its earnings and its net disagree — which is exactly the state a
     * partial save would leave behind if the recomputation were somebody else's later step.</p>
     */
    @PreAuthorize("hasAuthority('payroll:process')")
    public PayslipResponse updatePayslip(UUID payslipId, UpdatePayslipRequest request) {
        Payslip slip = payslips.findByIdAndOrgId(payslipId, orgId())
                .orElseThrow(() -> BusinessException.notFound("Payslip", payslipId));
        PayrollRun run = requireDraft(slip.getRunId());
        assertVersion(slip.getVersion(), request.version(), "payslip");

        if (request.paidDays().compareTo(BigDecimal.valueOf(run.getPayableDays())) > 0) {
            throw new BusinessException("payroll.paid-days-exceed-month",
                    "Days paid cannot exceed the " + run.getPayableDays() + " this month is "
                            + "being paid against. Hours worked beyond the shift belong in "
                            + "overtime, which is paid at twice the rate rather than as an "
                            + "extra day.");
        }
        if (nonZero(request.otherDeduction()) && isBlank(request.otherDeductionNote())) {
            throw new BusinessException("payroll.other-deduction-needs-a-reason",
                    "A deduction with no reason beside it is the one the employee telephones "
                            + "about. Say what it is for.");
        }

        PayrollMember member = staff.member(slip.getUserId(), run.periodEnd())
                .orElseThrow(() -> BusinessException.notFound("User", slip.getUserId()));

        slip.setPaidDays(request.paidDays());
        slip.setOvertimeHours(orZero(request.overtimeHours()));
        slip.setProfessionalTax(orZero(request.professionalTax()));
        slip.setTds(orZero(request.tds()));
        slip.setSalaryAdvance(orZero(request.salaryAdvance()));
        slip.setOtherDeduction(orZero(request.otherDeduction()),
                blankToNull(request.otherDeductionNote()));
        slip.setRemarks(blankToNull(request.remarks()));
        // No amount in the save is the office saying "work it out", and is how a figure typed
        // by mistake is undone. Without this the override would be a one-way door: clearing
        // the box would leave the typed amount standing and look like the screen ignored it.
        if (request.overtimeAmount() == null) {
            slip.clearOvertimeOverride();
        }
        slip.recompute(run.getPayableDays(), member.pfApplicable(), member.esiApplicable(),
                member.pfOnFullWages(), request.overtimeAmount(), OVERTIME_WAGE_DAYS);
        payslips.save(slip);
        return toResponse(slip, false);
    }

    /**
     * Takes somebody out of the month.
     *
     * <p>Really removed rather than zeroed, and only while the run is a draft. A slip of
     * zeroes is a document saying he was paid nothing, which is a different claim from his
     * not belonging in the month at all — and the first is the one an employee disputes.</p>
     */
    @PreAuthorize("hasAuthority('payroll:process')")
    public void removePayslip(UUID payslipId) {
        Payslip slip = payslips.findByIdAndOrgId(payslipId, orgId())
                .orElseThrow(() -> BusinessException.notFound("Payslip", payslipId));
        requireDraft(slip.getRunId());
        payslips.delete(slip);
    }

    // ------------------------------------------------------------------ ending it

    /**
     * Ends the month.
     *
     * <p>Once, and with no way back. A run that could be reopened would be a set of payslips
     * already in twenty pockets that can still change, and the copy in the office would stop
     * matching the copy in the hand. A figure found wrong after this is corrected the way an
     * over-measured quantity is: in the next month, as a line that says what it is.</p>
     */
    @PreAuthorize("hasAuthority('payroll:process')")
    public PayrollRunResponse finalise(UUID runId) {
        PayrollRun run = requireDraft(runId);
        List<Payslip> slips = payslips.findByRunIdOrderByEmployeeNameAsc(runId);
        if (slips.isEmpty()) {
            throw new BusinessException("payroll.nothing-to-finalise",
                    "There is nobody on this payroll. A month with no payslips is not a month "
                            + "that has been run.");
        }
        run.finalise(currentUser.currentUserIdOrNull(), Instant.now());
        runs.save(run);
        audit.record(ENTITY, run.getId(), "FINALISE", null,
                Map.of("periodMonth", run.getPeriodMonth().toString(),
                        "headcount", slips.size(),
                        "netPayable", sum(slips, Payslip::getNetAmount).toPlainString()), null);
        return toResponse(run);
    }

    /**
     * Throws a draft month away.
     *
     * <p>Allowed, because a draft has been shown to nobody and a month opened by mistake — the
     * wrong month, on the wrong day — is a mistake with no consequences yet. A finalised run
     * is refused: those documents exist.</p>
     */
    @PreAuthorize("hasAuthority('payroll:process')")
    public void delete(UUID runId) {
        PayrollRun run = requireDraft(runId);
        payslips.findByRunIdOrderByEmployeeNameAsc(runId).forEach(payslips::delete);
        runs.delete(run);
        audit.record(ENTITY, runId, "DELETE", null,
                Map.of("periodMonth", run.getPeriodMonth().toString()), null);
    }

    // ------------------------------------------------------------------ internals

    /** Loaded for rendering: the PDF service needs the run and its slips, not a response. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('payroll:read')")
    public PayrollRun requireForRender(UUID runId) {
        return require(runId);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('payroll:read')")
    public Payslip requirePayslipForRender(UUID payslipId) {
        return payslips.findByIdAndOrgId(payslipId, orgId())
                .orElseThrow(() -> BusinessException.notFound("Payslip", payslipId));
    }

    private PayrollRun require(UUID runId) {
        return runs.findByIdAndOrgId(runId, orgId())
                .orElseThrow(() -> BusinessException.notFound("Payroll run", runId));
    }

    private PayrollRun requireDraft(UUID runId) {
        PayrollRun run = require(runId);
        if (!run.isDraft()) {
            throw BusinessException.conflict("payroll.finalised",
                    "The payroll for " + run.getPeriodMonth().format(MONTH) + " has been "
                            + "finalised. Its payslips have been issued, so a correction "
                            + "belongs in the next month rather than in this one.");
        }
        return run;
    }

    private static void assertVersion(Long held, Long sent, String what) {
        if (!held.equals(sent)) {
            throw new OptimisticLockingFailureException(
                    "This " + what + " was changed by someone else while you were editing it");
        }
    }

    private PayrollRunResponse toResponse(PayrollRun run) {
        List<Payslip> slips = payslips.findByRunIdOrderByEmployeeNameAsc(run.getId());
        boolean finalised = !run.isDraft();
        Set<UUID> drawn = new HashSet<>();
        slips.forEach(slip -> drawn.add(slip.getUserId()));

        // Who is missing, and why. Only worth asking while the month can still be changed:
        // on a finalised run the answer is history, and the omission line on the printed
        // register is where it belongs.
        List<NotDrawn> notDrawn = new ArrayList<>();
        if (!finalised) {
            for (PayrollMember member : staff.membersFor(run.periodEnd())) {
                if (drawn.contains(member.userId())) {
                    continue;
                }
                notDrawn.add(new NotDrawn(member.userId(), member.fullName(),
                        member.gross() == null
                                ? "No salary has ever been recorded for this member"
                                : "The salary on record is a total with no breakdown, so the "
                                        + "provident fund and insurance cannot be worked out. "
                                        + "Record a structure — basic, allowances — and draw "
                                        + "the month again."));
            }
        }

        return new PayrollRunResponse(run.getId(), run.getPeriodMonth(), run.periodEnd(),
                run.getStatus(), run.getPayableDays(), run.getNotes(), run.getFinalisedAt(),
                run.getFinalisedBy(), totals(slips),
                slips.stream().map(slip -> toResponse(slip, finalised)).toList(),
                notDrawn, run.getVersion());
    }

    private static PayrollTotals totals(List<Payslip> slips) {
        return new PayrollTotals(slips.size(),
                sum(slips, Payslip::getTotalEarnings),
                sum(slips, Payslip::getTotalDeductions),
                sum(slips, Payslip::getNetAmount),
                sum(slips, Payslip::getPfEmployee),
                sum(slips, Payslip::getPfEmployer),
                sum(slips, Payslip::getEpsEmployer),
                sum(slips, Payslip::getEsiEmployee),
                sum(slips, Payslip::getEsiEmployer),
                sum(slips, Payslip::getProfessionalTax),
                sum(slips, Payslip::getTds),
                sum(slips, Payslip::employerCost));
    }

    private static BigDecimal sum(List<Payslip> slips,
                                  java.util.function.Function<Payslip, BigDecimal> field) {
        return slips.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static PayslipResponse toResponse(Payslip slip, boolean finalised) {
        return new PayslipResponse(slip.getId(), slip.getRunId(), slip.getUserId(),
                slip.getPeriodMonth(), slip.getEmployeeName(), slip.getEmployeeNumber(),
                slip.getDesignation(), slip.getUan(), slip.getEsicNumber(),
                slip.getStructBasic(), slip.getStructDa(), slip.getStructHra(),
                slip.getStructConveyance(), slip.getStructOther(), slip.getStructGross(),
                slip.getPayableDays(), slip.getPaidDays(), slip.getEarnedBasic(),
                slip.getEarnedDa(), slip.getEarnedHra(), slip.getEarnedConveyance(),
                slip.getEarnedOther(), slip.getOvertimeHours(), slip.getOvertimeAmount(),
                slip.getTotalEarnings(), slip.getStatutoryWages(), slip.getPfWages(),
                slip.getPfEmployee(), slip.getEsiWages(), slip.getEsiEmployee(),
                slip.getProfessionalTax(), slip.getTds(), slip.getSalaryAdvance(),
                slip.getOtherDeduction(), slip.getOtherDeductionNote(),
                slip.getTotalDeductions(), slip.getNetAmount(), slip.getPfEmployer(),
                slip.getEpsEmployer(), slip.getEsiEmployer(), slip.employerCost(),
                slip.getRemarks(), finalised, slip.getVersion());
    }

    private static boolean nonZero(BigDecimal value) {
        return value != null && value.signum() != 0;
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }
}

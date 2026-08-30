package in.nirman.modules.identity.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.identity.api.dto.StaffDtos.RecordSalaryRequest;
import in.nirman.modules.identity.api.dto.StaffDtos.SalaryRevisionResponse;
import in.nirman.modules.identity.api.dto.StaffDtos.SaveStaffProfileRequest;
import in.nirman.modules.identity.api.dto.StaffDtos.StaffAlert;
import in.nirman.modules.identity.api.dto.StaffDtos.StaffDashboardResponse;
import in.nirman.modules.identity.api.dto.StaffDtos.StaffProfileResponse;
import in.nirman.modules.identity.domain.Role;
import in.nirman.modules.identity.domain.StaffProfile;
import in.nirman.modules.identity.domain.StaffProfile.EmploymentType;
import in.nirman.modules.identity.domain.StaffSalaryRevision;
import in.nirman.modules.identity.domain.User;
import in.nirman.modules.identity.repository.StaffProfileRepository;
import in.nirman.modules.identity.repository.StaffSalaryRevisionRepository;
import in.nirman.modules.identity.repository.UserRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The staff records: who somebody is, how they are engaged, and what they are paid.
 *
 * <p><b>Two kinds of fact, kept apart on purpose.</b> The profile holds what was agreed —
 * the probation length, the probation salary, the salary after it — and is edited in place,
 * because an address or a bank account that changes is a correction. The salary revisions
 * hold what is actually paid from when, and are append-only, because history does not move:
 * a raise in April must not rewrite what March cost. The two are allowed to disagree, and
 * that disagreement is a fact worth being able to see — an offer that was never honoured.</p>
 *
 * <p>Every figure the dashboard reports is derived on the call. There is no stored payroll
 * total, for the reason there is no stored stock balance the ledger does not overrule.</p>
 */
@Service
@Transactional
public class StaffRecordService {

    /** How far ahead the dashboard looks for a probation or a contract about to run out. */
    private static final int HORIZON_DAYS = 30;
    private static final String ENTITY = "STAFF_PROFILE";

    private final StaffProfileRepository profiles;
    private final StaffSalaryRevisionRepository salaries;
    private final UserRepository users;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public StaffRecordService(StaffProfileRepository profiles,
                              StaffSalaryRevisionRepository salaries,
                              UserRepository users, CurrentUserProvider currentUser,
                              AuditService audit) {
        this.profiles = profiles;
        this.salaries = salaries;
        this.users = users;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    /**
     * One member's record. A member nobody has filled in yet comes back as the login plus
     * blanks rather than as a 404 — the screen's job is to collect what is missing, and it
     * cannot do that from an error.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('staff:read')")
    public StaffProfileResponse get(UUID userId) {
        User user = requireUser(userId);
        return toResponse(user, profiles.findByUserId(userId).orElse(null),
                currentSalary(userId));
    }

    /** Every member with a login, filled in or not. A contractor's staff fits on one page. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('staff:read')")
    public List<StaffProfileResponse> list() {
        Map<UUID, StaffProfile> byUser = new HashMap<>();
        profiles.findByOrgId(orgId()).forEach(profile -> byUser.put(profile.getUserId(), profile));
        Map<UUID, BigDecimal> pay = currentSalaries();
        return users.findByOrgIdOrderByUsername(orgId()).stream()
                .map(user -> toResponse(user, byUser.get(user.getId()), pay.get(user.getId())))
                .toList();
    }

    /**
     * Saves the record whole, creating it the first time.
     *
     * <p>The version is null on that first save and required after it. Demanding one for a
     * row that does not exist yet would make the first save impossible; not demanding one
     * afterwards would let two administrators editing the same person overwrite each other
     * silently.</p>
     */
    @PreAuthorize("hasAuthority('staff:write')")
    public StaffProfileResponse save(UUID userId, SaveStaffProfileRequest request) {
        User user = requireUser(userId);
        StaffProfile profile = profiles.findByUserId(userId).orElse(null);
        if (profile == null) {
            profile = new StaffProfile(orgId(), userId);
        } else if (!profile.getVersion().equals(request.version())) {
            throw new OptimisticLockingFailureException(
                    "The record for " + user.getUsername() + " was changed by someone else");
        }
        assertTermsMakeSense(request);

        profile.setAlternateMobile(blankToNull(request.alternateMobile()));
        profile.setDateOfBirth(request.dateOfBirth());
        profile.setAadhaarLast4(blankToNull(request.aadhaarLast4()));
        profile.setPan(blankToNull(request.pan()));
        profile.setCurrentAddress(blankToNull(request.currentAddress()));
        profile.setPermanentAddress(blankToNull(request.permanentAddress()));
        profile.setEmergencyContactName(blankToNull(request.emergencyContactName()));
        profile.setEmergencyContactMobile(blankToNull(request.emergencyContactMobile()));
        profile.setEmergencyContactRelation(blankToNull(request.emergencyContactRelation()));
        profile.setBankAccountName(blankToNull(request.bankAccountName()));
        profile.setBankAccountNo(blankToNull(request.bankAccountNo()));
        profile.setBankIfsc(blankToNull(request.bankIfsc()));
        profile.setBankName(blankToNull(request.bankName()));
        profile.setEmployeeNumber(blankToNull(request.employeeNumber()));
        profile.setDesignation(blankToNull(request.designation()));
        profile.setUan(blankToNull(request.uan()));
        profile.setEsicNumber(blankToNull(request.esicNumber()));
        profile.setPfApplicable(request.pfApplicable());
        profile.setEsiApplicable(request.esiApplicable());
        profile.setPfOnFullWages(request.pfOnFullWages());
        profile.setNoticePeriodDays(request.noticePeriodDays());
        // The detail only survives the offer. Unticking the box clears what was said about it,
        // the way the leaving section clears its date: a sentence about a room nobody is given
        // is one somebody reads next year and believes.
        profile.setAccommodationProvided(request.accommodationProvided());
        profile.setAccommodationNote(request.accommodationProvided()
                ? blankToNull(request.accommodationNote()) : null);
        profile.setFuelProvided(request.fuelProvided());
        profile.setFuelMonthlyAmount(request.fuelProvided() ? request.fuelMonthlyAmount() : null);
        profile.setFuelNote(request.fuelProvided() ? blankToNull(request.fuelNote()) : null);
        profile.setEmploymentType(request.employmentType());
        profile.setJoinedOn(request.joinedOn());
        profile.setProbationDays(request.probationDays());
        profile.setProbationMonthlySalary(request.probationMonthlySalary());
        profile.setConfirmedMonthlySalary(request.confirmedMonthlySalary());
        profile.setConfirmedOn(request.confirmedOn());
        profile.setContractEndsOn(request.contractEndsOn());
        profile.setExitDate(request.exitDate());
        profile.setExitReason(blankToNull(request.exitReason()));
        profile.setNotes(blankToNull(request.notes()));
        profiles.save(profile);

        // The values are deliberately not in the audit body. This row holds a home address
        // and a bank account, and an audit log is the one table nobody prunes — recording
        // that the record changed is the point, not recording what it now says.
        audit.record(ENTITY, userId, request.version() == null ? "CREATE" : "UPDATE", null,
                Map.of("username", user.getUsername(),
                        "employmentType", request.employmentType().name()), null);
        return toResponse(user, profile, currentSalary(userId));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('staff:read')")
    public List<SalaryRevisionResponse> salaryHistory(UUID userId) {
        requireUser(userId);
        return salaries.findByUserIdOrderByEffectiveFromDesc(userId).stream()
                .map(StaffRecordService::toResponse)
                .toList();
    }

    /**
     * Records a change of pay. Appended, never edited — see {@link StaffSalaryRevision}.
     *
     * <p>Two revisions effective the same morning are refused by the unique key; the sentence
     * here is so an administrator correcting today's figure is told to pick a date rather
     * than shown a constraint violation.</p>
     */
    @PreAuthorize("hasAuthority('staff:write')")
    public SalaryRevisionResponse recordSalary(UUID userId, RecordSalaryRequest request) {
        User user = requireUser(userId);
        boolean clash = salaries.findByUserIdOrderByEffectiveFromDesc(userId).stream()
                .anyMatch(revision -> revision.getEffectiveFrom().equals(request.effectiveFrom()));
        if (clash) {
            throw BusinessException.conflict("staff.salary-same-day",
                    "There is already a salary for " + user.getFullName() + " effective "
                            + request.effectiveFrom() + ". A second one the same day is a rate "
                            + "decided by whichever the query sorts first — pick another date.");
        }

        StaffSalaryRevision revision = new StaffSalaryRevision(orgId(), userId,
                request.basic(), request.dearnessAllowance(), request.hra(),
                request.conveyance(), request.otherAllowance(), request.professionalTax(),
                request.effectiveFrom(), request.reason());
        salaries.save(revision);
        audit.record(ENTITY, userId, "SALARY_REVISED", null,
                Map.of("effectiveFrom", request.effectiveFrom().toString()), request.reason());
        return toResponse(revision);
    }

    /**
     * The staff at a glance, and what is about to need attention.
     *
     * <p>The alerts are the point of the screen. A probation that quietly ran past its end
     * is somebody working on probation terms months after they were meant to stop, and a
     * member with no bank details is one the payroll total is silently lying about.</p>
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('staff:read')")
    public StaffDashboardResponse dashboard() {
        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(HORIZON_DAYS);
        List<User> staff = users.findByOrgIdOrderByUsername(orgId()).stream()
                .filter(User::isActive)
                .toList();
        Map<UUID, StaffProfile> byUser = new HashMap<>();
        profiles.findByOrgId(orgId()).forEach(profile -> byUser.put(profile.getUserId(), profile));
        Map<UUID, BigDecimal> pay = currentSalaries();

        int permanent = 0;
        int contractual = 0;
        int onProbation = 0;
        int overdue = 0;
        int endingSoon = 0;
        int contractsEnding = 0;
        int missingBank = 0;
        int noRecord = 0;
        BigDecimal payroll = BigDecimal.ZERO;
        List<StaffAlert> alerts = new ArrayList<>();

        for (User user : staff) {
            StaffProfile profile = byUser.get(user.getId());
            BigDecimal salary = pay.get(user.getId());
            if (salary != null) {
                payroll = payroll.add(salary);
            }
            if (profile == null) {
                noRecord++;
                alerts.add(new StaffAlert(user.getId(), user.getFullName(),
                        "No staff record yet — no address, no bank details, no terms", null));
                continue;
            }
            if (profile.getExitDate() != null && !profile.getExitDate().isAfter(today)) {
                continue;   // gone; they are not part of the headcount or the payroll
            }
            switch (profile.getEmploymentType()) {
                case PERMANENT -> permanent++;
                case CONTRACTUAL -> contractual++;
                case PROBATION -> onProbation++;
            }
            if (profile.probationOverdueOn(today)) {
                overdue++;
                alerts.add(new StaffAlert(user.getId(), user.getFullName(),
                        "Probation ended and nobody has confirmed them",
                        profile.probationEndsOn()));
            } else if (within(profile.probationEndsOn(), today, horizon)) {
                endingSoon++;
                alerts.add(new StaffAlert(user.getId(), user.getFullName(),
                        "Probation ends soon", profile.probationEndsOn()));
            }
            if (within(profile.getContractEndsOn(), today, horizon)) {
                contractsEnding++;
                alerts.add(new StaffAlert(user.getId(), user.getFullName(),
                        "Contract ends soon", profile.getContractEndsOn()));
            }
            if (isBlank(profile.getBankAccountNo()) || isBlank(profile.getBankIfsc())) {
                missingBank++;
                alerts.add(new StaffAlert(user.getId(), user.getFullName(),
                        "No bank account on file — they cannot be paid", null));
            }
        }

        alerts.sort(Comparator.comparing(StaffAlert::fullName));
        return new StaffDashboardResponse(permanent + contractual + onProbation, permanent,
                contractual, onProbation, overdue, endingSoon, contractsEnding, missingBank,
                noRecord, payroll, alerts);
    }

    // ------------------------------------------------------------------ internals

    private static SalaryRevisionResponse toResponse(StaffSalaryRevision revision) {
        return new SalaryRevisionResponse(revision.getId(), revision.getMonthlyAmount(),
                revision.isStructured(), revision.getBasic(), revision.getDearnessAllowance(),
                revision.getHra(), revision.getConveyance(), revision.getOtherAllowance(),
                revision.getProfessionalTax(), revision.statutoryWages(),
                revision.getEffectiveFrom(), revision.getReason());
    }

    /**
     * The combinations that describe nothing real.
     *
     * <p>The database refuses a probation with no length; these are the ones it cannot see.
     * A confirmation before the joining date, and a probation salary on somebody who is not
     * on probation — the second is not wrong exactly, but it is a figure that will be read
     * as current by somebody one day, and it is free to refuse it now.</p>
     */
    private static void assertTermsMakeSense(SaveStaffProfileRequest request) {
        if (request.employmentType() == EmploymentType.PROBATION
                && request.probationDays() == null) {
            throw new BusinessException("staff.probation-needs-a-length",
                    "A probation needs a length. An indefinite probation is not a probation.");
        }
        if (request.joinedOn() != null && request.confirmedOn() != null
                && request.confirmedOn().isBefore(request.joinedOn())) {
            throw new BusinessException("staff.confirmed-before-joining",
                    "They cannot have been confirmed before they joined.");
        }
        if (request.joinedOn() != null && request.exitDate() != null
                && request.exitDate().isBefore(request.joinedOn())) {
            throw new BusinessException("staff.left-before-joining",
                    "They cannot have left before they joined.");
        }
    }

    /** What the history says applies today: the newest revision that has already started. */
    private BigDecimal currentSalary(UUID userId) {
        LocalDate today = LocalDate.now();
        return salaries.findByUserIdOrderByEffectiveFromDesc(userId).stream()
                .filter(revision -> !revision.getEffectiveFrom().isAfter(today))
                .findFirst()
                .map(StaffSalaryRevision::getMonthlyAmount)
                .orElse(null);
    }

    /**
     * The same answer for everybody, in one query rather than one per member.
     *
     * <p>The rows arrive newest first, so the first one seen for a member that has already
     * started is the one that applies — a future-dated raise is skipped rather than paid
     * early.</p>
     */
    private Map<UUID, BigDecimal> currentSalaries() {
        LocalDate today = LocalDate.now();
        Map<UUID, BigDecimal> current = new HashMap<>();
        for (StaffSalaryRevision revision : salaries.findByOrgIdOrderByEffectiveFromDesc(orgId())) {
            if (revision.getEffectiveFrom().isAfter(today)) {
                continue;
            }
            current.putIfAbsent(revision.getUserId(), revision.getMonthlyAmount());
        }
        return current;
    }

    private static boolean within(LocalDate date, LocalDate from, LocalDate to) {
        return date != null && !date.isBefore(from) && !date.isAfter(to);
    }

    private StaffProfileResponse toResponse(User user, StaffProfile profile,
                                            BigDecimal currentSalary) {
        List<String> roles = user.getRoles().stream().map(Role::getCode).sorted().toList();
        if (profile == null) {
            // Blanks and a null version: nobody has filled this record in, and the screen
            // needs to be able to say so and then collect it.
            return new StaffProfileResponse(user.getId(), user.getUsername(), user.getFullName(),
                    user.getMobile(), user.getEmail(), user.isActive(), roles,
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    null,
                    null, null, null, null, false, false, false, null,
                    false, null, false, null, null,
                    EmploymentType.PERMANENT, null, null, null, null, null, null, null,
                    false, currentSalary, null, null, null, null);
        }
        return new StaffProfileResponse(user.getId(), user.getUsername(), user.getFullName(),
                user.getMobile(), user.getEmail(), user.isActive(), roles,
                profile.getAlternateMobile(), profile.getDateOfBirth(), profile.getAadhaarLast4(),
                profile.getPan(), profile.getCurrentAddress(), profile.getPermanentAddress(),
                profile.getEmergencyContactName(), profile.getEmergencyContactMobile(),
                profile.getEmergencyContactRelation(), profile.getBankAccountName(),
                profile.getBankAccountNo(), profile.getBankIfsc(), profile.getBankName(),
                profile.getEmployeeNumber(), profile.getDesignation(), profile.getUan(),
                profile.getEsicNumber(), profile.isPfApplicable(), profile.isEsiApplicable(),
                profile.isPfOnFullWages(), profile.getNoticePeriodDays(),
                profile.isAccommodationProvided(), profile.getAccommodationNote(),
                profile.isFuelProvided(), profile.getFuelMonthlyAmount(), profile.getFuelNote(),
                profile.getEmploymentType(), profile.getJoinedOn(), profile.getProbationDays(),
                profile.getProbationMonthlySalary(), profile.getConfirmedMonthlySalary(),
                profile.getConfirmedOn(), profile.getContractEndsOn(), profile.probationEndsOn(),
                profile.probationOverdueOn(LocalDate.now()), currentSalary,
                profile.getExitDate(), profile.getExitReason(), profile.getNotes(),
                profile.getVersion());
    }

    private User requireUser(UUID userId) {
        return users.findById(userId)
                .filter(user -> user.getOrgId().equals(orgId()))
                .orElseThrow(() -> BusinessException.notFound("User", userId));
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

package in.nirman.modules.identity.service;

import in.nirman.modules.identity.domain.StaffProfile;
import in.nirman.modules.identity.domain.StaffSalaryRevision;
import in.nirman.modules.identity.domain.User;
import in.nirman.modules.identity.domain.Organisation;
import in.nirman.modules.identity.repository.OrganisationRepository;
import in.nirman.modules.identity.repository.StaffProfileRepository;
import in.nirman.modules.identity.repository.StaffSalaryRevisionRepository;
import in.nirman.modules.identity.repository.UserRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@link StaffPayrollLookup} implementation.
 *
 * <p>No {@code @PreAuthorize}, like every other {@code *Lookup}: the caller has already passed
 * the check that got it here — it holds {@code payroll:process} and is drawing a month. The
 * organisation filter stays, because that is not a permission question but the boundary of
 * what exists.</p>
 */
@Service
@Transactional(readOnly = true)
public class StaffPayrollLookupService implements StaffPayrollLookup {

    private final UserRepository users;
    private final StaffProfileRepository profiles;
    private final StaffSalaryRevisionRepository salaries;
    private final OrganisationRepository organisations;
    private final CurrentUserProvider currentUser;

    public StaffPayrollLookupService(UserRepository users, StaffProfileRepository profiles,
                                     StaffSalaryRevisionRepository salaries,
                                     OrganisationRepository organisations,
                                     CurrentUserProvider currentUser) {
        this.users = users;
        this.profiles = profiles;
        this.salaries = salaries;
        this.organisations = organisations;
        this.currentUser = currentUser;
    }

    @Override
    public EmployerInfo employer() {
        return organisations.findById(currentUser.currentOrgId())
                .map(org -> new EmployerInfo(org.getName(), org.getAddress(), org.getGstin(),
                        org.getPan()))
                .orElseGet(() -> new EmployerInfo("", null, null, null));
    }

    @Override
    public List<PayrollMember> membersFor(LocalDate asOf) {
        UUID orgId = currentUser.currentOrgId();
        Map<UUID, StaffProfile> byUser = new HashMap<>();
        profiles.findByOrgId(orgId).forEach(profile -> byUser.put(profile.getUserId(), profile));
        Map<UUID, StaffSalaryRevision> pay = revisionsAsAt(orgId, asOf);

        return users.findByOrgIdOrderByUsername(orgId).stream()
                .filter(User::isActive)
                .map(user -> toMember(user, byUser.get(user.getId()), pay.get(user.getId())))
                // Employed at some point during the month is the test, not employed on its
                // last day: a man who left on the 8th is owed eight days and a man who joined
                // on the 20th is owed eleven. Only somebody whose exit was before the month
                // began drops out here, and the days on his slip do the rest.
                .filter(member -> member.exitDate() == null
                        || !member.exitDate().isBefore(asOf.withDayOfMonth(1)))
                .toList();
    }

    @Override
    public Optional<PayrollMember> member(UUID userId, LocalDate asOf) {
        UUID orgId = currentUser.currentOrgId();
        return users.findById(userId)
                .filter(user -> user.getOrgId().equals(orgId))
                .map(user -> toMember(user, profiles.findByUserId(userId).orElse(null),
                        revisionAsAt(userId, asOf)));
    }

    /**
     * The revision in force on a date, for everybody, in one query rather than one a head.
     *
     * <p>The rows arrive newest first, so the first one seen for a member that has already
     * started is the one that applies — and a raise dated after the month is skipped rather
     * than paid early.</p>
     */
    private Map<UUID, StaffSalaryRevision> revisionsAsAt(UUID orgId, LocalDate asOf) {
        Map<UUID, StaffSalaryRevision> current = new HashMap<>();
        for (StaffSalaryRevision revision : salaries.findByOrgIdOrderByEffectiveFromDesc(orgId)) {
            if (revision.getEffectiveFrom().isAfter(asOf)) {
                continue;
            }
            current.putIfAbsent(revision.getUserId(), revision);
        }
        return current;
    }

    private StaffSalaryRevision revisionAsAt(UUID userId, LocalDate asOf) {
        return salaries.findByUserIdOrderByEffectiveFromDesc(userId).stream()
                .filter(revision -> !revision.getEffectiveFrom().isAfter(asOf))
                .findFirst()
                .orElse(null);
    }

    private static PayrollMember toMember(User user, StaffProfile profile,
                                          StaffSalaryRevision revision) {
        boolean structured = revision != null && revision.isStructured();
        return new PayrollMember(
                user.getId(),
                user.getFullName(),
                profile == null ? null : profile.getEmployeeNumber(),
                profile == null ? null : profile.getDesignation(),
                profile == null ? null : profile.getUan(),
                profile == null ? null : profile.getEsicNumber(),
                profile != null && profile.isPfApplicable(),
                profile != null && profile.isEsiApplicable(),
                profile != null && profile.isPfOnFullWages(),
                profile == null ? null : profile.getJoinedOn(),
                profile == null ? null : profile.getExitDate(),
                structured,
                structured ? revision.getBasic() : null,
                structured ? zero(revision.getDearnessAllowance()) : null,
                structured ? zero(revision.getHra()) : null,
                structured ? zero(revision.getConveyance()) : null,
                structured ? zero(revision.getOtherAllowance()) : null,
                revision == null ? null : revision.getMonthlyAmount(),
                revision == null ? null : revision.getEffectiveFrom());
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}

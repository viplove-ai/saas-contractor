package in.nirman.modules.identity.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The identity module's read API for payroll: who is on the books this month, and what each
 * of them was engaged on.
 *
 * <p>The boundary rule says the payroll module never touches {@code StaffProfileRepository}
 * or {@code StaffSalaryRevisionRepository}, and this is why it does not have to. What payroll
 * needs of a staff record is a narrow slice of it — the structure, the enrolment numbers and
 * the two flags saying which statutes reach him — and emphatically not his home address or
 * his next of kin. Handing the entity across would hand those across too, and the split
 * between {@code staff:read} and {@code payroll:read} would be a line on paper only.</p>
 *
 * <p>No {@code @PreAuthorize} and no guard on the implementation, like every other
 * {@code *Lookup}: the caller has already passed the check that got it here.</p>
 */
public interface StaffPayrollLookup {

    /**
     * One member as payroll sees him, with the structure that applied on a given day.
     *
     * @param structured    whether a structure was found at all. False for a member with no
     *                      salary record, and false for one whose only revisions predate V54
     *                      and carry a gross with no breakdown. Either way the office is told
     *                      which of the two it is rather than shown a payslip built on a
     *                      split nobody decided
     * @param gross         the whole monthly packet — the sum of the five components, and the
     *                      figure the insurance coverage ceiling is tested against
     * @param structureFrom the date the revision in force took effect, so the run can say
     *                      which one it drew against
     */
    record PayrollMember(
            UUID userId,
            String fullName,
            String employeeNumber,
            String designation,
            String uan,
            String esicNumber,
            boolean pfApplicable,
            boolean esiApplicable,
            boolean pfOnFullWages,
            LocalDate joinedOn,
            LocalDate exitDate,
            boolean structured,
            BigDecimal basic,
            BigDecimal dearnessAllowance,
            BigDecimal hra,
            BigDecimal conveyance,
            BigDecimal otherAllowance,
            BigDecimal gross,
            BigDecimal professionalTax,
            LocalDate structureFrom) {
    }

    /**
     * Everybody the organisation was employing on that date, with the terms in force then.
     *
     * <p>"Employing on that date" is the whole of the filter and it is deliberately generous:
     * somebody who joined on the 20th and somebody who left on the 8th were both employed
     * during the month and both have a part-month packet coming. Only a login that is closed
     * and a member whose exit predates the month drop out.</p>
     *
     * @param asOf the last day of the month being drawn — a raise dated the 15th belongs to
     *             the month it was given in, and reading the structure as at the 1st would
     *             pay the old figure for a month that ended on the new one
     */
    List<PayrollMember> membersFor(LocalDate asOf);

    /** One member, or empty where no login of that id belongs to the organisation. */
    java.util.Optional<PayrollMember> member(UUID userId, LocalDate asOf);

    /**
     * The employer, as it prints at the head of a payslip.
     *
     * <p>Here rather than through an organisation repository because the boundary rule holds
     * for a PDF template as much as for a controller: what payroll needs of the organisation
     * is a letterhead — a name, an address and the two registration numbers a payslip is
     * expected to carry — and not the currency, the timezone or whether the account is
     * active.</p>
     */
    record EmployerInfo(String name, String address, String gstin, String pan) {
    }

    EmployerInfo employer();
}

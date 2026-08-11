/**
 * The four system roles, as the screens name them. Codes are what the JWT carries and the
 * server matches on; the labels are display only. Lives in shared/ because both the admin
 * screens and a member's own profile put a role on the page, and features must not import
 * from one another.
 *
 * <p>A member holds a <em>set</em> of these, not one — a small contractor's site engineer
 * often keeps the books as well. Effective permissions are the union of every role held
 * (docs/00-assumptions #4), and site visibility follows the widest role, so the helpers
 * below all take the whole set rather than a single code.</p>
 */

export const ROLE_LABEL: Record<string, string> = {
  ADMIN: 'Administrator',
  ENGINEER: 'Site engineer',
  SUPERVISOR: 'Site supervisor',
  ACCOUNTANT: 'Accountant',
};

/** The two roles whose data visibility is per-assignment rather than company-wide. */
export const SITE_SCOPED_ROLES = ['ENGINEER', 'SUPERVISOR'] as const;

/**
 * The roles whose token carries the ALL-sites claim. Mirrors {@code AuthService
 * .ALL_SITE_ROLES} on the server — this copy decides wording only, never access.
 */
export const COMPANY_WIDE_ROLES = ['ADMIN', 'ACCOUNTANT'] as const;

export function isSiteScopedRole(roleCode: string): boolean {
  return (SITE_SCOPED_ROLES as readonly string[]).includes(roleCode);
}

/** True when at least one role held is posted per site, so site postings mean something. */
export function hasSiteScopedRole(roleCodes: readonly string[]): boolean {
  return roleCodes.some(isSiteScopedRole);
}

/**
 * True when any role held is company-wide. Deliberately "any", not "all": holding
 * SUPERVISOR alongside ADMIN widens the token to every site rather than narrowing it to the
 * postings, and the screens must say the same thing the server does.
 */
export function seesAllSites(roleCodes: readonly string[]): boolean {
  return roleCodes.some((code) => (COMPANY_WIDE_ROLES as readonly string[]).includes(code));
}

/** Labels for a set of roles, in the order the codes arrive. */
export function roleLabels(roleCodes: readonly string[]): string[] {
  return roleCodes.map((code) => ROLE_LABEL[code] ?? code);
}

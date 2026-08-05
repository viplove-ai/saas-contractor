/**
 * The four system roles, as the screens name them. Codes are what the JWT carries and the
 * server matches on; the labels are display only. Lives in shared/ because both the admin
 * screens and a member's own profile put a role on the page, and features must not import
 * from one another.
 */

export const ROLE_LABEL: Record<string, string> = {
  ADMIN: 'Administrator',
  ENGINEER: 'Site engineer',
  SUPERVISOR: 'Site supervisor',
  ACCOUNTANT: 'Accountant',
};

/** The two roles whose data visibility is per-assignment rather than company-wide. */
export const SITE_SCOPED_ROLES = ['ENGINEER', 'SUPERVISOR'] as const;

export function isSiteScopedRole(roleCode: string): boolean {
  return (SITE_SCOPED_ROLES as readonly string[]).includes(roleCode);
}

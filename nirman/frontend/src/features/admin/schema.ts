import { z } from 'zod';

/** Mirrors the server's bean validation, so a bad field is caught before the round trip. */

const optionalEmail = z
  .string()
  .trim()
  .max(150, 'At most 150 characters')
  .email('Enter a valid email address')
  .optional()
  .or(z.literal(''));

/**
 * Required for a member, unlike email: a supervisor who has locked himself out is reached
 * by phone, and most site staff have no email address to reach them at instead.
 */
const requiredMobile = z
  .string()
  .trim()
  .min(1, 'Enter a mobile number')
  .max(20, 'At most 20 characters')
  .regex(/^[0-9+\-\s]+$/, 'Digits, spaces, + and - only');

/** BCrypt reads at most 72 bytes; anything longer would silently truncate on the server. */
const password = z
  .string()
  .min(8, 'At least 8 characters')
  .max(72, 'At most 72 characters');

/**
 * Onboarding and editing share one shape so the dialog can hold one form object rather
 * than switching between two — a switch that TypeScript can only describe as a union, and
 * that every field access would then have to narrow. The fields that only apply to one of
 * the two modes are validated through {@code mode} instead.
 */
export const userFormSchema = z
  .object({
    mode: z.enum(['create', 'edit']),
    username: z
      .string()
      .trim()
      .min(1, 'Enter a username')
      .max(60, 'At most 60 characters')
      .regex(/^[a-z0-9._-]+$/, 'Lowercase letters, digits, dot, underscore and hyphen only'),
    fullName: z.string().trim().min(1, 'Enter the full name').max(150, 'At most 150 characters'),
    email: optionalEmail,
    mobile: requiredMobile,
    temporaryPassword: z.string(),
    // A set, not a choice: one person can be the site engineer and keep the books.
    roleCodes: z.array(z.string()).min(1, 'Choose at least one role'),
    siteIds: z.array(z.string()),
  })
  .superRefine((form, ctx) => {
    // A password is set once, at onboarding. Changing it later is a reset, which is a
    // different act with its own dialog and its own audit entry.
    if (form.mode !== 'create') {
      return;
    }
    const result = password.safeParse(form.temporaryPassword);
    if (!result.success) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['temporaryPassword'],
        message: result.error.issues[0]?.message ?? 'Enter a first-time password',
      });
    }
  });

export type UserForm = z.infer<typeof userFormSchema>;

export const resetPasswordSchema = z.object({ temporaryPassword: password });

export type ResetPasswordForm = z.infer<typeof resetPasswordSchema>;

/**
 * Money is typed and validated as text, then converted on submit. Coercing the field to a
 * number would turn a cleared box into 0 — and a contract worth zero is a different claim
 * from a contract whose value has not been entered yet.
 */
const optionalAmount = z
  .string()
  .trim()
  .regex(/^\d{1,16}(\.\d{1,2})?$/, 'A number, up to two decimals')
  .optional()
  .or(z.literal(''));

const optionalDate = z.string().optional().or(z.literal(''));

export const projectSchema = z
  .object({
    code: z
      .string()
      .trim()
      .min(1, 'Enter a project code')
      .max(40, 'At most 40 characters')
      .regex(/^[A-Za-z0-9._-]+$/, 'Letters, digits, dot, underscore and hyphen only'),
    name: z.string().trim().min(1, 'Enter the project name').max(200, 'At most 200 characters'),
    clientDepartment: z.string().trim().max(200, 'At most 200 characters').optional(),
    agreementNo: z.string().trim().max(80, 'At most 80 characters').optional(),
    nitNumber: z.string().trim().max(80, 'At most 80 characters').optional(),
    tenderReference: z.string().trim().max(120, 'At most 120 characters').optional(),
    contractValue: optionalAmount,
    budgetAmount: optionalAmount,
    startDate: optionalDate,
    expectedCompletionDate: optionalDate,
    actualCompletionDate: optionalDate,
    projectManagerId: z.string().optional(),
    status: z.enum(['PLANNED', 'ACTIVE', 'ON_HOLD', 'COMPLETED', 'CLOSED']),
    description: z.string().trim().optional(),
  })
  .refine(
    (form) =>
      !form.startDate ||
      !form.expectedCompletionDate ||
      form.startDate <= form.expectedCompletionDate,
    { path: ['expectedCompletionDate'], message: 'Completion cannot precede the start' },
  );

export type ProjectForm = z.infer<typeof projectSchema>;

export const siteSchema = z.object({
  projectId: z.string().min(1, 'Choose a project'),
  code: z
    .string()
    .trim()
    .min(1, 'Enter a site code')
    .max(40, 'At most 40 characters')
    .regex(/^[A-Za-z0-9._-]+$/, 'Letters, digits, dot, underscore and hyphen only'),
  name: z.string().trim().min(1, 'Enter the site name').max(200, 'At most 200 characters'),
  address: z.string().trim().optional(),
  siteEngineerId: z.string().optional(),
  supervisorId: z.string().optional(),
  startDate: z.string().optional(),
  status: z.enum(['PLANNED', 'ACTIVE', 'SUSPENDED', 'CLOSED']),
  standardShiftHours: z.coerce
    .number({ invalid_type_error: 'Enter the shift length in hours' })
    .min(0.5, 'At least 0.5 hours')
    .max(24, 'At most 24 hours'),
  usesOutsourcedLabour: z.boolean(),
  monthlyWageDays: z.coerce
    .number({ invalid_type_error: 'Enter the days in a wage month' })
    .int('Whole days only')
    .min(1, 'At least 1 day')
    .max(31, 'At most 31 days'),
});

export type SiteForm = z.infer<typeof siteSchema>;

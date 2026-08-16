import { z } from 'zod';

/** Mirrors the labour module's bean validation. */

const optionalAmount = z
  .string()
  .trim()
  .regex(/^\d{1,14}(\.\d{1,4})?$/, 'A number')
  .optional()
  .or(z.literal(''));

/**
 * Taking a man on at the gate, and nothing more.
 *
 * <p>His number is not here because the server assigns it, and his employment basis, his
 * contractor, his joining date and his Aadhaar are not here because none of them was ever
 * answered correctly by somebody standing at a gate. The office fills those in later. What
 * is left is what the muster roll cannot do without: a name, a number to reach him on, the
 * site he is joining, and — for whoever may set pay — what he earns in a day.</p>
 */
export const onboardWorkerSchema = z.object({
  fullName: z.string().trim().min(1, 'Enter his full name').max(150, 'At most 150 characters'),
  mobile: z
    .string()
    .trim()
    .min(1, 'Enter his mobile number')
    .max(20, 'At most 20 characters')
    .refine((value) => value.replace(/\D/g, '').length >= 10, 'At least ten digits'),
  skillCategoryId: z.string().optional(),
  siteId: z.string().min(1, 'Choose the site he is joining'),
  /** Per day. Overtime is not asked for — see the dialog. */
  normalRate: optionalAmount,
});

export type OnboardWorkerForm = z.infer<typeof onboardWorkerSchema>;

/**
 * Correcting a man's particulars afterwards, which is a different act from taking him on.
 *
 * <p>The gate form asks for four things and defaults the rest; this one is where the rest
 * gets answered — what he is engaged as, when he joined, and whether he is still here. The
 * name and mobile carry the same rules as onboarding, because they are the same two fields
 * and a correction that accepted a blank name would be a way round the check at the gate.</p>
 *
 * <p>Pay is not here at all. A rate is revised, never edited, so it cannot sit in a form
 * whose whole nature is to overwrite what was there.</p>
 *
 * <p>Whether he is still here is the one field on it that is routinely changed twice: a man
 * is stood down and taken back on, so both directions have to be as cheap as each other.</p>
 */
export const editWorkerSchema = z.object({
  fullName: z.string().trim().min(1, 'Enter his full name').max(150, 'At most 150 characters'),
  mobile: z
    .string()
    .trim()
    .min(1, 'Enter his mobile number')
    .max(20, 'At most 20 characters')
    .refine((value) => value.replace(/\D/g, '').length >= 10, 'At least ten digits'),
  skillCategoryId: z.string().optional(),
  employmentType: z.enum(['PERMANENT', 'CONTRACT', 'CASUAL']),
  /** Only editable by someone who may set pay; passed through unchanged for everyone else. */
  wageType: z.enum(['DAILY', 'HOURLY', 'MONTHLY']),
  joiningDate: z.string().optional(),
  /**
   * Whether he is on the roll. This said Working or Left, and the last day below it was
   * required, because Left meant one thing: gone for good. Inactive covers the commoner
   * case as well — a man off for a fortnight, a man the site stands down between pours —
   * and demanding a last day for him would put a leaving date on somebody who has not
   * left. It stays optional and stays asked for, because for a man who really has gone it
   * is the date his final settlement is reckoned against.
   */
  active: z.enum(['active', 'inactive']),
  exitDate: z.string().optional(),
});

export type EditWorkerForm = z.infer<typeof editWorkerSchema>;

export const transferSchema = z.object({
  siteId: z.string().min(1, 'Choose where he is going'),
  effectiveFrom: z.string().min(1, 'Choose the date he starts there'),
});

export type TransferForm = z.infer<typeof transferSchema>;

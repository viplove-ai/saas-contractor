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
 */
export const editWorkerSchema = z
  .object({
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
    active: z.enum(['working', 'left']),
    exitDate: z.string().optional(),
  })
  // A man marked Left with no last day leaves the muster roll on a date nobody can name, and
  // that date is what every question about his final wages is asked against.
  .refine((values) => values.active === 'working' || Boolean(values.exitDate), {
    path: ['exitDate'],
    message: 'Give his last day',
  });

export type EditWorkerForm = z.infer<typeof editWorkerSchema>;

export const transferSchema = z.object({
  siteId: z.string().min(1, 'Choose where he is going'),
  effectiveFrom: z.string().min(1, 'Choose the date he starts there'),
});

export type TransferForm = z.infer<typeof transferSchema>;

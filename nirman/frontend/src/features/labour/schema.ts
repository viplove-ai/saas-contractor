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

export const transferSchema = z.object({
  siteId: z.string().min(1, 'Choose where he is going'),
  effectiveFrom: z.string().min(1, 'Choose the date he starts there'),
});

export type TransferForm = z.infer<typeof transferSchema>;

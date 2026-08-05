import { z } from 'zod';

/** Mirrors the labour module's bean validation. */

const optionalAmount = z
  .string()
  .trim()
  .regex(/^\d{1,14}(\.\d{1,4})?$/, 'A number')
  .optional()
  .or(z.literal(''));

export const onboardWorkerSchema = z.object({
  workerCode: z
    .string()
    .trim()
    .min(1, 'Enter a worker number')
    .max(40, 'At most 40 characters')
    .regex(/^[A-Za-z0-9._-]+$/, 'Letters, digits, dot, underscore and hyphen only'),
  fullName: z.string().trim().min(1, 'Enter his full name').max(150, 'At most 150 characters'),
  mobile: z.string().trim().max(20, 'At most 20 characters').optional(),
  skillCategoryId: z.string().optional(),
  employmentType: z.enum(['PERMANENT', 'CONTRACT', 'CASUAL']),
  labourContractorId: z.string().optional(),
  wageType: z.enum(['DAILY', 'HOURLY', 'MONTHLY']),
  joiningDate: z.string().optional().or(z.literal('')),
  /**
   * Four digits, never the whole number. The server enforces the same: a muster roll needs
   * enough to tell two men apart, and holding full Aadhaar numbers on a site phone is a
   * liability nobody asked for.
   */
  aadhaarLast4: z
    .string()
    .trim()
    .regex(/^\d{4}$/, 'The last four digits only')
    .optional()
    .or(z.literal('')),
  siteId: z.string().min(1, 'Choose the site he is joining'),
  normalRate: optionalAmount,
  overtimeRate: optionalAmount,
});

export type OnboardWorkerForm = z.infer<typeof onboardWorkerSchema>;

export const transferSchema = z.object({
  siteId: z.string().min(1, 'Choose where he is going'),
  effectiveFrom: z.string().min(1, 'Choose the date he starts there'),
});

export type TransferForm = z.infer<typeof transferSchema>;

import { z } from 'zod';

/** Mirrors the server's bean validation, so a bad field is caught before the round trip. */

const optional = (max: number) =>
  z.string().trim().max(max, `At most ${max} characters`).optional().or(z.literal(''));

/**
 * A dealer.
 *
 * <p>Only the code, the name and the type are required, and deliberately so: a lorry is at
 * the gate from a supplier nobody has onboarded, and refusing to record him until somebody
 * finds his GSTIN is how a delivery goes unbooked. The rest is what the office fills in when
 * the paperwork catches up.</p>
 */
export const vendorSchema = z.object({
  // No code. It used to be the first thing the form asked for and it was a filing decision
  // nobody was really making: one person typed the firm's initials, the next typed the town,
  // and the register sorted into no order. The server derives it from what he supplies and
  // what he is called, and only the server can promise it is unique.
  name: z.string().trim().min(1, 'Enter the firm’s name').max(200, 'At most 200 characters'),
  vendorType: z.enum(['MATERIAL', 'SUBCONTRACTOR', 'SERVICE', 'TRANSPORT', 'OTHER']),
  suppliesNote: optional(120),
  contactPerson: optional(150),
  mobile: z
    .string()
    .trim()
    .max(20, 'At most 20 characters')
    .regex(/^[0-9+\-\s]*$/, 'Digits, spaces, + and - only')
    .optional()
    .or(z.literal('')),
  email: z
    .string()
    .trim()
    .max(150, 'At most 150 characters')
    .email('Enter a valid email address')
    .optional()
    .or(z.literal('')),
  address: z.string().trim().optional(),
  // Fifteen characters exactly, and only when given. A half-typed GSTIN on file is worse
  // than none: it looks answered and fails at the first return.
  gstin: z
    .string()
    .trim()
    .regex(/^[0-9A-Z]{15}$/, 'A GSTIN is 15 characters, digits and capitals')
    .optional()
    .or(z.literal('')),
  pan: z
    .string()
    .trim()
    .regex(/^[A-Z]{5}[0-9]{4}[A-Z]$/, 'A PAN is five letters, four digits and a letter')
    .optional()
    .or(z.literal('')),
  bankAccountNo: optional(30),
  bankIfsc: z
    .string()
    .trim()
    .regex(/^[A-Z]{4}0[A-Z0-9]{6}$/, 'An IFSC is four letters, a zero and six characters')
    .optional()
    .or(z.literal('')),
  creditDays: z.coerce
    .number({ invalid_type_error: 'Enter the days of credit, or 0' })
    .int('Whole days only')
    .min(0, 'At least 0')
    .max(365, 'At most 365'),
  active: z.boolean(),
})
  /*
    "Other" on its own tells the next person to read the register nothing he did not already
    know, and the list of five will never fit the scaffolding hire, the surveyor and the man
    with the water tanker. So the box beside it is required — and only then, because a note
    sitting behind a kind that no longer shows it is a second answer nobody can see.
  */
  .superRefine((values, ctx) => {
    if (values.vendorType === 'OTHER' && !values.suppliesNote?.trim()) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['suppliesNote'],
        message: 'Say what he supplies',
      });
    }
  });

export type VendorForm = z.infer<typeof vendorSchema>;

/**
 * Money going out before there is a bill for it.
 *
 * <p>The reason is required, unlike on most of the app's optional notes. Six months on, an
 * advance with no explanation is indistinguishable from a payment somebody forgot to attach
 * to a bill — and the person asking is never the person who paid it.</p>
 */
export const vendorAdvanceSchema = z.object({
  paymentDate: z.string().min(1, 'Pick the date it went out'),
  amount: z.coerce
    .number({ invalid_type_error: 'Enter the amount' })
    .positive('More than zero'),
  paymentMode: z.enum(['CASH', 'BANK', 'UPI', 'CHEQUE']),
  referenceNumber: optional(80),
  remarks: z
    .string()
    .trim()
    .min(1, 'Say what it is against — an unexplained advance is unanswerable later')
    .max(500, 'At most 500 characters'),
});

export type VendorAdvanceForm = z.infer<typeof vendorAdvanceSchema>;

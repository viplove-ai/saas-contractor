import { z } from 'zod';

/** Mirrors the server's bean validation, so a bad field is caught before the round trip. */

const optionalText = (max: number) =>
  z.string().trim().max(max, `At most ${max} characters`).optional().or(z.literal(''));

const optionalDate = z.string().optional().or(z.literal(''));

/**
 * Money as text, converted on submit. Coercing the field to a number would turn a cleared
 * box into 0 — and a salary of zero is a different claim from a salary nobody has entered.
 */
const optionalAmount = z
  .string()
  .trim()
  .regex(/^\d{1,12}(\.\d{1,2})?$/, 'A number, up to two decimals')
  .optional()
  .or(z.literal(''));

/**
 * A member's record.
 *
 * <p>Nothing except the employment type is required. Somebody is onboarded as a login on the
 * day they start, and their bank details arrive a week later — a form that refuses to save
 * until every box is full is a form the office fills in with placeholders.</p>
 */
export const staffProfileSchema = z
  .object({
    alternateMobile: z
      .string()
      .trim()
      .max(20, 'At most 20 characters')
      .regex(/^[0-9+\-\s]*$/, 'Digits, spaces, + and - only')
      .optional()
      .or(z.literal('')),
    dateOfBirth: optionalDate,
    // Four digits, and the form says so. The whole number is never stored — four is enough
    // to tell two people of the same name apart, which is all it is for.
    aadhaarLast4: z
      .string()
      .trim()
      .regex(/^[0-9]{4}$/, 'The last four digits only')
      .optional()
      .or(z.literal('')),
    pan: z
      .string()
      .trim()
      .regex(/^[A-Z]{5}[0-9]{4}[A-Z]$/, 'A PAN is five letters, four digits and a letter')
      .optional()
      .or(z.literal('')),
    currentAddress: z.string().trim().optional(),
    permanentAddress: z.string().trim().optional(),
    emergencyContactName: optionalText(150),
    emergencyContactMobile: z
      .string()
      .trim()
      .max(20, 'At most 20 characters')
      .regex(/^[0-9+\-\s]*$/, 'Digits, spaces, + and - only')
      .optional()
      .or(z.literal('')),
    emergencyContactRelation: optionalText(60),
    bankAccountName: optionalText(150),
    bankAccountNo: optionalText(30),
    bankIfsc: z
      .string()
      .trim()
      .regex(/^[A-Z]{4}0[A-Z0-9]{6}$/, 'An IFSC is four letters, a zero and six characters')
      .optional()
      .or(z.literal('')),
    bankName: optionalText(100),

    employmentType: z.enum(['PERMANENT', 'CONTRACTUAL', 'PROBATION']),
    joinedOn: optionalDate,
    probationDays: z.string().trim().optional().or(z.literal('')),
    probationMonthlySalary: optionalAmount,
    confirmedMonthlySalary: optionalAmount,
    confirmedOn: optionalDate,
    contractEndsOn: optionalDate,
    exitDate: optionalDate,
    exitReason: optionalText(500),
    notes: z.string().trim().optional(),
  })
  .superRefine((form, ctx) => {
    // An indefinite probation is not a probation. The server refuses it too; catching it
    // here is what stops the office saving a person and then reading an error.
    if (form.employmentType === 'PROBATION') {
      const days = Number(form.probationDays);
      if (!form.probationDays || !Number.isInteger(days) || days < 1 || days > 730) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['probationDays'],
          message: 'How many days? An indefinite probation is not a probation.',
        });
      }
    }
    if (form.joinedOn && form.confirmedOn && form.confirmedOn < form.joinedOn) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['confirmedOn'],
        message: 'They cannot have been confirmed before they joined',
      });
    }
    if (form.joinedOn && form.exitDate && form.exitDate < form.joinedOn) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['exitDate'],
        message: 'They cannot have left before they joined',
      });
    }
  });

export type StaffProfileForm = z.infer<typeof staffProfileSchema>;

/** A change of pay. The reason is required — see RecordSalaryRequest for why. */
export const salaryRevisionSchema = z.object({
  monthlyAmount: z.coerce
    .number({ invalid_type_error: 'Enter the monthly salary' })
    .min(0, 'Not less than zero'),
  effectiveFrom: z.string().min(1, 'From which date?'),
  reason: z
    .string()
    .trim()
    .min(1, 'Say why it moved — a figure that changed for no recorded reason is one somebody has to go and ask about')
    .max(300, 'At most 300 characters'),
});

export type SalaryRevisionForm = z.infer<typeof salaryRevisionSchema>;

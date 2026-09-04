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
    email: z
      .string()
      .trim()
      .max(150, 'At most 150 characters')
      .email('That is not an email address')
      .optional()
      .or(z.literal('')),
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

    employeeNumber: optionalText(20),
    designation: optionalText(100),
    uan: z
      .string()
      .trim()
      .regex(/^[0-9]{12}$/, 'A UAN is twelve digits')
      .optional()
      .or(z.literal('')),
    esicNumber: optionalText(17),
    pfApplicable: z.boolean(),
    esiApplicable: z.boolean(),
    pfOnFullWages: z.boolean(),
    noticePeriodDays: z.string().trim().optional().or(z.literal('')),
    accommodationProvided: z.boolean(),
    accommodationNote: optionalText(200),
    fuelProvided: z.boolean(),
    fuelMonthlyAmount: optionalAmount,
    fuelNote: optionalText(200),

    employmentType: z.enum(['PERMANENT', 'CONTRACTUAL', 'PROBATION']),
    joinedOn: optionalDate,
    probationDays: z.string().trim().optional().or(z.literal('')),
    probationMonthlySalary: optionalAmount,
    confirmedMonthlySalary: optionalAmount,
    confirmedOn: optionalDate,
    contractEndsOn: optionalDate,
    // Nobody is leaving until somebody says so. The box is what reveals the two fields
    // below it, and unticking it is what clears them — a date left lying in the form
    // because a section was open is how a man still on the site goes off the headcount.
    leaving: z.boolean(),
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
    // Marked as leaving with no day is half a statement: the headcount, the payroll and
    // the salary history all turn on the date and none of them can use "at some point".
    if (form.leaving && !form.exitDate) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['exitDate'],
        message: 'Which day was their last?',
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

/**
 * A change of pay, as a structure rather than as a figure.
 *
 * <p>The basic is required and the rest are not. A salary of basic alone is a real and common
 * arrangement; a salary with no basic at all is not an arrangement, it is a figure somebody
 * has declined to break down — and no payslip can be drawn from it, because the provident
 * fund has nothing to stand on.</p>
 *
 * <p>There is no gross box. It is the sum of the five, shown while typing and sent as
 * nothing: a total typed beside a breakdown is a total that can disagree with it, and the
 * check constraint under the row would then refuse the save over arithmetic the person typing
 * cannot act on.</p>
 */
const component = (label: string) =>
  z.coerce.number({ invalid_type_error: label }).min(0, 'Not less than zero');

export const salaryRevisionSchema = z.object({
  basic: component('Enter the basic pay'),
  dearnessAllowance: component('A number, or leave it at zero'),
  hra: component('A number, or leave it at zero'),
  conveyance: component('A number, or leave it at zero'),
  otherAllowance: component('A number, or leave it at zero'),
  effectiveFrom: z.string().min(1, 'From which date?'),
  reason: z
    .string()
    .trim()
    .min(1, 'Say why it moved — a figure that changed for no recorded reason is one somebody has to go and ask about')
    .max(300, 'At most 300 characters'),
});

export type SalaryRevisionForm = z.infer<typeof salaryRevisionSchema>;

/** What the offer letter asks for, which is only what the record does not already hold. */
export const offerLetterSchema = z.object({
  joiningOn: optionalDate,
  letterDate: optionalDate,
  reference: optionalText(60),
  placeOfPosting: optionalText(200),
  respondBy: optionalDate,
});

export type OfferLetterForm = z.infer<typeof offerLetterSchema>;

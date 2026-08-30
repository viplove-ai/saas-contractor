import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Button,
  Checkbox,
  Collapse,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  FormControlLabel,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { apiErrorDetail } from '../../shared/apiClient';
import { useSaveStaffProfile } from './api';
import { StaffDocuments } from './StaffDocuments';
import { staffProfileSchema, type StaffProfileForm } from './schema';
import { EMPLOYMENT_LABEL, type EmploymentType, type StaffProfile } from './types';

interface Props {
  open: boolean;
  member: StaffProfile;
  onClose: () => void;
}

const TYPES = Object.keys(EMPLOYMENT_LABEL) as EmploymentType[];

/**
 * One member's record: how to reach them, who to telephone, where the salary goes, and what
 * was agreed when they were taken on.
 *
 * <p>Nothing but the employment type is required. Somebody is onboarded as a login on the
 * day they start and their bank details arrive a week later; a form that refuses to save
 * until every box is full is a form the office fills in with placeholders.</p>
 *
 * <p>The Aadhaar box asks for four digits and takes four. The whole number is never stored —
 * four is enough to tell two people of the same name apart on a payroll, which is the only
 * reason it needs to be here, and holding the rest would make this a record the employer has
 * a duty to protect and no use for.</p>
 *
 * <p>The salaries here are the <em>terms</em> — what was agreed. What somebody is actually
 * paid is the salary history beside this dialog, which is appended rather than edited so a
 * raise cannot restate what last month cost.</p>
 */
export function StaffProfileDialog({ open, member, onClose }: Props) {
  const save = useSaveStaffProfile();
  const [serverError, setServerError] = useState<string | null>(null);

  const {
    control,
    register,
    handleSubmit,
    reset,
    setValue,
    formState: { errors, isSubmitting },
  } = useForm<StaffProfileForm>({
    resolver: zodResolver(staffProfileSchema),
    defaultValues: fromProfile(member),
  });

  useEffect(() => {
    if (open) {
      setServerError(null);
      reset(fromProfile(member));
    }
  }, [open, member, reset]);

  /*
    useWatch rather than the form's own watch(). watch() re-renders this whole dialog on every
    change anywhere in it, and the dialog is thirty-odd MUI fields — so typing a house number
    into the address box re-rendered the bank details, the probation terms and the payroll
    section along with it. useWatch subscribes to the one field it names.
  */
  const employmentType = useWatch({ control, name: 'employmentType' });
  const leaving = useWatch({ control, name: 'leaving' });

  const submit = handleSubmit(async ({ leaving: isLeaving, ...values }) => {
    setServerError(null);
    try {
      await save.mutateAsync({
        userId: member.userId,
        ...values,
        // The tick is the statement, so what it says wins over whatever the two boxes hold,
        // and it stays out of the request itself — the record's answer is the date. The
        // server overwrites both fields on every save, which is what lets an unticked box
        // put somebody who never actually left back on the headcount.
        exitDate: isLeaving ? values.exitDate : '',
        exitReason: isLeaving ? values.exitReason : '',
        probationDays: values.probationDays ? Number(values.probationDays) : undefined,
        noticePeriodDays: values.noticePeriodDays
          ? Number(values.noticePeriodDays)
          : undefined,
        probationMonthlySalary: toAmount(values.probationMonthlySalary),
        confirmedMonthlySalary: toAmount(values.confirmedMonthlySalary),
        version: member.version,
      });
      onClose();
    } catch (error) {
      setServerError(apiErrorDetail(error));
    }
  });

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{member.fullName}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {serverError && <Alert severity="error">{serverError}</Alert>}
          {member.version === undefined && (
            <Alert severity="info">
              Nothing on file for {member.fullName} yet beyond their login. Fill in what you
              have; the rest can wait.
            </Alert>
          )}

          <Section>How they are engaged</Section>
          <Controller
            control={control}
            name="employmentType"
            render={({ field }) => (
              <TextField {...field} select label="Employment">
                {TYPES.map((type) => (
                  <MenuItem key={type} value={type}>
                    {EMPLOYMENT_LABEL[type]}
                  </MenuItem>
                ))}
              </TextField>
            )}
          />
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Joined on"
              type="date"
              fullWidth
              InputLabelProps={{ shrink: true }}
              error={!!errors.joinedOn}
              helperText={errors.joinedOn?.message}
              {...register('joinedOn')}
            />
            {employmentType === 'CONTRACTUAL' && (
              <TextField
                label="Contract ends"
                type="date"
                fullWidth
                InputLabelProps={{ shrink: true }}
                error={!!errors.contractEndsOn}
                helperText={errors.contractEndsOn?.message}
                {...register('contractEndsOn')}
              />
            )}
          </Stack>

          {/*
            Probation is the only footing somebody leaves, so its fields appear only when it
            is the one selected. Both salaries stay visible either way: the confirmed figure
            is what they go onto, and the office agrees it on the day they are hired.
          */}
          {employmentType === 'PROBATION' && (
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField
                label="Probation days"
                type="number"
                fullWidth
                inputProps={{ min: 1, max: 730, step: 1 }}
                error={!!errors.probationDays}
                helperText={
                  errors.probationDays?.message ??
                  (member.probationEndsOn ? `Ends ${member.probationEndsOn}` : 'From the joining date')
                }
                {...register('probationDays')}
              />
              <TextField
                label="Confirmed on"
                type="date"
                fullWidth
                InputLabelProps={{ shrink: true }}
                error={!!errors.confirmedOn}
                helperText={
                  errors.confirmedOn?.message ??
                  'When probation actually ended — often not the agreed day.'
                }
                {...register('confirmedOn')}
              />
            </Stack>
          )}
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Salary during probation"
              fullWidth
              inputMode="decimal"
              error={!!errors.probationMonthlySalary}
              helperText={errors.probationMonthlySalary?.message ?? 'A month, as agreed'}
              {...register('probationMonthlySalary')}
            />
            <TextField
              label="Salary after probation"
              fullWidth
              inputMode="decimal"
              error={!!errors.confirmedMonthlySalary}
              helperText={errors.confirmedMonthlySalary?.message ?? 'A month, as agreed'}
              {...register('confirmedMonthlySalary')}
            />
          </Stack>
          <Alert severity="info" sx={{ mt: -1 }}>
            These are the terms that were agreed. What is actually paid, and from when, is the
            salary history — record a revision there when it changes.
          </Alert>

          {/*
            The payroll half of the record. It is here rather than on the salary screen
            because none of it is a figure: a universal account number and an insurance
            number belong to the person and follow him between employers, and whether the two
            schemes reach him at all is a fact about his enrolment, not about this month.
          */}
          <Section>Payroll and statutory</Section>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Employee number"
              fullWidth
              error={!!errors.employeeNumber}
              helperText={errors.employeeNumber?.message ?? 'What the payslip and the bank advice call him'}
              {...register('employeeNumber')}
            />
            <TextField
              label="Designation"
              fullWidth
              error={!!errors.designation}
              helperText={errors.designation?.message ?? 'Prints on the payslip and the offer letter'}
              {...register('designation')}
            />
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="UAN"
              fullWidth
              inputMode="numeric"
              error={!!errors.uan}
              helperText={errors.uan?.message ?? 'Twelve digits, carried between employers'}
              {...register('uan')}
            />
            <TextField
              label="ESIC number"
              fullWidth
              error={!!errors.esicNumber}
              helperText={errors.esicNumber?.message ?? 'How a claim is made'}
              {...register('esicNumber')}
            />
            <TextField
              label="Notice period (days)"
              fullWidth
              inputMode="numeric"
              error={!!errors.noticePeriodDays}
              helperText={errors.noticePeriodDays?.message ?? 'Prints on the offer letter'}
              {...register('noticePeriodDays')}
            />
          </Stack>
          <Controller
            control={control}
            name="pfApplicable"
            render={({ field }) => (
              <FormControlLabel
                control={<Checkbox {...field} checked={field.value} />}
                label="Enrolled in the provident fund"
              />
            )}
          />
          <Controller
            control={control}
            name="pfOnFullWages"
            render={({ field }) => (
              <FormControlLabel
                control={<Checkbox {...field} checked={field.value} />}
                label="Contribute on full wages rather than on the ₹15,000 ceiling"
              />
            )}
          />
          <Controller
            control={control}
            name="esiApplicable"
            render={({ field }) => (
              <FormControlLabel
                control={<Checkbox {...field} checked={field.value} />}
                label="Covered by state insurance"
              />
            )}
          />
          {/*
            Why coverage is a tick and not a test. It runs for a whole contribution period and
            does not lapse in the middle of one because a raise carried somebody over the
            ceiling — a screen that recomputed it monthly would drop him out in July and leave
            the establishment short-paid until the period ended.
          */}
          <Alert severity="info" sx={{ mt: -1 }}>
            Insurance coverage is decided for a whole contribution period (April–September,
            October–March) and does not stop mid-period because a raise crossed the ₹21,000
            ceiling. Leave the tick where the scheme put it.
          </Alert>

          <Section>How you reach them</Section>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Alternate mobile"
              fullWidth
              inputMode="tel"
              error={!!errors.alternateMobile}
              helperText={
                errors.alternateMobile?.message ?? `Their sign-in number is ${member.mobile ?? '—'}`
              }
              {...register('alternateMobile')}
            />
            <TextField
              label="Date of birth"
              type="date"
              fullWidth
              InputLabelProps={{ shrink: true }}
              error={!!errors.dateOfBirth}
              helperText={errors.dateOfBirth?.message}
              {...register('dateOfBirth')}
            />
          </Stack>
          <TextField
            label="Current address"
            multiline
            minRows={2}
            error={!!errors.currentAddress}
            helperText={errors.currentAddress?.message ?? 'Where they are living while on this job'}
            {...register('currentAddress')}
          />
          <TextField
            label="Permanent address"
            multiline
            minRows={2}
            error={!!errors.permanentAddress}
            helperText={errors.permanentAddress?.message ?? 'Their home village or town'}
            {...register('permanentAddress')}
          />

          <Section>Who to telephone</Section>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Name"
              fullWidth
              error={!!errors.emergencyContactName}
              helperText={errors.emergencyContactName?.message}
              {...register('emergencyContactName')}
            />
            <TextField
              label="Relation"
              fullWidth
              error={!!errors.emergencyContactRelation}
              helperText={errors.emergencyContactRelation?.message}
              {...register('emergencyContactRelation')}
            />
            <TextField
              label="Mobile"
              fullWidth
              inputMode="tel"
              error={!!errors.emergencyContactMobile}
              helperText={errors.emergencyContactMobile?.message}
              {...register('emergencyContactMobile')}
            />
          </Stack>

          <Section>Identity and bank</Section>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Aadhaar — last 4 digits"
              fullWidth
              inputMode="numeric"
              inputProps={{ maxLength: 4 }}
              error={!!errors.aadhaarLast4}
              helperText={
                errors.aadhaarLast4?.message ??
                'Four digits only. The full number is never stored.'
              }
              {...register('aadhaarLast4')}
            />
            <TextField
              label="PAN"
              fullWidth
              inputProps={{ style: { textTransform: 'uppercase' } }}
              error={!!errors.pan}
              helperText={errors.pan?.message}
              {...register('pan')}
            />
          </Stack>
          <TextField
            label="Account holder’s name"
            error={!!errors.bankAccountName}
            helperText={errors.bankAccountName?.message ?? 'As the bank has it, if it differs'}
            {...register('bankAccountName')}
          />
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Account number"
              fullWidth
              error={!!errors.bankAccountNo}
              helperText={errors.bankAccountNo?.message}
              {...register('bankAccountNo')}
            />
            <TextField
              label="IFSC"
              fullWidth
              inputProps={{ style: { textTransform: 'uppercase' } }}
              error={!!errors.bankIfsc}
              helperText={errors.bankIfsc?.message}
              {...register('bankIfsc')}
            />
          </Stack>
          <TextField
            label="Bank"
            error={!!errors.bankName}
            helperText={errors.bankName?.message}
            {...register('bankName')}
          />

          <Section>Leaving</Section>
          {/*
            Almost nobody using this dialog is recording a departure — they are filling in a
            bank account or a telephone number for somebody who is still here. So the two
            fields are behind a tick rather than sitting open with a date box beside them: an
            empty date box on an open form is an invitation to put today in it, and today in
            that box takes a man off the headcount. Unticking is a statement too, and it
            clears both fields on the way past, so a date typed and thought better of does
            not survive being hidden.
          */}
          <Controller
            control={control}
            name="leaving"
            render={({ field }) => (
              <FormControlLabel
                control={
                  <Checkbox
                    checked={field.value}
                    onChange={(event) => {
                      field.onChange(event.target.checked);
                      if (!event.target.checked) {
                        setValue('exitDate', '');
                        setValue('exitReason', '');
                      }
                    }}
                  />
                }
                label="They are leaving, or have left"
              />
            )}
          />
          <Collapse in={leaving}>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ pt: 1 }}>
              <TextField
                label="Last day"
                type="date"
                fullWidth
                InputLabelProps={{ shrink: true }}
                error={!!errors.exitDate}
                helperText={
                  errors.exitDate?.message ?? 'This is what takes them off the headcount.'
                }
                {...register('exitDate')}
              />
              <TextField
                label="Reason"
                fullWidth
                error={!!errors.exitReason}
                helperText={errors.exitReason?.message}
                {...register('exitReason')}
              />
            </Stack>
          </Collapse>

          {/*
            The papers the fields above were typed off. Last, because it is the one part of
            this dialog that saves itself as it goes — putting it between two form sections
            would leave a reader unsure which half the Save button was for.
          */}
          <Section>Papers on file</Section>
          <StaffDocuments userId={member.userId} memberName={member.fullName} />

          <Section>Anything else</Section>
          <TextField
            label="Notes"
            multiline
            minRows={2}
            error={!!errors.notes}
            helperText={errors.notes?.message}
            {...register('notes')}
          />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" color="secondary" onClick={submit} disabled={isSubmitting}>
          Save the record
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function Section({ children }: { children: string }) {
  return (
    <Divider textAlign="left" sx={{ pt: 1 }}>
      <Typography variant="overline" color="text.secondary">
        {children}
      </Typography>
    </Divider>
  );
}

/** An empty string, not undefined: a controlled box handed undefined goes uncontrolled. */
function fromProfile(member: StaffProfile): StaffProfileForm {
  return {
    alternateMobile: member.alternateMobile ?? '',
    dateOfBirth: member.dateOfBirth ?? '',
    aadhaarLast4: member.aadhaarLast4 ?? '',
    pan: member.pan ?? '',
    currentAddress: member.currentAddress ?? '',
    permanentAddress: member.permanentAddress ?? '',
    emergencyContactName: member.emergencyContactName ?? '',
    emergencyContactMobile: member.emergencyContactMobile ?? '',
    emergencyContactRelation: member.emergencyContactRelation ?? '',
    bankAccountName: member.bankAccountName ?? '',
    bankAccountNo: member.bankAccountNo ?? '',
    bankIfsc: member.bankIfsc ?? '',
    bankName: member.bankName ?? '',
    employeeNumber: member.employeeNumber ?? '',
    designation: member.designation ?? '',
    uan: member.uan ?? '',
    esicNumber: member.esicNumber ?? '',
    pfApplicable: member.pfApplicable,
    esiApplicable: member.esiApplicable,
    pfOnFullWages: member.pfOnFullWages,
    noticePeriodDays:
      member.noticePeriodDays === undefined ? '' : String(member.noticePeriodDays),
    employmentType: member.employmentType,
    joinedOn: member.joinedOn ?? '',
    probationDays: member.probationDays === undefined ? '' : String(member.probationDays),
    probationMonthlySalary: amountText(member.probationMonthlySalary),
    confirmedMonthlySalary: amountText(member.confirmedMonthlySalary),
    confirmedOn: member.confirmedOn ?? '',
    contractEndsOn: member.contractEndsOn ?? '',
    // Ticked only for somebody the record already says has gone, so the section opens for
    // the one person in a hundred it is about and stays shut for the rest.
    leaving: Boolean(member.exitDate),
    exitDate: member.exitDate ?? '',
    exitReason: member.exitReason ?? '',
    notes: member.notes ?? '',
  };
}

function amountText(value: number | undefined): string {
  return value === undefined ? '' : String(value);
}

function toAmount(value: string | undefined): number | undefined {
  return value === undefined || value === '' ? undefined : Number(value);
}

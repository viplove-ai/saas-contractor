import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { apiErrorDetail } from '../../shared/apiClient';
import { useSaveStaffProfile } from './api';
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
    watch,
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

  const employmentType = watch('employmentType');

  const submit = handleSubmit(async (values) => {
    setServerError(null);
    try {
      await save.mutateAsync({
        userId: member.userId,
        ...values,
        probationDays: values.probationDays ? Number(values.probationDays) : undefined,
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
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Last day"
              type="date"
              fullWidth
              InputLabelProps={{ shrink: true }}
              error={!!errors.exitDate}
              helperText={
                errors.exitDate?.message ??
                'Leave blank while they are still here. Setting it takes them off the headcount.'
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
    employmentType: member.employmentType,
    joinedOn: member.joinedOn ?? '',
    probationDays: member.probationDays === undefined ? '' : String(member.probationDays),
    probationMonthlySalary: amountText(member.probationMonthlySalary),
    confirmedMonthlySalary: amountText(member.confirmedMonthlySalary),
    confirmedOn: member.confirmedOn ?? '',
    contractEndsOn: member.contractEndsOn ?? '',
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

import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { apiErrorDetail } from '../../shared/apiClient';
import { useAuth } from '../auth/AuthContext';
import {
  useLabourContractors,
  useMySites,
  useOnboardWorker,
  useSkillCategories,
} from './api';
import { onboardWorkerSchema, type OnboardWorkerForm } from './schema';
import { EMPLOYMENT_LABEL, WAGE_TYPE_LABEL, type EmploymentType, type WageType } from './types';

interface Props {
  open: boolean;
  /** Pre-selected site, so the supervisor is not asked where he is standing. */
  defaultSiteId: string;
  onClose: () => void;
}

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * Taking a man on at the gate.
 *
 * <p>Written for the phone it will be filled in on: the site is pre-selected, the joining
 * date defaults to today, and everything the office can supply later — his contractor, his
 * Aadhaar digits, his bank — is optional. A man who starts work at eight and is entered at
 * nine has to be markable at the end of the same day.</p>
 *
 * <p>The pay fields appear only for someone who may set pay. A supervisor onboards the man;
 * what he is paid is the office's decision, and the server refuses a rate from anyone
 * without {@code wage:write} rather than trusting this to hide it.</p>
 */
export function OnboardWorkerDialog({ open, defaultSiteId, onClose }: Props) {
  const { hasPermission } = useAuth();
  const mySites = useMySites();
  const skills = useSkillCategories();
  const contractors = useLabourContractors();
  const onboard = useOnboardWorker();
  const [serverError, setServerError] = useState<string | null>(null);
  const canSetPay = hasPermission('wage:write');

  const {
    control,
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<OnboardWorkerForm>({
    resolver: zodResolver(onboardWorkerSchema),
    defaultValues: {
      workerCode: '',
      fullName: '',
      mobile: '',
      skillCategoryId: '',
      employmentType: 'CONTRACT',
      labourContractorId: '',
      wageType: 'DAILY',
      joiningDate: todayIso(),
      aadhaarLast4: '',
      siteId: defaultSiteId,
      normalRate: '',
      overtimeRate: '',
    },
  });

  useEffect(() => {
    if (!open) {
      return;
    }
    setServerError(null);
    reset({
      workerCode: '',
      fullName: '',
      mobile: '',
      skillCategoryId: '',
      employmentType: 'CONTRACT',
      labourContractorId: '',
      wageType: 'DAILY',
      joiningDate: todayIso(),
      aadhaarLast4: '',
      siteId: defaultSiteId || mySites.data?.[0]?.id || '',
      normalRate: '',
      overtimeRate: '',
    });
  }, [open, defaultSiteId, mySites.data, reset]);

  const submit = handleSubmit(async (values) => {
    setServerError(null);
    try {
      await onboard.mutateAsync({
        workerCode: values.workerCode,
        fullName: values.fullName,
        mobile: values.mobile,
        skillCategoryId: values.skillCategoryId,
        employmentType: values.employmentType,
        labourContractorId: values.labourContractorId,
        wageType: values.wageType,
        joiningDate: values.joiningDate,
        aadhaarLast4: values.aadhaarLast4,
        siteId: values.siteId,
        normalRate: canSetPay && values.normalRate ? Number(values.normalRate) : undefined,
        overtimeRate: canSetPay && values.overtimeRate ? Number(values.overtimeRate) : undefined,
      });
      onClose();
    } catch (error) {
      setServerError(apiErrorDetail(error));
    }
  });

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Take on a worker</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {serverError && <Alert severity="error">{serverError}</Alert>}

          <TextField
            label="Full name"
            autoFocus
            error={!!errors.fullName}
            helperText={errors.fullName?.message}
            {...register('fullName')}
          />
          <TextField
            label="Worker number"
            error={!!errors.workerCode}
            helperText={errors.workerCode?.message ?? 'What goes against his name on the roll.'}
            {...register('workerCode')}
          />
          <TextField
            label="Mobile (optional)"
            inputMode="tel"
            error={!!errors.mobile}
            helperText={errors.mobile?.message}
            {...register('mobile')}
          />

          <Controller
            control={control}
            name="siteId"
            render={({ field }) => (
              <TextField
                {...field}
                select
                label="Site"
                error={!!errors.siteId}
                helperText={errors.siteId?.message ?? 'He appears on this site’s roll from today.'}
              >
                {(mySites.data ?? []).map((site) => (
                  <MenuItem key={site.id} value={site.id}>
                    {site.code} — {site.name}
                  </MenuItem>
                ))}
              </TextField>
            )}
          />

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <Controller
              control={control}
              name="skillCategoryId"
              render={({ field }) => (
                <TextField {...field} select label="Trade (optional)">
                  <MenuItem value="">Not set</MenuItem>
                  {(skills.data ?? [])
                    .filter((skill) => skill.active)
                    .map((skill) => (
                      <MenuItem key={skill.id} value={skill.id}>
                        {skill.name}
                      </MenuItem>
                    ))}
                </TextField>
              )}
            />
            <Controller
              control={control}
              name="employmentType"
              render={({ field }) => (
                <TextField {...field} select label="Engaged as">
                  {(Object.keys(EMPLOYMENT_LABEL) as EmploymentType[]).map((type) => (
                    <MenuItem key={type} value={type}>
                      {EMPLOYMENT_LABEL[type]}
                    </MenuItem>
                  ))}
                </TextField>
              )}
            />
          </Stack>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <Controller
              control={control}
              name="labourContractorId"
              render={({ field }) => (
                <TextField {...field} select label="Through contractor (optional)">
                  <MenuItem value="">Direct</MenuItem>
                  {(contractors.data ?? [])
                    .filter((contractor) => contractor.active)
                    .map((contractor) => (
                      <MenuItem key={contractor.id} value={contractor.id}>
                        {contractor.name}
                      </MenuItem>
                    ))}
                </TextField>
              )}
            />
            <Controller
              control={control}
              name="wageType"
              render={({ field }) => (
                <TextField {...field} select label="Paid">
                  {(Object.keys(WAGE_TYPE_LABEL) as WageType[]).map((type) => (
                    <MenuItem key={type} value={type}>
                      {WAGE_TYPE_LABEL[type]}
                    </MenuItem>
                  ))}
                </TextField>
              )}
            />
          </Stack>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Joining date"
              type="date"
              InputLabelProps={{ shrink: true }}
              error={!!errors.joiningDate}
              helperText={errors.joiningDate?.message}
              {...register('joiningDate')}
            />
            <TextField
              label="Aadhaar last 4 (optional)"
              inputMode="numeric"
              error={!!errors.aadhaarLast4}
              helperText={errors.aadhaarLast4?.message ?? 'Four digits only, never the full number.'}
              {...register('aadhaarLast4')}
            />
          </Stack>

          {canSetPay ? (
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField
                label="Rate (optional)"
                inputMode="decimal"
                error={!!errors.normalRate}
                helperText={errors.normalRate?.message ?? 'Per the pay basis chosen above.'}
                {...register('normalRate')}
              />
              <TextField
                label="Overtime rate an hour (optional)"
                inputMode="decimal"
                error={!!errors.overtimeRate}
                helperText={errors.overtimeRate?.message}
                {...register('overtimeRate')}
              />
            </Stack>
          ) : (
            <Alert severity="info">
              Add him now and mark his attendance today. The office sets what he is paid —
              until they do, his days are recorded but carry no amount.
            </Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" color="secondary" onClick={submit} disabled={isSubmitting}>
          Add worker
        </Button>
      </DialogActions>
    </Dialog>
  );
}

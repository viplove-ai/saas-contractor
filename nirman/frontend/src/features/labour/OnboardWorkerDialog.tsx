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
import { useMySites, useOnboardWorker, useSkillCategories } from './api';
import { onboardWorkerSchema, type OnboardWorkerForm } from './schema';

interface Props {
  open: boolean;
  /** Pre-selected site, so the supervisor is not asked where he is standing. */
  defaultSiteId: string;
  onClose: () => void;
}

/**
 * Taking a man on at the gate, in five fields.
 *
 * <p>Written for the phone it will be filled in on. It asks only what the muster roll cannot
 * do without — his name, a number to reach him on, the site, his trade if anyone knows it —
 * and everything the office can supply later is gone from the screen entirely rather than
 * sitting there marked optional. His worker number is assigned by the server, because a
 * number invented at a gate is a number another gate has already used.</p>
 *
 * <p>The pay field appears only for someone who may set pay, and it is a day's wage. Nobody
 * is asked for an overtime rate: the site already carries where its working hours end, and
 * the hour past them is paid at the rate the day's wage implies.</p>
 */
export function OnboardWorkerDialog({ open, defaultSiteId, onClose }: Props) {
  const { hasPermission } = useAuth();
  const mySites = useMySites();
  const skills = useSkillCategories();
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
      fullName: '',
      mobile: '',
      skillCategoryId: '',
      siteId: defaultSiteId,
      normalRate: '',
    },
  });

  useEffect(() => {
    if (!open) {
      return;
    }
    setServerError(null);
    reset({
      fullName: '',
      mobile: '',
      skillCategoryId: '',
      siteId: defaultSiteId || mySites.data?.[0]?.id || '',
      normalRate: '',
    });
  }, [open, defaultSiteId, mySites.data, reset]);

  const submit = handleSubmit(async (values) => {
    setServerError(null);
    try {
      await onboard.mutateAsync({
        fullName: values.fullName,
        mobile: values.mobile,
        skillCategoryId: values.skillCategoryId,
        siteId: values.siteId,
        normalRate: canSetPay && values.normalRate ? Number(values.normalRate) : undefined,
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
            label="Mobile"
            inputMode="tel"
            error={!!errors.mobile}
            helperText={errors.mobile?.message ?? 'How the site reaches him.'}
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

          {canSetPay ? (
            <TextField
              label="Wage a day (optional)"
              inputMode="decimal"
              error={!!errors.normalRate}
              helperText={
                errors.normalRate?.message ??
                'Anything past the site’s working hours is overtime, at the hour this works out to.'
              }
              {...register('normalRate')}
            />
          ) : (
            <Alert severity="info">
              Add him now and mark his attendance today. His rate can be set afterwards from
              the register — until it is, his days are recorded but carry no amount.
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

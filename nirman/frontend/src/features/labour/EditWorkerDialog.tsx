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
import { Controller, useForm, useWatch } from 'react-hook-form';
import { apiErrorDetail } from '../../shared/apiClient';
import { useAuth } from '../auth/AuthContext';
import { useSkillCategories, useUpdateWorker } from './api';
import { editWorkerSchema, type EditWorkerForm } from './schema';
import { EMPLOYMENT_LABEL, WAGE_TYPE_LABEL, type Worker } from './types';

interface Props {
  worker: Worker | null;
  onClose: () => void;
}

/**
 * Correcting a man's particulars — the other half of taking him on.
 *
 * <p>The gate form asks for four things and defaults the rest, on the reasoning that nothing
 * else is answered correctly by somebody standing at a gate. This is where the rest is
 * answered: what he is engaged as, when he joined, and whether he is still here. The two
 * fields both forms share carry the same rules, because a correction that accepted a blank
 * name would be a way round the check at the gate.</p>
 *
 * <p><b>Pay is deliberately absent.</b> A rate is revised and never edited — the old one is
 * closed the day before the new one opens, so that a month already settled cannot be
 * repriced — and putting it in a form whose whole nature is to overwrite would undo that.
 * The wage <em>basis</em> is here, but only for someone who may set pay: per day or per
 * month decides what the number beside it means, so changing it is a pay decision even
 * though it is not a number.</p>
 *
 * <p>Marked Left is the ordinary end of a man's time here and is not deletion: his months
 * keep their wages and still count towards what the site cost. So it asks for his last day,
 * which is the date every question about his final settlement is asked against.</p>
 */
export function EditWorkerDialog({ worker, onClose }: Props) {
  const { hasPermission } = useAuth();
  const skills = useSkillCategories();
  const update = useUpdateWorker();
  const [serverError, setServerError] = useState<string | null>(null);
  const canSetPay = hasPermission('wage:write');

  const {
    control,
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<EditWorkerForm>({
    resolver: zodResolver(editWorkerSchema),
    defaultValues: {
      fullName: '',
      mobile: '',
      skillCategoryId: '',
      employmentType: 'CONTRACT',
      wageType: 'DAILY',
      joiningDate: '',
      active: 'working',
      exitDate: '',
    },
  });

  // The last day only exists for a man who has gone, so the field follows the answer above it.
  const status = useWatch({ control, name: 'active' });

  useEffect(() => {
    if (!worker) {
      return;
    }
    setServerError(null);
    reset({
      fullName: worker.fullName,
      mobile: worker.mobile ?? '',
      skillCategoryId: worker.skillCategoryId ?? '',
      employmentType: worker.employmentType,
      wageType: worker.wageType,
      joiningDate: worker.joiningDate ?? '',
      active: worker.active ? 'working' : 'left',
      exitDate: worker.exitDate ?? '',
    });
  }, [worker, reset]);

  const submit = handleSubmit(async (values) => {
    if (!worker) {
      return;
    }
    setServerError(null);
    const working = values.active === 'working';
    try {
      await update.mutateAsync({
        id: worker.id,
        fullName: values.fullName,
        mobile: values.mobile,
        skillCategoryId: values.skillCategoryId || undefined,
        employmentType: values.employmentType,
        // Not on the form, and sent back as it stands: the update replaces the row, so
        // leaving these out would clear a man's bank details for changing his trade.
        labourSupplierId: worker.labourSupplierId,
        wageType: values.wageType,
        joiningDate: values.joiningDate || undefined,
        // A man put back to working keeps no last day, or he is on the roll and gone at once.
        exitDate: working ? undefined : values.exitDate || undefined,
        aadhaarLast4: worker.aadhaarLast4,
        bankAccountNo: worker.bankAccountNo,
        bankIfsc: worker.bankIfsc,
        bankName: worker.bankName,
        active: working,
        version: worker.version,
      });
      onClose();
    } catch (error) {
      setServerError(apiErrorDetail(error));
    }
  });

  return (
    <Dialog open={worker !== null} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Edit {worker?.workerCode}</DialogTitle>
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
            name="skillCategoryId"
            render={({ field }) => (
              <TextField {...field} select label="Trade">
                <MenuItem value="">Not set</MenuItem>
                {(skills.data ?? [])
                  .filter((skill) => skill.active || skill.id === worker?.skillCategoryId)
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
                {(['PERMANENT', 'CONTRACT', 'CASUAL'] as const).map((type) => (
                  <MenuItem key={type} value={type}>
                    {EMPLOYMENT_LABEL[type]}
                  </MenuItem>
                ))}
              </TextField>
            )}
          />

          {canSetPay && (
            <Controller
              control={control}
              name="wageType"
              render={({ field }) => (
                <TextField
                  {...field}
                  select
                  label="Paid by"
                  helperText="What his rate is a rate of. Changing it does not change the rate."
                >
                  {(['DAILY', 'HOURLY', 'MONTHLY'] as const).map((type) => (
                    <MenuItem key={type} value={type}>
                      {WAGE_TYPE_LABEL[type]}
                    </MenuItem>
                  ))}
                </TextField>
              )}
            />
          )}

          <TextField
            label="Joining date"
            type="date"
            InputLabelProps={{ shrink: true }}
            {...register('joiningDate')}
          />

          <Controller
            control={control}
            name="active"
            render={({ field }) => (
              <TextField
                {...field}
                select
                label="Status"
                helperText="A man marked Left comes off the roll and keeps every day he worked."
              >
                <MenuItem value="working">Working</MenuItem>
                <MenuItem value="left">Left</MenuItem>
              </TextField>
            )}
          />

          {status === 'left' && (
            <TextField
              label="Last day"
              type="date"
              InputLabelProps={{ shrink: true }}
              error={!!errors.exitDate}
              helperText={errors.exitDate?.message ?? 'The last day he was on the site.'}
              {...register('exitDate')}
            />
          )}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" color="secondary" onClick={submit} disabled={isSubmitting}>
          Save changes
        </Button>
      </DialogActions>
    </Dialog>
  );
}

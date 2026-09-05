import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import { useReviseWage, useWageHistory } from './api';
import { reviseWageSchema, type ReviseWageForm } from './schema';
import { WAGE_TYPE_LABEL, type Worker } from './types';

interface Props {
  worker: Worker | null;
  onClose: () => void;
}

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * Setting or revising what a man is paid.
 *
 * <p>A rate is revised and never edited. The one in force is closed the day before the new
 * one begins, and every day already verified keeps the rate frozen onto it — so raising a
 * man on Monday repays nothing he was marked for last week. That is why this is its own
 * dialog and not a field on the edit form, whose whole nature is to overwrite.</p>
 *
 * <p>Only the day's wage is asked for. The overtime hour is worked out by the server from the
 * shift length of the site he stands on that day, the same way it is when he is taken on;
 * anybody who has agreed a different figure can type it. The history below the form is what
 * the man was paid before, newest first, because "what was he on last month" is the question
 * this dialog is opened to answer half the time.</p>
 */
export function ReviseWageDialog({ worker, onClose }: Props) {
  const revise = useReviseWage();
  const history = useWageHistory(worker?.id);
  const [serverError, setServerError] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<ReviseWageForm>({
    resolver: zodResolver(reviseWageSchema),
    defaultValues: { normalRate: '', overtimeRate: '', effectiveFrom: today(), remarks: '' },
  });

  useEffect(() => {
    if (!worker) {
      return;
    }
    setServerError(null);
    reset({ normalRate: '', overtimeRate: '', effectiveFrom: today(), remarks: '' });
  }, [worker, reset]);

  const submit = handleSubmit(async (values) => {
    if (!worker) {
      return;
    }
    setServerError(null);
    try {
      await revise.mutateAsync({
        workerId: worker.id,
        normalRate: Number(values.normalRate),
        overtimeRate: values.overtimeRate ? Number(values.overtimeRate) : undefined,
        effectiveFrom: values.effectiveFrom,
        remarks: values.remarks || undefined,
      });
      onClose();
    } catch (error) {
      setServerError(apiErrorDetail(error));
    }
  });

  const current = worker?.currentWageRate;
  const basis = worker ? WAGE_TYPE_LABEL[worker.wageType].toLowerCase() : '';

  return (
    <Dialog open={worker !== null} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{current ? 'Revise rate' : 'Set rate'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {serverError && <Alert severity="error">{serverError}</Alert>}

          <Typography color="text.secondary">
            {worker?.fullName} ({worker?.workerCode}) ·{' '}
            {current
              ? `${formatAmount(current.normalRate)} ${basis} since ${current.effectiveFrom}`
              : 'no rate yet, so his days carry no amount'}
          </Typography>

          <TextField
            label={`Rate (${basis})`}
            autoFocus
            inputMode="decimal"
            error={!!errors.normalRate}
            helperText={errors.normalRate?.message}
            {...register('normalRate')}
          />
          <TextField
            label="Overtime rate an hour (optional)"
            inputMode="decimal"
            error={!!errors.overtimeRate}
            helperText={
              errors.overtimeRate?.message ??
              'Leave it empty and the hour past the site’s shift pays what the rate works out to an hour.'
            }
            {...register('overtimeRate')}
          />
          <TextField
            label="From"
            type="date"
            InputLabelProps={{ shrink: true }}
            error={!!errors.effectiveFrom}
            helperText={
              errors.effectiveFrom?.message ??
              'Days already verified keep the rate they were verified at.'
            }
            {...register('effectiveFrom')}
          />
          <TextField
            label="Reason (optional)"
            error={!!errors.remarks}
            helperText={errors.remarks?.message}
            {...register('remarks')}
          />

          {history.data && history.data.length > 0 && (
            <Stack spacing={0.5}>
              <Typography variant="subtitle2">Earlier rates</Typography>
              {history.data.map((rate) => (
                <Typography key={rate.id} variant="body2" color="text.secondary">
                  {formatAmount(rate.normalRate)} {basis}, overtime{' '}
                  {formatAmount(rate.overtimeRate)} an hour · {rate.effectiveFrom} to{' '}
                  {rate.effectiveTo ?? 'date'}
                  {rate.remarks ? ` · ${rate.remarks}` : ''}
                </Typography>
              ))}
            </Stack>
          )}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" color="secondary" onClick={submit} disabled={isSubmitting}>
          {current ? 'Revise rate' : 'Set rate'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

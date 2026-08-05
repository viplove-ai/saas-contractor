import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  MenuItem,
  Stack,
  TextField,
} from '@mui/material';
import { DialogTitle } from '@mui/material';
import { useEffect, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { apiErrorDetail } from '../../shared/apiClient';
import { useSiteDirectory, useTransferWorker } from './api';
import { transferSchema, type TransferForm } from './schema';
import type { Worker } from './types';

interface Props {
  worker: Worker | null;
  onClose: () => void;
}

function tomorrowIso(): string {
  const date = new Date();
  date.setDate(date.getDate() + 1);
  return date.toISOString().slice(0, 10);
}

/**
 * Lending a man to another site.
 *
 * <p>The destination list is the company directory, not the sites this user works at: the
 * whole point is to send him somewhere else, usually to a supervisor whose sites this one
 * cannot open. From the effective date he is on that site's roll and off this one, and the
 * receiving supervisor marks him — there is no acceptance step, because on a site the man
 * has simply turned up.</p>
 *
 * <p>The date defaults to tomorrow. A posting cannot be closed before the day it opened, so
 * a man taken on this morning cannot also be moved on this morning; and in practice the
 * transfer is arranged the day before he walks to the other site.</p>
 */
export function TransferWorkerDialog({ worker, onClose }: Props) {
  const directory = useSiteDirectory();
  const transfer = useTransferWorker();
  const [serverError, setServerError] = useState<string | null>(null);

  const {
    control,
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<TransferForm>({
    resolver: zodResolver(transferSchema),
    defaultValues: { siteId: '', effectiveFrom: tomorrowIso() },
  });

  useEffect(() => {
    if (worker) {
      setServerError(null);
      reset({ siteId: '', effectiveFrom: tomorrowIso() });
    }
  }, [worker, reset]);

  const submit = handleSubmit(async (values) => {
    if (!worker) {
      return;
    }
    setServerError(null);
    try {
      await transfer.mutateAsync({
        workerId: worker.id,
        siteId: values.siteId,
        effectiveFrom: values.effectiveFrom,
      });
      onClose();
    } catch (error) {
      setServerError(apiErrorDetail(error));
    }
  });

  const destinations = (directory.data ?? []).filter(
    (site) => site.id !== worker?.currentSiteId && site.status !== 'CLOSED',
  );

  return (
    <Dialog open={worker !== null} onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Transfer {worker?.fullName}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {serverError && <Alert severity="error">{serverError}</Alert>}
          <DialogContentText>
            From the date you choose he comes off your roll and onto theirs, and their
            supervisor marks his attendance.
          </DialogContentText>

          <Controller
            control={control}
            name="siteId"
            render={({ field }) => (
              <TextField
                {...field}
                select
                label="Send him to"
                error={!!errors.siteId}
                helperText={errors.siteId?.message ?? 'Any site in the company, on any project.'}
              >
                {destinations.map((site) => (
                  <MenuItem key={site.id} value={site.id}>
                    {site.code} — {site.name}
                  </MenuItem>
                ))}
              </TextField>
            )}
          />
          <TextField
            label="From"
            type="date"
            InputLabelProps={{ shrink: true }}
            error={!!errors.effectiveFrom}
            helperText={errors.effectiveFrom?.message ?? 'His first day at the new site.'}
            {...register('effectiveFrom')}
          />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" color="secondary" onClick={submit} disabled={isSubmitting}>
          Transfer
        </Button>
      </DialogActions>
    </Dialog>
  );
}

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
import { useRecordVendorAdvance } from './api';
import { vendorAdvanceSchema, type VendorAdvanceForm } from './schema';

interface Props {
  open: boolean;
  vendorId: string;
  vendorName: string;
  onClose: () => void;
}

const MODES = [
  { value: 'BANK', label: 'Bank transfer' },
  { value: 'UPI', label: 'UPI' },
  { value: 'CHEQUE', label: 'Cheque' },
  { value: 'CASH', label: 'Cash' },
];

/**
 * Cash to a supplier before any bill of his exists.
 *
 * <p>It is how a lorry of steel gets loaded, and until now the system had no way to write it
 * down — a payment had to name a bill, so the advance went in a second book and the
 * supplier's account and ours stopped agreeing.</p>
 *
 * <p>It is not set off against any particular bill here. Which of his bills it eventually
 * covers is a question his bills answer as they arrive, not one to guess on the day the
 * money leaves.</p>
 */
export function RecordAdvanceDialog({ open, vendorId, vendorName, onClose }: Props) {
  const recordAdvance = useRecordVendorAdvance();
  const [serverError, setServerError] = useState<string | null>(null);

  const {
    control,
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<VendorAdvanceForm>({
    resolver: zodResolver(vendorAdvanceSchema),
    defaultValues: emptyAdvance(),
  });

  useEffect(() => {
    if (open) {
      setServerError(null);
      reset(emptyAdvance());
    }
  }, [open, reset]);

  const submit = handleSubmit(async (values) => {
    setServerError(null);
    try {
      await recordAdvance.mutateAsync({ vendorId, ...values });
      onClose();
    } catch (error) {
      setServerError(apiErrorDetail(error));
    }
  });

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Advance to {vendorName}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {serverError && <Alert severity="error">{serverError}</Alert>}
          <Alert severity="info">
            This goes against the supplier, not against any one bill. It is set off as his
            bills arrive.
          </Alert>

          <TextField
            label="Date it went out"
            type="date"
            InputLabelProps={{ shrink: true }}
            error={!!errors.paymentDate}
            helperText={errors.paymentDate?.message}
            {...register('paymentDate')}
          />
          <TextField
            label="Amount"
            type="number"
            inputMode="decimal"
            error={!!errors.amount}
            helperText={errors.amount?.message}
            {...register('amount')}
          />
          <Controller
            control={control}
            name="paymentMode"
            render={({ field }) => (
              <TextField {...field} select label="How it was paid">
                {MODES.map((mode) => (
                  <MenuItem key={mode.value} value={mode.value}>
                    {mode.label}
                  </MenuItem>
                ))}
              </TextField>
            )}
          />
          <TextField
            label="Reference (optional)"
            error={!!errors.referenceNumber}
            helperText={errors.referenceNumber?.message ?? 'Cheque number, UTR, anything traceable.'}
            {...register('referenceNumber')}
          />
          <TextField
            label="What it is against"
            multiline
            minRows={2}
            error={!!errors.remarks}
            helperText={
              errors.remarks?.message ??
              'Required. Six months on, an unexplained advance reads as a payment somebody forgot to attach to a bill.'
            }
            {...register('remarks')}
          />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" color="secondary" onClick={submit} disabled={isSubmitting}>
          Record the advance
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function emptyAdvance(): VendorAdvanceForm {
  return {
    paymentDate: new Date().toISOString().slice(0, 10),
    amount: 0,
    paymentMode: 'BANK',
    referenceNumber: '',
    remarks: '',
  };
}

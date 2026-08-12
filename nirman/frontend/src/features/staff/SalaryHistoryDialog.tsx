import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import { useRecordSalary, useSalaryHistory } from './api';
import { salaryRevisionSchema, type SalaryRevisionForm } from './schema';
import type { StaffProfile } from './types';

interface Props {
  open: boolean;
  member: StaffProfile;
  onClose: () => void;
}

/**
 * What a member is actually paid, and how it got there.
 *
 * <p>Appended, never edited. A raise in April must not restate what March cost — the same
 * rule that makes attendance freeze its wage rate at verification, applied to the salaried
 * half of the payroll. So there is no edit button on a row: a figure that was wrong is
 * corrected by a revision from the right date, and both stay on the record.</p>
 */
export function SalaryHistoryDialog({ open, member, onClose }: Props) {
  const history = useSalaryHistory(open ? member.userId : undefined);
  const record = useRecordSalary();
  const [serverError, setServerError] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<SalaryRevisionForm>({
    resolver: zodResolver(salaryRevisionSchema),
    defaultValues: emptyRevision(),
  });

  useEffect(() => {
    if (open) {
      setServerError(null);
      reset(emptyRevision());
    }
  }, [open, reset]);

  const submit = handleSubmit(async (values) => {
    setServerError(null);
    try {
      await record.mutateAsync({ userId: member.userId, ...values });
      reset(emptyRevision());
    } catch (error) {
      setServerError(apiErrorDetail(error));
    }
  });

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Salary — {member.fullName}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {serverError && <Alert severity="error">{serverError}</Alert>}

          <Typography color="text.secondary">
            {member.currentSalary === undefined
              ? 'No salary recorded yet. What was agreed is on the record; this is what is actually paid.'
              : `Paid today: ${formatAmount(member.currentSalary)} a month.`}
          </Typography>

          {history.isError && <Alert severity="error">{apiErrorDetail(history.error)}</Alert>}
          {history.data && history.data.length > 0 && (
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>From</TableCell>
                  <TableCell align="right">A month</TableCell>
                  <TableCell>Why</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {history.data.map((revision) => (
                  <TableRow key={revision.id}>
                    <TableCell>{revision.effectiveFrom}</TableCell>
                    <TableCell align="right">{formatAmount(revision.monthlyAmount)}</TableCell>
                    <TableCell>{revision.reason}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}

          <Divider textAlign="left">
            <Typography variant="overline" color="text.secondary">
              Record a change
            </Typography>
          </Divider>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="A month"
              type="number"
              fullWidth
              inputMode="decimal"
              error={!!errors.monthlyAmount}
              helperText={errors.monthlyAmount?.message}
              {...register('monthlyAmount')}
            />
            <TextField
              label="From"
              type="date"
              fullWidth
              InputLabelProps={{ shrink: true }}
              error={!!errors.effectiveFrom}
              helperText={errors.effectiveFrom?.message ?? 'A future date is fine; it applies then.'}
              {...register('effectiveFrom')}
            />
          </Stack>
          <TextField
            label="Why it moved"
            error={!!errors.reason}
            helperText={
              errors.reason?.message ?? 'Confirmed off probation, annual raise, change of role…'
            }
            {...register('reason')}
          />
          <Button
            variant="outlined"
            onClick={submit}
            disabled={isSubmitting}
            sx={{ alignSelf: 'flex-start' }}
          >
            Add the revision
          </Button>
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  );
}

function emptyRevision(): SalaryRevisionForm {
  return {
    monthlyAmount: 0,
    effectiveFrom: new Date().toISOString().slice(0, 10),
    reason: '',
  };
}

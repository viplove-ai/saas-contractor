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
import { useForm, useWatch } from 'react-hook-form';
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

/** The share of the packet the Code on Wages treats as wages however the structure is written. */
const WAGE_FLOOR_SHARE = 0.5;

/**
 * What a member is actually paid, what it is made of, and how it got there.
 *
 * <p>Appended, never edited. A raise in April must not restate what March cost — the same
 * rule that makes attendance freeze its wage rate at verification, applied to the salaried
 * half of the payroll. So there is no edit button on a row: a figure that was wrong is
 * corrected by a revision from the right date, and both stay on the record.</p>
 *
 * <p><b>The parts, not a total.</b> The provident fund is computed on basic and dearness
 * allowance, the state insurance on the whole packet, and gratuity on the same wage the fund
 * uses — so a single gross answers none of the three, and a payslip cannot be drawn from one.
 * The gross box is therefore not a box: it is the sum, shown while typing, and the office is
 * spared a total that can disagree with the lines above it.</p>
 *
 * <p><b>The wage line under the form is the point of the screen.</b> An office writing a low
 * basic and large allowances to keep the fund down learns here, before it saves, that the law
 * lifts the wage to half the packet anyway — which is the single thing about the 2025 codes
 * that changes what a contractor's payroll costs.</p>
 */
export function SalaryHistoryDialog({ open, member, onClose }: Props) {
  const history = useSalaryHistory(open ? member.userId : undefined);
  const record = useRecordSalary();
  const [serverError, setServerError] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    control,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<SalaryRevisionForm>({
    resolver: zodResolver(salaryRevisionSchema),
    defaultValues: emptyRevision(),
  });

  const typed = useWatch({ control });
  const gross =
    num(typed.basic) +
    num(typed.dearnessAllowance) +
    num(typed.hra) +
    num(typed.conveyance) +
    num(typed.otherAllowance);
  // The same maximum the server computes, so the two never tell the office different things.
  const statutoryWages = Math.max(
    num(typed.basic) + num(typed.dearnessAllowance),
    round2(gross * WAGE_FLOOR_SHARE),
  );
  const liftedByTheRule = gross > 0 && statutoryWages > num(typed.basic) + num(typed.dearnessAllowance);

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
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="md">
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
                  <TableCell align="right">Basic</TableCell>
                  <TableCell align="right">DA</TableCell>
                  <TableCell align="right">HRA</TableCell>
                  <TableCell align="right">Conveyance</TableCell>
                  <TableCell align="right">Other</TableCell>
                  <TableCell align="right">Gross</TableCell>
                  <TableCell align="right">Statutory wage</TableCell>
                  <TableCell>Why</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {history.data.map((revision) => (
                  <TableRow key={revision.id}>
                    <TableCell>{revision.effectiveFrom}</TableCell>
                    {revision.structured ? (
                      <>
                        <TableCell align="right">{formatAmount(revision.basic ?? 0)}</TableCell>
                        <TableCell align="right">
                          {formatAmount(revision.dearnessAllowance ?? 0)}
                        </TableCell>
                        <TableCell align="right">{formatAmount(revision.hra ?? 0)}</TableCell>
                        <TableCell align="right">
                          {formatAmount(revision.conveyance ?? 0)}
                        </TableCell>
                        <TableCell align="right">
                          {formatAmount(revision.otherAllowance ?? 0)}
                        </TableCell>
                      </>
                    ) : (
                      /*
                        A row from before the structure existed. It says so rather than
                        showing five zeroes, because five zeroes is a claim about how the
                        salary was made up and this row makes no such claim.
                      */
                      <TableCell colSpan={5} sx={{ color: 'text.secondary', fontStyle: 'italic' }}>
                        A total with no breakdown — no payslip can be drawn from it
                      </TableCell>
                    )}
                    <TableCell align="right">{formatAmount(revision.monthlyAmount)}</TableCell>
                    <TableCell align="right">
                      {revision.statutoryWages === undefined
                        ? '—'
                        : formatAmount(revision.statutoryWages)}
                    </TableCell>
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
              label="Basic pay"
              type="number"
              fullWidth
              inputMode="decimal"
              error={!!errors.basic}
              helperText={errors.basic?.message ?? 'The fund and gratuity stand on this'}
              {...register('basic')}
            />
            <TextField
              label="Dearness allowance"
              type="number"
              fullWidth
              inputMode="decimal"
              error={!!errors.dearnessAllowance}
              helperText={errors.dearnessAllowance?.message ?? 'Counts as wages, like the basic'}
              {...register('dearnessAllowance')}
            />
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="House rent allowance"
              type="number"
              fullWidth
              inputMode="decimal"
              error={!!errors.hra}
              helperText={errors.hra?.message}
              {...register('hra')}
            />
            <TextField
              label="Conveyance"
              type="number"
              fullWidth
              inputMode="decimal"
              error={!!errors.conveyance}
              helperText={errors.conveyance?.message}
              {...register('conveyance')}
            />
            <TextField
              label="Other allowances"
              type="number"
              fullWidth
              inputMode="decimal"
              error={!!errors.otherAllowance}
              helperText={errors.otherAllowance?.message ?? 'Special, washing, education…'}
              {...register('otherAllowance')}
            />
          </Stack>

          <Alert severity={liftedByTheRule ? 'warning' : 'info'}>
            <Typography variant="body2">
              Gross <strong>{formatAmount(gross)}</strong> a month. Wages for the provident
              fund, gratuity and any statutory bonus:{' '}
              <strong>{formatAmount(statutoryWages)}</strong>.
            </Typography>
            {liftedByTheRule && (
              <Typography variant="body2" sx={{ mt: 0.5 }}>
                The allowances come to more than half the packet, so the Code on Wages counts
                the excess as wages anyway — the fund is charged on{' '}
                {formatAmount(statutoryWages)}, not on the basic. Writing a low basic does not
                reduce it.
              </Typography>
            )}
          </Alert>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Professional tax a month"
              type="number"
              fullWidth
              inputMode="decimal"
              error={!!errors.professionalTax}
              helperText={
                errors.professionalTax?.message ?? "What your State charges at this salary"
              }
              {...register('professionalTax')}
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
    basic: 0,
    dearnessAllowance: 0,
    hra: 0,
    conveyance: 0,
    otherAllowance: 0,
    professionalTax: 0,
    effectiveFrom: new Date().toISOString().slice(0, 10),
    reason: '',
  };
}

function num(value: unknown): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

/**
 * Half up on the decimal a person would have written. `Math.round(v * 100) / 100` sends
 * 1.005 down and the server sends it up — the same trap the measurement sheet's round2 was
 * written to avoid, and here it would show the office a wage a rupee off what is charged.
 */
function round2(value: number): number {
  return Number((Math.round((value + Number.EPSILON) * 100) / 100).toFixed(2));
}

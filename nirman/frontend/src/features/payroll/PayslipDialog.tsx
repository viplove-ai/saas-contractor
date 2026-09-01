import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import { useUpdatePayslip } from './api';
import type { Payslip } from './types';

interface Props {
  open: boolean;
  slip: Payslip;
  onClose: () => void;
}

/**
 * The four things about one month that no rule can work out.
 *
 * <p>Everything else on a payslip falls out of the structure and the statutes: the components,
 * the proration, the provident fund, the insurance. What is left is what somebody has to
 * know — how many days he was there, what he worked beyond the shift, what the tax office is
 * owed, and what is being recovered from him — and this is the only screen that asks.</p>
 *
 * <p><b>The tax is typed and not computed, and that is deliberate.</b> It depends on the
 * member's election between the two regimes, on declarations he makes and proofs he produces,
 * and this system holds none of the three. A figure guessed here is money deposited to the
 * government in somebody else's name, and the mistake surfaces a year later at his return.</p>
 *
 * <p><b>Overtime is entered as hours.</b> The Code on Wages sets the rate at twice the
 * ordinary rate, so the amount follows from the hours and does not need to be guessed at seven
 * in the evening. The amount box below it is for an office that has agreed something else; the
 * hours still print beside whatever it puts there.</p>
 */
export function PayslipDialog({ open, slip, onClose }: Props) {
  const update = useUpdatePayslip();
  const [serverError, setServerError] = useState<string | null>(null);
  const [form, setForm] = useState(() => toForm(slip));

  useEffect(() => {
    if (open) {
      setServerError(null);
      setForm(toForm(slip));
    }
  }, [open, slip]);

  const submit = async () => {
    setServerError(null);
    try {
      await update.mutateAsync({
        id: slip.id,
        paidDays: Number(form.paidDays),
        overtimeHours: numberOrUndefined(form.overtimeHours),
        overtimeAmount: numberOrUndefined(form.overtimeAmount),
        tds: numberOrUndefined(form.tds),
        salaryAdvance: numberOrUndefined(form.salaryAdvance),
        otherDeduction: numberOrUndefined(form.otherDeduction),
        otherDeductionNote: form.otherDeductionNote || undefined,
        remarks: form.remarks || undefined,
        version: slip.version,
      });
      onClose();
    } catch (error) {
      setServerError(apiErrorDetail(error));
    }
  };

  const set = (key: keyof ReturnType<typeof toForm>) => (event: { target: { value: string } }) =>
    setForm((current) => ({ ...current, [key]: event.target.value }));

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{slip.employeeName}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {serverError && <Alert severity="error">{serverError}</Alert>}

          <Typography variant="body2" color="text.secondary">
            Structure {formatAmount(slip.structGross)} a month — basic{' '}
            {formatAmount(slip.structBasic)}. Wages for the fund and gratuity:{' '}
            {formatAmount(slip.statutoryWages)}.
          </Typography>

          <TextField
            label={`Days paid, out of ${slip.payableDays}`}
            type="number"
            value={form.paidDays}
            onChange={set('paidDays')}
            inputMode="decimal"
            helperText="Half days are real; two decimals are accepted"
          />

          <Divider textAlign="left">
            <Typography variant="overline" color="text.secondary">
              Beyond the shift
            </Typography>
          </Divider>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Overtime hours"
              type="number"
              fullWidth
              value={form.overtimeHours}
              onChange={set('overtimeHours')}
              helperText="Paid at twice the ordinary rate"
            />
            <TextField
              label="Overtime amount"
              type="number"
              fullWidth
              value={form.overtimeAmount}
              onChange={set('overtimeAmount')}
              helperText="Only if you have agreed a different rate"
            />
          </Stack>

          <Divider textAlign="left">
            <Typography variant="overline" color="text.secondary">
              Deductions
            </Typography>
          </Divider>
          <Alert severity="info" sx={{ py: 0.5 }}>
            The provident fund ({formatAmount(slip.pfEmployee)}) and the state insurance (
            {formatAmount(slip.esiEmployee)}) are computed and cannot be typed.
          </Alert>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="TDS (u/s 392)"
              type="number"
              fullWidth
              value={form.tds}
              onChange={set('tds')}
              helperText="Typed: the regime and the declarations are not held here"
            />
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Salary advance recovered"
              type="number"
              fullWidth
              value={form.salaryAdvance}
              onChange={set('salaryAdvance')}
            />
            <TextField
              label="Other deduction"
              type="number"
              fullWidth
              value={form.otherDeduction}
              onChange={set('otherDeduction')}
            />
          </Stack>
          <TextField
            label="What the other deduction is for"
            value={form.otherDeductionNote}
            onChange={set('otherDeductionNote')}
            helperText="Required where there is one — a deduction with no reason is the one he telephones about"
          />
          <TextField
            label="Remarks on the slip"
            value={form.remarks}
            onChange={set('remarks')}
          />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" onClick={submit} disabled={update.isPending}>
          Save
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function toForm(slip: Payslip) {
  return {
    paidDays: String(slip.paidDays),
    overtimeHours: String(slip.overtimeHours),
    overtimeAmount: '',
    tds: String(slip.tds),
    salaryAdvance: String(slip.salaryAdvance),
    otherDeduction: String(slip.otherDeduction),
    otherDeductionNote: slip.otherDeductionNote ?? '',
    remarks: slip.remarks ?? '',
  };
}

/**
 * A cleared box means "leave it to the rule", which is not the same as zero — the overtime
 * amount especially, where an empty box asks the server for the statutory double and a zero
 * asserts that the hours are unpaid.
 */
function numberOrUndefined(value: string): number | undefined {
  return value.trim() === '' ? undefined : Number(value);
}

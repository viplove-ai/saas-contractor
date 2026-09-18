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
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import { usePayWages, useSettlement, useWorkers, type PayWagesInput } from './api';
import { PAYMENT_MODE_LABEL, type WagePaymentMode } from './types';

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

const MODES: WagePaymentMode[] = ['CASH', 'BANK', 'UPI', 'CHEQUE'];

interface Props {
  siteId: string;
  workerId?: string | undefined;
  onClose: () => void;
}

/**
 * The payday: wages handed to a man against what his ledger says he is owed.
 *
 * <p>The amount is offered as the whole of what he is owed and can be typed down — a man
 * paid part of his balance is a normal case. It cannot be typed up: the server refuses a
 * figure past the balance, because money handed over against wages not yet earned is an
 * advance, and there is a dialog for one. The refusal is shown here in the server's words
 * rather than pre-empted, so the two never disagree about the figure.</p>
 *
 * <p>What the payday will close is said before the button is pressed. The ledger netted his
 * advances out of the balance when they were approved; recording the payment is what marks
 * them recovered, and an office counting out ₹7,000 wants to know that the ₹2,000 he drew in
 * the first week is what made it ₹7,000 and not ₹9,000.</p>
 */
export function PayWagesDialog({ siteId, workerId: presetWorkerId, onClose }: Props) {
  const workers = useWorkers(siteId, '', 'active');
  const pay = usePayWages();

  const [workerId, setWorkerId] = useState(presetWorkerId ?? '');
  const [paymentDate, setPaymentDate] = useState(today());
  // Null until the office types: the box then shows the whole of what he is owed, and a
  // figure typed down is never overwritten because the query answered late.
  const [typedAmount, setTypedAmount] = useState<string | null>(null);
  const [paymentMode, setPaymentMode] = useState<WagePaymentMode>('CASH');
  const [referenceNumber, setReferenceNumber] = useState('');
  const [remarks, setRemarks] = useState('');
  const [error, setError] = useState<string | null>(null);

  const settlement = useSettlement(workerId || undefined);
  const owed = settlement.data?.netPayable ?? 0;
  const openAdvances = settlement.data?.openAdvanceAmount ?? 0;
  const amount = typedAmount ?? (owed > 0 ? owed.toFixed(2) : '');

  const complete = Boolean(workerId) && Number(amount) > 0 && Boolean(paymentDate);

  async function save() {
    setError(null);
    const input: PayWagesInput = {
      id: crypto.randomUUID(),
      siteId,
      workerId,
      paymentDate,
      amount: Number(amount),
      paymentMode,
      referenceNumber: referenceNumber.trim() || undefined,
      remarks: remarks.trim() || undefined,
    };
    try {
      await pay.mutateAsync(input);
      onClose();
    } catch (cause) {
      setError(apiErrorDetail(cause));
    }
  }

  return (
    <Dialog open fullWidth maxWidth="sm" onClose={onClose}>
      <DialogTitle>Pay wages</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}

          <TextField
            select
            label="To whom"
            value={workerId}
            onChange={(e) => {
              setWorkerId(e.target.value);
              setTypedAmount(null);
            }}
            helperText="The active men on this site."
          >
            {(workers.data?.content ?? []).map((worker) => (
              <MenuItem key={worker.id} value={worker.id}>
                {worker.fullName} — {worker.workerCode}
              </MenuItem>
            ))}
          </TextField>

          {settlement.data &&
            (owed > 0 ? (
              <Alert severity="info">
                He is owed {formatAmount(owed)}.
                {openAdvances > 0 &&
                  ` Paying him closes ${formatAmount(openAdvances)} of advances his wages have covered.`}
              </Alert>
            ) : owed < 0 ? (
              <Alert severity="warning">
                He is {formatAmount(-owed)} ahead of his wages. Nothing can be paid until his
                verified days catch up.
              </Alert>
            ) : (
              <Alert severity="info">Nothing is owed to him as things stand.</Alert>
            ))}

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="How much"
              type="number"
              inputMode="decimal"
              value={amount}
              onChange={(e) => setTypedAmount(e.target.value)}
              helperText={owed > 0 ? 'Up to what he is owed. Anything past it is an advance.' : ' '}
              sx={{ flex: 1 }}
            />
            <TextField
              label="Paid on"
              type="date"
              value={paymentDate}
              onChange={(e) => setPaymentDate(e.target.value)}
              InputLabelProps={{ shrink: true }}
              sx={{ flex: 1 }}
            />
          </Stack>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              select
              label="How"
              value={paymentMode}
              onChange={(e) => setPaymentMode(e.target.value as WagePaymentMode)}
              sx={{ flex: 1 }}
            >
              {MODES.map((mode) => (
                <MenuItem key={mode} value={mode}>
                  {PAYMENT_MODE_LABEL[mode]}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              label="Reference"
              value={referenceNumber}
              onChange={(e) => setReferenceNumber(e.target.value)}
              helperText="UTR or cheque number, if it did not go by hand."
              sx={{ flex: 1 }}
            />
          </Stack>

          <TextField
            label="Remarks"
            value={remarks}
            onChange={(e) => setRemarks(e.target.value)}
          />

          <Typography variant="caption" color="text.secondary">
            Posted to his account as paid. Wages were counted as cost on the day they were
            verified, so this books no expense.
          </Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          disabled={!complete || owed <= 0 || pay.isPending}
          onClick={save}
        >
          Pay him
        </Button>
      </DialogActions>
    </Dialog>
  );
}

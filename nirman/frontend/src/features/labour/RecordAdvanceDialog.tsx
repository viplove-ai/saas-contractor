import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import { useRecordAdvance, useSettlement, useWorkers, type RecordAdvanceInput } from './api';
import { PAYMENT_MODE_LABEL, type AdvancePaymentMode } from './types';

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

interface Props {
  siteId: string;
  /** Pre-picked when opened from a man's own row; blank from the register's button. */
  workerId?: string | undefined;
  onClose: () => void;
}

/**
 * Cash or goods handed to a worker against his wages.
 *
 * <p>Recording it is a statement that something changed hands. It is deducted from nothing
 * until somebody holding the approval agrees that it comes out of his wages — which is why
 * the dialog says so under the button, so that the man typing it does not go looking for the
 * deduction on the settlement sheet an hour later.</p>
 *
 * <p><b>Whether it is recoverable is asked, never assumed.</b> Cash usually comes back out of
 * his wages. Ration, footwear and medicine vary by item and by firm, and the one question the
 * field sheets could not answer was which — so it is a switch on every advance rather than a
 * rule somewhere else (docs/00, assumption 28).</p>
 *
 * <p>What he already stands at is shown beside his name, for the reason the float dialog
 * shows the holder's balance: how much to hand a man is decided against what he is owed, and
 * a man already ahead of his wages is worth knowing about before another rupee goes out.</p>
 */
export function RecordAdvanceDialog({ siteId, workerId: presetWorkerId, onClose }: Props) {
  const workers = useWorkers(siteId, '', 'active');
  const record = useRecordAdvance();

  const [workerId, setWorkerId] = useState(presetWorkerId ?? '');
  const [advanceDate, setAdvanceDate] = useState(today());
  const [amount, setAmount] = useState('');
  const [paymentMode, setPaymentMode] = useState<AdvancePaymentMode>('CASH');
  const [purpose, setPurpose] = useState('');
  const [recoverable, setRecoverable] = useState(true);
  const [remarks, setRemarks] = useState('');
  const [error, setError] = useState<string | null>(null);

  const standing = useSettlement(workerId || undefined);
  const complete = Boolean(workerId) && Number(amount) > 0 && Boolean(advanceDate);

  async function save() {
    setError(null);
    const input: RecordAdvanceInput = {
      id: crypto.randomUUID(),
      siteId,
      workerId,
      advanceDate,
      amount: Number(amount),
      paymentMode,
      purpose: purpose.trim() || undefined,
      recoverable,
      remarks: remarks.trim() || undefined,
    };
    try {
      await record.mutateAsync(input);
      onClose();
    } catch (cause) {
      setError(apiErrorDetail(cause));
    }
  }

  return (
    <Dialog open fullWidth maxWidth="sm" onClose={onClose}>
      <DialogTitle>Record an advance</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}

          <TextField
            select
            label="To whom"
            value={workerId}
            onChange={(e) => setWorkerId(e.target.value)}
            helperText={
              workers.data && workers.data.content.length === 0
                ? 'Nobody active is posted to this site.'
                : 'The active men on this site.'
            }
          >
            {(workers.data?.content ?? []).map((worker) => (
              <MenuItem key={worker.id} value={worker.id}>
                {worker.fullName} — {worker.workerCode}
              </MenuItem>
            ))}
          </TextField>

          {standing.data && standing.data.netPayable !== 0 && (
            <Alert severity={standing.data.netPayable < 0 ? 'warning' : 'info'}>
              {standing.data.netPayable < 0
                ? `He is already ${formatAmount(-standing.data.netPayable)} ahead of his wages.`
                : `He is owed ${formatAmount(standing.data.netPayable)} as things stand.`}
            </Alert>
          )}

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="How much"
              type="number"
              inputMode="decimal"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              sx={{ flex: 1 }}
            />
            <TextField
              label="Handed over on"
              type="date"
              value={advanceDate}
              onChange={(e) => setAdvanceDate(e.target.value)}
              InputLabelProps={{ shrink: true }}
              sx={{ flex: 1 }}
            />
          </Stack>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              select
              label="How"
              value={paymentMode}
              onChange={(e) => setPaymentMode(e.target.value as AdvancePaymentMode)}
              sx={{ flex: 1 }}
            >
              {(Object.keys(PAYMENT_MODE_LABEL) as AdvancePaymentMode[]).map((mode) => (
                <MenuItem key={mode} value={mode}>
                  {PAYMENT_MODE_LABEL[mode]}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              label="What for"
              value={purpose}
              onChange={(e) => setPurpose(e.target.value)}
              helperText="Cash, ration, footwear, medicine — whatever it was."
              sx={{ flex: 1 }}
            />
          </Stack>

          <FormControlLabel
            control={
              <Switch checked={recoverable} onChange={(e) => setRecoverable(e.target.checked)} />
            }
            label={
              <Stack spacing={0}>
                <Typography variant="body2">Comes out of his wages</Typography>
                <Typography variant="caption" color="text.secondary">
                  {recoverable
                    ? 'Deducted from what he is owed once approved.'
                    : 'Borne by the firm: recorded as handed over, never deducted.'}
                </Typography>
              </Stack>
            }
          />

          <TextField
            label="Remarks"
            value={remarks}
            onChange={(e) => setRemarks(e.target.value)}
          />

          <Typography variant="caption" color="text.secondary">
            Recording it says the money changed hands. It comes off his wages only once
            somebody approves it.
          </Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!complete || record.isPending} onClick={save}>
          Record it
        </Button>
      </DialogActions>
    </Dialog>
  );
}

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
import { useState } from 'react';
import { formatAmount } from '../../shared/formatters';
import { useFloatBalances, useFloatHolders, type IssueFloatInput } from './api';

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * Handing cash to somebody on a site.
 *
 * <p>The person is picked off the site's own postings rather than off the user list: the user
 * list is behind {@code user:read}, which is an administrator's, so an accountant holding
 * {@code advance:issue} could reach the call that hands over the money and not the one that says
 * who is standing there to take it.</p>
 *
 * <p><b>What he is already carrying is shown beside his name.</b> A float is handed over to top
 * somebody up, and the office deciding how much to give him is deciding it against what he has
 * — including the case where the figure is negative, which means he is owed money and this
 * hand-over is squaring it rather than starting something new. Making him ask a second screen
 * for that is how a man ends up holding ₹40,000 nobody meant to give him.</p>
 *
 * <p>The purpose is required by the server and it is right that it is: a float with no reason on
 * it is indistinguishable, a month later, from cash that went out and was never explained.</p>
 */
export function IssueFloatDialog({
  siteId,
  saving,
  onClose,
  onSave,
}: {
  siteId: string;
  saving: boolean;
  onClose: () => void;
  onSave: (input: IssueFloatInput) => void;
}) {
  const holders = useFloatHolders(siteId);
  const balances = useFloatBalances(siteId);

  const [issuedToUserId, setIssuedToUserId] = useState('');
  const [advanceDate, setAdvanceDate] = useState(today());
  const [amount, setAmount] = useState('');
  const [paymentMode, setPaymentMode] = useState('CASH');
  const [referenceNumber, setReferenceNumber] = useState('');
  const [purpose, setPurpose] = useState('');
  const [remarks, setRemarks] = useState('');

  const standing = (balances.data ?? []).find((row) => row.userId === issuedToUserId);
  const complete = Boolean(issuedToUserId) && Number(amount) > 0 && purpose.trim().length > 0;

  return (
    <Dialog open fullWidth maxWidth="sm" onClose={onClose}>
      <DialogTitle>Hand over cash</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <TextField
            select
            label="To whom"
            value={issuedToUserId}
            onChange={(e) => setIssuedToUserId(e.target.value)}
            helperText={
              holders.data && holders.data.length === 0
                ? 'Nobody is posted to this site. Post somebody to it first.'
                : 'The people posted to this site.'
            }
          >
            {(holders.data ?? []).map((holder) => (
              <MenuItem key={holder.userId} value={holder.userId}>
                {holder.fullName ?? holder.username}
                {holder.roleCodes.length > 0 && ` — ${holder.roleCodes.join(', ').toLowerCase()}`}
              </MenuItem>
            ))}
          </TextField>

          {/*
            What he already has, before another rupee is added to it. Silent when he has
            nothing and nothing is owed to him, because "he is holding 0" is what an empty
            line already says.
          */}
          {standing && standing.inHandAmount !== 0 && (
            <Alert severity={standing.inHandAmount < 0 ? 'warning' : 'info'}>
              {standing.inHandAmount < 0
                ? `He is already owed ${formatAmount(-standing.inHandAmount)} — he has paid that much past his float.`
                : `He is already holding ${formatAmount(standing.inHandAmount)}.`}
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
              onChange={(e) => setPaymentMode(e.target.value)}
              sx={{ flex: 1 }}
            >
              <MenuItem value="CASH">Cash</MenuItem>
              <MenuItem value="BANK">Bank transfer</MenuItem>
              <MenuItem value="UPI">UPI</MenuItem>
              <MenuItem value="CHEQUE">Cheque</MenuItem>
            </TextField>
            <TextField
              label="Reference"
              value={referenceNumber}
              onChange={(e) => setReferenceNumber(e.target.value)}
              helperText="UTR, cheque number — whatever proves it left the account."
              sx={{ flex: 1 }}
            />
          </Stack>

          <TextField
            label="What it is for"
            value={purpose}
            onChange={(e) => setPurpose(e.target.value)}
            helperText="A float with no reason on it cannot be told from cash nobody explained."
          />
          <TextField
            label="Remarks"
            value={remarks}
            onChange={(e) => setRemarks(e.target.value)}
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          disabled={!complete || saving}
          onClick={() =>
            onSave({
              siteId,
              issuedToUserId,
              advanceDate,
              amount: Number(amount),
              paymentMode,
              referenceNumber: referenceNumber.trim() || undefined,
              purpose: purpose.trim(),
              remarks: remarks.trim() || undefined,
            })
          }
        >
          Hand it over
        </Button>
      </DialogActions>
    </Dialog>
  );
}

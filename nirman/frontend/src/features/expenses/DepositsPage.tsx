import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { tokens } from '../../app/theme';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import { useSelectedSite } from '../../shared/siteSelection';
import { useAuth } from '../auth/AuthContext';
import { useDeposits, useSettleDeposit, useSites } from './api';
import type { DepositRow, RefundOutcome } from './types';

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * The money we placed and have not got back.
 *
 * <p>An electricity connection wants ₹12,000 against the meter, a plant hirer wants ₹25,000
 * down, a gas agency wants cylinder money. It goes out on an ordinary bill, and part of that
 * bill is not spending at all — it is the company's money sitting with somebody, and it comes
 * back when the meter is surrendered and the mixer goes home. A contractor running six sites
 * has a few lakh out this way, and until this screen the only record of it was the memory of
 * whoever paid.</p>
 *
 * <p>Two questions, and the screen is built around the first. <b>What is still out there, and
 * with whom</b> — that is the default view, and the overdue rows come first because a deposit
 * whose expected date has gone by is one nobody is chasing. <b>And what happened to the one I
 * paid last March</b> — the same screen with the settled rows shown, because sending that
 * question to a second screen is how it stops being asked.</p>
 *
 * <p>Settling is behind {@code payment:record}: recording that money came back is the mirror
 * of recording that money went out, the same accountant reading the same bank statement.</p>
 */
export function DepositsPage() {
  const { hasPermission } = useAuth();
  const sites = useSites();
  const [siteId, setSiteId] = useSelectedSite(sites.data, { allowAll: true });
  const [openOnly, setOpenOnly] = useState(true);
  const [settling, setSettling] = useState<DepositRow | null>(null);

  const register = useDeposits(siteId || undefined, openOnly);
  const canSettle = hasPermission('payment:record');

  return (
    <Stack spacing={2}>
      <Typography variant="h1">Deposits</Typography>
      <Typography color="text.secondary">
        Money placed with somebody and coming back — a meter security, plant hire money, a
        cylinder deposit. It is not what the work cost, and it is not on the job&apos;s figures.
      </Typography>

      <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
        <TextField
          select
          label="Site"
          value={siteId}
          onChange={(e) => setSiteId(e.target.value)}
          sx={{ minWidth: 220 }}
        >
          <MenuItem value="">Every site</MenuItem>
          {(sites.data ?? []).map((site) => (
            <MenuItem key={site.id} value={site.id}>
              {site.code} — {site.name}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          select
          label="Show"
          value={openOnly ? 'OPEN' : 'ALL'}
          onChange={(e) => setOpenOnly(e.target.value === 'OPEN')}
          sx={{ minWidth: 220 }}
        >
          <MenuItem value="OPEN">Still out there</MenuItem>
          <MenuItem value="ALL">Everything, settled included</MenuItem>
        </TextField>
      </Box>

      {register.isLoading && <CircularProgress size={24} />}
      {register.isError && <Alert severity="error">{apiErrorDetail(register.error)}</Alert>}

      {/*
        Outstanding is the figure the whole screen exists for. Placed and received sit beside
        it rather than being folded into it, because "we have placed eight lakh over the years
        and one and a half is still out" is two facts and the second one is the useful one.
      */}
      {register.data && (
        <Paper elevation={0} sx={{ p: 1.5, border: `1.6px solid ${tokens.ink}` }}>
          <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap>
            <Figure label="Still out there" value={formatAmount(register.data.outstanding)} />
            <Figure label="Placed" value={formatAmount(register.data.placed)} />
            <Figure label="Come back" value={formatAmount(register.data.received)} />
            {register.data.writtenOff > 0 && (
              <Figure label="Given up on" value={formatAmount(register.data.writtenOff)} />
            )}
            <Figure label="Deposits" value={String(register.data.depositCount)} />
          </Stack>
          {register.data.overdue > 0 && (
            <Typography variant="body2" sx={{ pt: 1, color: tokens.signal }}>
              {formatAmount(register.data.overdue)} was expected back before today and has not
              come. Somebody has to ask for it — nobody sends a deposit back unasked.
            </Typography>
          )}
        </Paper>
      )}

      {register.data?.rows.length === 0 && (
        <Alert severity="info">
          {openOnly
            ? 'Nothing is out there. Every deposit on file has come back or been closed.'
            : 'No bill on file carries a deposit yet. Tick “Part of this comes back to us” when '
              + 'you book one.'}
        </Alert>
      )}

      <Stack spacing={1.5}>
        {(register.data?.rows ?? []).map((row) => (
          <Paper key={row.expenseId} variant="outlined" sx={{ p: 2 }}>
            <Stack
              direction={{ xs: 'column', sm: 'row' }}
              spacing={1}
              justifyContent="space-between"
              alignItems={{ sm: 'flex-start' }}
            >
              <Box>
                <Typography fontWeight={600}>
                  {row.description}
                  {row.vendorName && <> · {row.vendorName}</>}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {row.expenseNumber} · {row.expenseDate}
                  {row.categoryName && <> · {row.categoryName}</>} · bill{' '}
                  {formatAmount(row.totalAmount)}
                </Typography>
              </Box>
              <Stack direction="row" spacing={1} alignItems="center">
                <DepositChip row={row} />
                {canSettle && row.outstandingAmount > 0 && (
                  <Button
                    variant="outlined"
                    onClick={() => setSettling(row)}
                    sx={{ minHeight: 44 }}
                  >
                    Settle
                  </Button>
                )}
              </Stack>
            </Stack>

            <Divider sx={{ my: 1.5 }} />

            <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap>
              <Figure label="Deposit" value={formatAmount(row.refundableAmount)} />
              <Figure label="Come back" value={formatAmount(row.refundedAmount)} />
              {row.writtenOffAmount > 0 && (
                <Figure label="Given up on" value={formatAmount(row.writtenOffAmount)} />
              )}
              <Figure label="Still out" value={formatAmount(row.outstandingAmount)} />
              {row.refundExpectedOn && (
                <Figure label="Expected back" value={row.refundExpectedOn} />
              )}
            </Stack>

            {/*
              What was done about it and when. Kept on the row rather than behind a click: the
              question "did we ever get the Kausani meter money" is answered by a line of text,
              and a screen that made somebody open each deposit to find out would not be read.
            */}
            {row.settlements.length > 0 && (
              <Stack spacing={0.5} sx={{ pt: 1.5 }}>
                {row.settlements.map((settlement) => (
                  <Typography key={settlement.id} variant="body2" color="text.secondary">
                    {settlement.settledOn} ·{' '}
                    {settlement.outcome === 'RECEIVED' ? 'came back' : 'given up on'}{' '}
                    {formatAmount(settlement.amount)}
                    {settlement.paymentMode && <> by {spellMode(settlement.paymentMode)}</>}
                    {settlement.referenceNumber && <> · {settlement.referenceNumber}</>}
                    {settlement.reason && <> — {settlement.reason}</>}
                  </Typography>
                ))}
              </Stack>
            )}
          </Paper>
        ))}
      </Stack>

      {settling && (
        <SettleDialog row={settling} onClose={() => setSettling(null)} />
      )}
    </Stack>
  );
}

/**
 * Recording what became of a deposit.
 *
 * <p>Two outcomes on one form, because they are answers to the same question asked of the
 * same row — and the write-off is the one that needs a sentence, since a deposit that
 * disappears from the register without an explanation cannot be asked about six months on.</p>
 */
function SettleDialog({ row, onClose }: { row: DepositRow; onClose: () => void }) {
  const settle = useSettleDeposit();
  const [outcome, setOutcome] = useState<RefundOutcome>('RECEIVED');
  const [settledOn, setSettledOn] = useState(today());
  const [amount, setAmount] = useState(String(row.outstandingAmount));
  const [paymentMode, setPaymentMode] = useState('BANK_TRANSFER');
  const [referenceNumber, setReferenceNumber] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);

  const value = Number(amount);
  const amountValid =
    Number.isFinite(value) && value > 0 && value <= row.outstandingAmount;
  const reasonGiven = outcome === 'RECEIVED' || reason.trim().length > 0;

  const save = () => {
    setError(null);
    settle.mutate(
      {
        expenseId: row.expenseId,
        outcome,
        settledOn,
        amount: value,
        paymentMode: outcome === 'RECEIVED' ? paymentMode : undefined,
        referenceNumber: outcome === 'RECEIVED' ? referenceNumber || undefined : undefined,
        reason: reason.trim() || undefined,
      },
      {
        onSuccess: onClose,
        onError: (failure) => setError(apiErrorDetail(failure)),
      },
    );
  };

  return (
    <Paper variant="outlined" sx={{ p: 2, borderColor: tokens.ink, borderWidth: 1.6 }}>
      <Typography variant="h2" sx={{ fontSize: '1.15rem' }}>
        Settle the deposit on {row.expenseNumber}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ pb: 2 }}>
        {formatAmount(row.outstandingAmount)} is still out there.
      </Typography>

      <Stack spacing={2}>
        <TextField
          select
          label="What happened"
          value={outcome}
          onChange={(e) => setOutcome(e.target.value as RefundOutcome)}
        >
          <MenuItem value="RECEIVED">The money came back</MenuItem>
          <MenuItem value="WRITTEN_OFF">It is not coming back</MenuItem>
        </TextField>

        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
          <TextField
            label="Amount"
            type="number"
            inputMode="decimal"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            error={amount.length > 0 && !amountValid}
            helperText={
              amount.length > 0 && !amountValid
                ? `Only ${formatAmount(row.outstandingAmount)} is still out on this one.`
                : 'Part of it is fine — the board often refunds in two goes'
            }
            sx={{ minWidth: 180 }}
          />
          <TextField
            label={outcome === 'RECEIVED' ? 'Received on' : 'Decided on'}
            type="date"
            value={settledOn}
            onChange={(e) => setSettledOn(e.target.value)}
            InputLabelProps={{ shrink: true }}
          />
        </Stack>

        {outcome === 'RECEIVED' && (
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              select
              label="How"
              value={paymentMode}
              onChange={(e) => setPaymentMode(e.target.value)}
              sx={{ minWidth: 200 }}
            >
              <MenuItem value="BANK_TRANSFER">Bank transfer</MenuItem>
              <MenuItem value="CHEQUE">Cheque</MenuItem>
              <MenuItem value="CASH">Cash</MenuItem>
              <MenuItem value="ADJUSTMENT">Adjusted against a bill</MenuItem>
            </TextField>
            <TextField
              label="Reference"
              value={referenceNumber}
              onChange={(e) => setReferenceNumber(e.target.value)}
              helperText="Cheque number, UTR, or the credit note it was adjusted against"
              sx={{ flexGrow: 1, minWidth: 200 }}
            />
          </Stack>
        )}

        <TextField
          label={outcome === 'RECEIVED' ? 'Anything worth noting' : 'Why is it not coming back'}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          multiline
          minRows={2}
          helperText={
            outcome === 'RECEIVED'
              ? 'Optional'
              : 'Required. A deposit that vanishes from the register without a reason cannot be '
                + 'asked about six months later.'
          }
        />

        {outcome === 'WRITTEN_OFF' && (
          <Alert severity="warning">
            This closes the deposit and books no cost. The loss belongs to the day it was
            decided, so book it as its own expense under a loss head if it is to appear in the
            month&apos;s figures.
          </Alert>
        )}

        {error && <Alert severity="error">{error}</Alert>}

        <Stack direction="row" spacing={2}>
          <Button onClick={onClose} disabled={settle.isPending}>
            Cancel
          </Button>
          <Button
            variant="contained"
            onClick={save}
            disabled={!amountValid || !reasonGiven || settle.isPending}
            sx={{ minHeight: 48 }}
          >
            {settle.isPending ? 'Saving…' : 'Record it'}
          </Button>
        </Stack>
      </Stack>
    </Paper>
  );
}

/** The stored code read as a person would say it. BANK_TRANSFER is not a word. */
function spellMode(mode: string): string {
  return mode.toLowerCase().replace(/_/g, ' ');
}

function DepositChip({ row }: { row: DepositRow }) {
  if (row.status === 'SETTLED') {
    return (
      <Chip
        label={row.writtenOffAmount > 0 ? 'Closed' : 'Come back'}
        size="small"
        sx={{ color: tokens.ok, bgcolor: '#E7F5EC', fontWeight: 600, borderRadius: 1 }}
      />
    );
  }
  if (row.overdue) {
    return (
      <Chip
        label="Overdue"
        size="small"
        sx={{ color: tokens.signal, bgcolor: '#FDEDE6', fontWeight: 600, borderRadius: 1 }}
      />
    );
  }
  return (
    <Chip
      label={row.status === 'PARTIAL' ? 'Part back' : 'Out there'}
      size="small"
      sx={{ color: tokens.ink, bgcolor: '#E8EDF2', fontWeight: 600, borderRadius: 1 }}
    />
  );
}

function Figure({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <Typography variant="overline" sx={{ color: tokens.annotation, display: 'block' }}>
        {label}
      </Typography>
      <Typography sx={{ fontFamily: '"IBM Plex Mono", monospace', fontWeight: 700 }}>
        {value}
      </Typography>
    </div>
  );
}

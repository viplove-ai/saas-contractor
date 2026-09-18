import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Paper,
  Stack,
  Typography,
} from '@mui/material';
import { tokens } from '../../app/theme';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import { RecordTable, type RecordColumn } from '../../shared/RecordTable';
import { useSettlement } from './api';
import { LEDGER_ENTRY_LABEL, type LedgerEntry } from './types';

interface Props {
  workerId: string | null;
  onClose: () => void;
  /** Offered when the caller may pay him; the dialog itself decides nothing about that. */
  onPay?: ((workerId: string) => void) | undefined;
}

/**
 * One man's account, the way the field sheet works it out at the foot of the column:
 * earned, less advances, less what he has been paid, is what he is owed.
 *
 * <p>The ledger lines are under the figures rather than instead of them, because the figure
 * is what a man disputes on payday and the lines are what the dispute is settled with. Every
 * line is derived — nobody types a balance — so what is shown is the same history the server
 * would show anybody else asking.</p>
 *
 * <p>The open advances are called out apart from the advance total: the ledger netted every
 * advance out of his wages the day it was approved, and what a payday does is mark them
 * recovered. "₹2,000 drawn, of which ₹500 still open" is what the office wants to know before
 * it counts out the cash.</p>
 */
export function SettlementDialog({ workerId, onClose, onPay }: Props) {
  const settlement = useSettlement(workerId ?? undefined);
  const sheet = settlement.data;

  const columns: RecordColumn<LedgerEntry>[] = [
    {
      key: 'date',
      header: 'Date',
      card: 'title',
      cell: (row) => (
        <Stack spacing={0.25}>
          <Typography variant="body2" fontWeight={600}>
            {row.entryDate}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {LEDGER_ENTRY_LABEL[row.entryType]}
            {row.reason ? ` · ${row.reason}` : ''}
          </Typography>
        </Stack>
      ),
    },
    {
      key: 'amount',
      header: 'Amount',
      align: 'right',
      cell: (row) => (
        <Typography
          variant="body2"
          color={row.direction < 0 ? 'error.main' : 'text.primary'}
          fontWeight={600}
        >
          {row.direction < 0 ? '− ' : '+ '}
          {formatAmount(row.amount)}
        </Typography>
      ),
    },
    {
      key: 'balance',
      header: 'Owed after',
      align: 'right',
      cell: (row) => formatAmount(row.balanceAfter),
    },
  ];

  return (
    <Dialog open={workerId !== null} fullWidth maxWidth="md" onClose={onClose}>
      <DialogTitle>
        {sheet ? `${sheet.workerName} — ${sheet.workerCode}` : 'Settlement'}
      </DialogTitle>
      <DialogContent>
        {settlement.isLoading && (
          <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
            <CircularProgress />
          </Box>
        )}
        {settlement.isError && (
          <Alert severity="error">{apiErrorDetail(settlement.error)}</Alert>
        )}
        {sheet && (
          <Stack spacing={2} sx={{ pt: 1 }}>
            {/*
              The identity, set out as the sheet sets it out. The result is drawn apart from
              its terms so that "what is he owed" is the one figure the eye lands on.
            */}
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
              <Figure label="Earned" amount={sheet.earnedAmount} />
              <Figure
                label="Advances"
                amount={sheet.advanceAmount}
                hint={
                  sheet.openAdvanceAmount > 0
                    ? `${formatAmount(sheet.openAdvanceAmount)} still open`
                    : sheet.advanceAmount > 0
                      ? 'all recovered'
                      : undefined
                }
              />
              <Figure label="Paid" amount={sheet.paidAmount} />
              {sheet.deductionAmount > 0 && (
                <Figure label="Deductions" amount={sheet.deductionAmount} />
              )}
            </Stack>
            <Owed amount={sheet.netPayable} />

            <Typography variant="subtitle2">Every line on his account</Typography>
            <RecordTable
              columns={columns}
              rows={sheet.entries}
              rowKey={(row) => row.id}
              nested
              ariaLabel="Ledger entries"
              empty={
                <Typography color="text.secondary">
                  Nothing on his account yet. A day is posted here once it is verified.
                </Typography>
              }
            />
          </Stack>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        {onPay && sheet && (
          <Button
            variant="contained"
            disabled={sheet.netPayable <= 0}
            onClick={() => onPay(sheet.workerId)}
          >
            Pay him
          </Button>
        )}
      </DialogActions>
    </Dialog>
  );
}

function Figure({ label, amount, hint }: { label: string; amount: number; hint?: string | undefined }) {
  return (
    <Paper variant="outlined" sx={{ p: 1.5, flex: 1 }}>
      <Typography variant="overline" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="h6">{formatAmount(amount)}</Typography>
      {hint && (
        <Typography variant="caption" color="text.secondary">
          {hint}
        </Typography>
      )}
    </Paper>
  );
}

/**
 * The figure the payday is about, with its sign said in words. A man who has drawn more than
 * he has earned is not shown a minus sign and left to work out what it means.
 */
function Owed({ amount }: { amount: number }) {
  const owes = amount < 0;
  return (
    <Paper
      variant="outlined"
      sx={{ p: 2, borderColor: owes ? tokens.stop : tokens.signal }}
    >
      <Typography variant="overline" color="text.secondary">
        {owes ? 'He owes the firm' : 'Owed to him'}
      </Typography>
      <Typography variant="h5" color={owes ? 'error.main' : 'text.primary'}>
        {formatAmount(Math.abs(amount))}
      </Typography>
      <Typography variant="caption" color="text.secondary">
        {owes
          ? 'He has drawn more than his verified days come to. Nothing can be paid until the wages catch up.'
          : 'Earned, less advances, less what he has already been paid.'}
      </Typography>
    </Paper>
  );
}

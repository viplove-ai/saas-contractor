import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
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
import { RecordTable, type RecordColumn } from '../../shared/RecordTable';
import { useSelectedSite } from '../../shared/siteSelection';
import { useAuth } from '../auth/AuthContext';
import { useDecideAdvance, useMySites, useWorkerAdvances, useWorkerPayments } from './api';
import { PayWagesDialog } from './PayWagesDialog';
import { RecordAdvanceDialog } from './RecordAdvanceDialog';
import { SettlementDialog } from './SettlementDialog';
import { PAYMENT_MODE_LABEL, type WorkerAdvance, type WorkerPayment } from './types';

/**
 * Money handed to the men: advances against wages, and the paydays that settle them.
 *
 * <p>The register has been in the database since V1 — the ledger nets an advance out of a
 * man's wages the day it is approved, and the settlement sheet has always read right — with
 * no screen onto any of it. Advances were typed through Swagger or not at all, nobody could
 * approve one, and the payday, the one act that marks an advance recovered, did not exist.
 * So a man's advance stood OPEN for ever on a row nobody could see.</p>
 *
 * <p>Three people meet here and hold three different keys. Whoever holds
 * {@code worker:advance} records the hand-over — every role since V63, because the ration
 * and the festival money are handed over at the gate by the man who took him on, and it
 * deducts nothing until approved; {@code advance:settle:approve} is that decision, and the
 * rows waiting on it are drawn first, because a queue that has to be found is a queue nobody
 * clears; and {@code payment:record} is the payday. Anybody who can read the worker register
 * can read this one — a supervisor is entitled to know what the men on his site have drawn,
 * because they will ask him.</p>
 *
 * <p>The two figures at the top are kept apart for the reason the float register keeps its
 * two apart: what is still to come out of wages and what the site has paid out are two
 * different questions, and netting them answers neither.</p>
 */
export function WorkerAdvancesPage() {
  const { hasPermission } = useAuth();
  const mayRecord = hasPermission('worker:advance');
  const mayDecide = hasPermission('advance:settle:approve');
  const mayPay = hasPermission('payment:record');
  const maySeeSheet = hasPermission('wage:read');

  const mySites = useMySites();
  const [siteId, setSiteId] = useSelectedSite(mySites.data);
  const advances = useWorkerAdvances(siteId || undefined);
  const payments = useWorkerPayments(siteId || undefined);
  const decide = useDecideAdvance();

  const [recording, setRecording] = useState(false);
  const [paying, setPaying] = useState<{ workerId?: string | undefined } | null>(null);
  const [sheetFor, setSheetFor] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const rows = advances.data?.content ?? [];
  const waiting = rows.filter(
    (row) => row.workflowStatus === 'DRAFT' || row.workflowStatus === 'SUBMITTED',
  );
  const decided = rows.filter((row) => !waiting.includes(row));
  const stillOpen = rows
    .filter((row) => row.workflowStatus === 'APPROVED' && row.recoverable)
    .reduce((sum, row) => sum + row.balanceAmount, 0);
  const paidOut = (payments.data?.content ?? []).reduce((sum, row) => sum + row.amount, 0);

  async function decideOn(row: WorkerAdvance, action: 'APPROVE' | 'REJECT') {
    setError(null);
    try {
      await decide.mutateAsync({ advanceId: row.id, workerId: row.workerId, action });
    } catch (cause) {
      setError(apiErrorDetail(cause));
    }
  }

  const advanceColumns: RecordColumn<WorkerAdvance>[] = [
    {
      key: 'worker',
      header: 'Worker',
      card: 'title',
      cell: (row) => (
        <Stack spacing={0.25}>
          <Typography variant="body2" fontWeight={600}>
            {row.workerName ?? 'A man no longer on the rolls'}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {row.advanceNumber} · {row.advanceDate} · {PAYMENT_MODE_LABEL[row.paymentMode].toLowerCase()}
          </Typography>
        </Stack>
      ),
    },
    {
      key: 'purpose',
      header: 'What for',
      cell: (row) => (
        <Stack spacing={0.25}>
          <Typography variant="body2">{row.purpose ?? '—'}</Typography>
          {!row.recoverable && (
            <Typography variant="caption" color="text.secondary">
              borne by the firm
            </Typography>
          )}
        </Stack>
      ),
    },
    {
      key: 'amount',
      header: 'Handed over',
      align: 'right',
      cell: (row) => formatAmount(row.amount),
    },
    {
      key: 'open',
      header: 'Still to recover',
      align: 'right',
      cell: (row) =>
        row.workflowStatus === 'APPROVED' && row.recoverable
          ? formatAmount(row.balanceAmount)
          : '—',
    },
    {
      key: 'status',
      header: 'Status',
      card: 'status',
      cell: (row) => <AdvanceChip row={row} />,
    },
    {
      key: 'actions',
      header: '',
      align: 'right',
      card: 'actions',
      cell: (row) => (
        <Stack direction="row" spacing={1} justifyContent="flex-end">
          {maySeeSheet && (
            <Button size="small" onClick={() => setSheetFor(row.workerId)}>
              His account
            </Button>
          )}
          {mayDecide &&
            (row.workflowStatus === 'DRAFT' || row.workflowStatus === 'SUBMITTED') && (
              <>
                <Button
                  size="small"
                  color="error"
                  disabled={decide.isPending}
                  onClick={() => decideOn(row, 'REJECT')}
                >
                  Reject
                </Button>
                <Button
                  size="small"
                  variant="contained"
                  disabled={decide.isPending}
                  onClick={() => decideOn(row, 'APPROVE')}
                >
                  Approve
                </Button>
              </>
            )}
        </Stack>
      ),
    },
  ];

  const paymentColumns: RecordColumn<WorkerPayment>[] = [
    {
      key: 'worker',
      header: 'Worker',
      card: 'title',
      cell: (row) => (
        <Stack spacing={0.25}>
          <Typography variant="body2" fontWeight={600}>
            {row.workerName ?? 'A man no longer on the rolls'}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {row.paymentNumber} · {row.paymentDate} · {PAYMENT_MODE_LABEL[row.paymentMode].toLowerCase()}
            {row.referenceNumber ? ` · ${row.referenceNumber}` : ''}
          </Typography>
        </Stack>
      ),
    },
    {
      key: 'amount',
      header: 'Paid',
      align: 'right',
      cell: (row) => formatAmount(row.amount),
    },
    {
      key: 'recovered',
      header: 'Advances closed',
      align: 'right',
      cell: (row) => (row.advancesRecovered > 0 ? formatAmount(row.advancesRecovered) : '—'),
    },
    {
      key: 'actions',
      header: '',
      align: 'right',
      card: 'actions',
      cell: (row) =>
        maySeeSheet ? (
          <Button size="small" onClick={() => setSheetFor(row.workerId)}>
            His account
          </Button>
        ) : null,
    },
  ];

  return (
    <Stack spacing={3}>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        justifyContent="space-between"
        alignItems={{ sm: 'center' }}
      >
        <Stack spacing={0.5}>
          <Typography variant="h1">Advances and wages</Typography>
          <Typography color="text.secondary">
            What the men have drawn against their wages, and what they have been paid.
          </Typography>
        </Stack>
        <Stack direction="row" spacing={1}>
          {mayRecord && (
            <Button variant="outlined" disabled={!siteId} onClick={() => setRecording(true)}>
              Record an advance
            </Button>
          )}
          {mayPay && (
            <Button
              variant="contained"
              color="secondary"
              disabled={!siteId}
              onClick={() => setPaying({})}
            >
              Pay wages
            </Button>
          )}
        </Stack>
      </Stack>

      <TextField
        select
        label="Site"
        value={siteId}
        onChange={(event) => setSiteId(event.target.value)}
        sx={{ maxWidth: 280 }}
      >
        {(mySites.data ?? []).map((site) => (
          <MenuItem key={site.id} value={site.id}>
            {site.code} — {site.name}
          </MenuItem>
        ))}
      </TextField>

      {error && (
        <Alert severity="error" onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <Tile
          label="Still to come out of wages"
          amount={stillOpen}
          hint="Approved advances no payday has closed yet."
          accent
        />
        <Tile
          label="Paid out at this site"
          amount={paidOut}
          hint="Wages handed over, on every payday recorded here."
        />
      </Stack>

      {advances.isLoading && (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress />
        </Box>
      )}
      {advances.isError && <Alert severity="error">{apiErrorDetail(advances.error)}</Alert>}

      {advances.data && waiting.length > 0 && (
        <Stack spacing={1}>
          <Typography variant="h2" sx={{ fontSize: '1.2rem' }}>
            Waiting on a decision
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Recorded as handed over. Nothing comes off his wages until one of these is approved.
          </Typography>
          <RecordTable
            columns={advanceColumns}
            rows={waiting}
            rowKey={(row) => row.id}
            ariaLabel="Advances waiting on a decision"
            empty={null}
          />
        </Stack>
      )}

      {advances.data && (
        <Stack spacing={1}>
          <Typography variant="h2" sx={{ fontSize: '1.2rem' }}>
            Advances
          </Typography>
          <RecordTable
            columns={advanceColumns}
            rows={decided}
            rowKey={(row) => row.id}
            ariaLabel="Advances"
            empty={
              <Typography color="text.secondary">
                {siteId
                  ? 'Nothing has been handed to anybody at this site.'
                  : 'Pick a site to see what its men have drawn.'}
              </Typography>
            }
          />
        </Stack>
      )}

      {payments.data && (
        <Stack spacing={1}>
          <Typography variant="h2" sx={{ fontSize: '1.2rem' }}>
            Paydays
          </Typography>
          <RecordTable
            columns={paymentColumns}
            rows={payments.data.content}
            rowKey={(row) => row.id}
            ariaLabel="Wage payments"
            empty={
              <Typography color="text.secondary">
                No wages have been paid out through here yet.
              </Typography>
            }
          />
        </Stack>
      )}

      {recording && siteId && (
        <RecordAdvanceDialog siteId={siteId} onClose={() => setRecording(false)} />
      )}
      {paying && siteId && (
        <PayWagesDialog siteId={siteId} workerId={paying.workerId} onClose={() => setPaying(null)} />
      )}
      <SettlementDialog
        workerId={sheetFor}
        onClose={() => setSheetFor(null)}
        onPay={
          mayPay && siteId
            ? (workerId) => {
                setSheetFor(null);
                setPaying({ workerId });
              }
            : undefined
        }
      />
    </Stack>
  );
}

/**
 * Two statuses read as one chip. The workflow says whether the office agreed it comes out
 * of his wages; recovery says how much has since come out. Only an approved recoverable
 * advance has a recovery worth stating.
 */
function AdvanceChip({ row }: { row: WorkerAdvance }) {
  switch (row.workflowStatus) {
    case 'DRAFT':
    case 'SUBMITTED':
      return <Chip size="small" color="warning" variant="outlined" label="Waiting" />;
    case 'REJECTED':
      return <Chip size="small" color="error" variant="outlined" label="Rejected" />;
    case 'CANCELLED':
      return <Chip size="small" variant="outlined" label="Cancelled" />;
    default:
      break;
  }
  if (!row.recoverable) {
    return <Chip size="small" variant="outlined" label="Borne by the firm" />;
  }
  switch (row.status) {
    case 'RECOVERED':
      return <Chip size="small" variant="outlined" label="Recovered" />;
    case 'PARTIALLY_RECOVERED':
      return <Chip size="small" color="warning" variant="outlined" label="Part recovered" />;
    case 'WRITTEN_OFF':
      return <Chip size="small" variant="outlined" label="Written off" />;
    default:
      return <Chip size="small" color="success" variant="outlined" label="Open" />;
  }
}

function Tile({
  label,
  amount,
  hint,
  accent,
}: {
  label: string;
  amount: number;
  hint: string;
  accent?: boolean;
}) {
  return (
    <Paper
      variant="outlined"
      sx={{ p: 2, flex: 1, borderColor: accent ? tokens.signal : 'divider' }}
    >
      <Typography variant="overline" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="h6">{formatAmount(amount)}</Typography>
      <Typography variant="caption" color="text.secondary">
        {hint}
      </Typography>
    </Paper>
  );
}
